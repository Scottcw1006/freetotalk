package com.example.demo.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Every write here is a single statement, and that is load-bearing: a single statement
 * takes effect whole, so a reader can never catch a thread half-saved or momentarily
 * gone. Nothing tests this at the database level — splitting a save or a delete into
 * several statements would need a transaction and tests to come back with it.
 */
@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations")
    fun observeAll(): Flow<List<ConversationRecord>>

    /** A list, not a single row, so it goes through the same reading rule as [observeAll]. */
    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: String): List<ConversationRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun write(record: ConversationRecord)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [ConversationRecord::class], version = 1)
abstract class ConversationDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao

    companion object {
        /** The file under the app's databases directory. */
        const val FILE_NAME = "conversations.db"
    }
}
