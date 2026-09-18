package com.example.demo.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A save or a delete here is several statements, and each of [write] and [delete] runs
 * them inside one transaction. That is load-bearing: clearing a thread's old message rows
 * and writing the new ones as separate steps would leave a moment in which a reader finds
 * the thread with no messages, or half of them — which is exactly how threads used to
 * vanish from the history list when two saves of the same thread overlapped. Nothing
 * tests this at the database level; the only guards are this comment and what is checked
 * by hand on a device.
 *
 * Message rows are removed by an explicit statement rather than a cascading foreign key.
 * A cascade only fires on a connection that has foreign keys switched on, and it would
 * turn "replace the thread's row" into "wipe its messages".
 *
 * The author column is bound in rather than spelled out in the SQL, so the queries never
 * hold their own idea of who can have written a message.
 */
@Dao
abstract class ConversationDao {

    /**
     * What the history list shows of every thread, without reading the messages it does
     * not show. Which message a title or a preview comes from is decided here; what is
     * made of it is decided by [HistoryRow.toEntryOrNull].
     */
    @Query(
        """
        SELECT c.id, c.persona, c.model, c.createdAt, c.updatedAt,
            (SELECT m.text FROM messages m
              WHERE m.conversationId = c.id AND m.author = :user AND m.deleted = 0
              ORDER BY m.position LIMIT 1) AS firstSaid,
            (SELECT m.text FROM messages m
              WHERE m.conversationId = c.id AND m.deleted = 0
              ORDER BY m.position DESC LIMIT 1) AS lastShown,
            EXISTS(SELECT 1 FROM messages m
              WHERE m.conversationId = c.id AND m.author = :user) AS everSaid,
            EXISTS(SELECT 1 FROM messages m
              WHERE m.conversationId = c.id AND m.author NOT IN (:knownAuthors)) AS hasUnknownAuthor
        FROM conversations c
        """
    )
    abstract fun observeHistory(user: String, knownAuthors: List<String>): Flow<List<HistoryRow>>

    /** A list, not a single thread, so it goes through the same reading rule as every other read. */
    @Transaction
    open suspend fun thread(id: String): List<ThreadRecord> =
        conversation(id).map { ThreadRecord(it, messagesOf(listOf(it.id))) }

    @Query("SELECT id FROM conversations ORDER BY updatedAt DESC")
    abstract suspend fun ids(): List<String>

    /** Whole threads, for the ones in [ids] that are still there. */
    @Transaction
    open suspend fun threads(ids: List<String>): List<ThreadRecord> {
        val messages = messagesOf(ids).groupBy { it.conversationId }
        return conversations(ids).map { ThreadRecord(it, messages[it.id].orEmpty()) }
    }

    @Transaction
    open suspend fun write(record: ThreadRecord) {
        upsert(record.conversation)
        deleteMessagesOf(record.conversation.id)
        insert(record.messages)
    }

    @Transaction
    open suspend fun delete(id: String) {
        deleteMessagesOf(id)
        deleteConversation(id)
    }

    @Query("SELECT * FROM conversations WHERE id = :id")
    protected abstract suspend fun conversation(id: String): List<ConversationRecord>

    @Query("SELECT * FROM conversations WHERE id IN (:ids)")
    protected abstract suspend fun conversations(ids: List<String>): List<ConversationRecord>

    @Query("SELECT * FROM messages WHERE conversationId IN (:ids) ORDER BY conversationId, position")
    protected abstract suspend fun messagesOf(ids: List<String>): List<MessageRecord>

    // Not REPLACE: that is a delete followed by an insert, and the day message rows hang
    // off this one by a foreign key it would take them all with it.
    @Upsert
    protected abstract suspend fun upsert(conversation: ConversationRecord)

    @Insert
    protected abstract suspend fun insert(messages: List<MessageRecord>)

    @Query("DELETE FROM messages WHERE conversationId = :id")
    protected abstract suspend fun deleteMessagesOf(id: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    protected abstract suspend fun deleteConversation(id: String)
}

/**
 * [SCHEMA_VERSION] goes up with every change to the tables, and every step up comes with
 * a migration in [ConversationMigrations]. A step without one does not lose anything —
 * the store simply cannot be opened by that build, and says so — but nobody can read
 * their threads until a build that has the migration arrives.
 */
@Database(entities = [ConversationRecord::class, MessageRecord::class], version = SCHEMA_VERSION)
abstract class ConversationDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao

    companion object {
        /** The file under the app's databases directory. */
        const val FILE_NAME = "conversations.db"
    }
}

const val SCHEMA_VERSION = 2
