package com.example.demo.data

import android.content.Context
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.demo.chat.Conversation
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Where saved threads come from and go. One per process: Room only tells its own
 * subscribers about writes made through the same database instance, and a second
 * instance would leave the history list stuck on an old picture.
 *
 * It only knows what was saved. The open thread may never be saved, and a reply still
 * streaming in is not saved yet, so nothing that must show what the user can see —
 * search above all — reads from here alone. There is deliberately no query method.
 *
 * When the stored threads as a whole cannot be read, they are thrown away and the store
 * starts over, and [wasReset] says so once, so the user is told rather than finding the
 * history mysteriously empty. Failing to write is different: the data is fine, so it is
 * reported to the caller and nothing is discarded.
 */
class ConversationRepository private constructor(context: Context) {

    private val app = context.applicationContext
    private val marker = app.getSharedPreferences(MARKER_FILE, Context.MODE_PRIVATE)
    private val database = MutableStateFlow(open())
    private val resetting = Mutex()
    private var automaticResetsLeft = 1

    private val _wasReset = MutableStateFlow(false)
    /** True once the stored threads were found unreadable and discarded, until acknowledged. */
    val wasReset: StateFlow<Boolean> = _wasReset.asStateFlow()

    fun acknowledgeReset() {
        _wasReset.value = false
    }

    /** Every readable saved thread, newest first, re-emitted whenever anything is written. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val all: Flow<List<Conversation>> = database.flatMapLatest { db ->
        db.conversations().observeAll()
            .map(ConversationReader::all)
            // Resetting swaps in a new database, which this flow then follows. If that
            // does not help either, the list stays as it last was rather than taking the
            // app down.
            .catch { e -> if (e.meansStoreIsUnreadable()) reset(db) else throw e }
            .catch { }
    }

    suspend fun find(id: String): Conversation? = attempt { db ->
        ConversationReader.find(db.conversations().byId(id), id)
    }.getOrNull()

    /** False when the thread could not be written; whatever was saved before is untouched. */
    suspend fun save(conversation: Conversation): Boolean = attempt { db ->
        when (val action = conversation.toSaveAction()) {
            is SaveAction.Write -> db.conversations().write(action.record)
            is SaveAction.Delete -> db.conversations().delete(action.id)
        }
    }.isSuccess

    suspend fun delete(id: String): Boolean = attempt { db ->
        db.conversations().delete(id)
    }.isSuccess

    suspend fun removeLegacyThreads() = withContext(Dispatchers.IO) {
        deleteLegacyConversations(app.filesDir)
    }

    /** Runs [block], and once more on a fresh store if the first failure meant the store was unreadable. */
    private suspend fun <T> attempt(block: suspend (ConversationDatabase) -> T): Result<T> {
        val db = database.value
        val first = runCatchingUnlessCancelled { block(db) }
        val error = first.exceptionOrNull() ?: return first
        if (!error.meansStoreIsUnreadable()) return first
        reset(db)
        return runCatchingUnlessCancelled { block(database.value) }
    }

    private suspend fun reset(broken: ConversationDatabase) = resetting.withLock {
        // Someone else already replaced it while this caller was failing on the old one.
        if (database.value !== broken) return@withLock
        if (automaticResetsLeft == 0) return@withLock
        automaticResetsLeft--
        withContext(Dispatchers.IO) {
            runCatching { broken.close() }
            app.deleteDatabase(ConversationDatabase.FILE_NAME)
        }
        _wasReset.value = true
        database.value = open()
    }

    private fun open(): ConversationDatabase = Room
        .databaseBuilder(app, ConversationDatabase::class.java, ConversationDatabase.FILE_NAME)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .addCallback(StartOverDetector())
        .build()

    /**
     * Android's own handling of a corrupt database file is to delete it and quietly
     * create a new one, which Room goes along with. The only trace that leaves is a
     * database being created when one had been created before — so that is what is
     * watched for, alongside Room's own drop-and-recreate on a schema it cannot use.
     *
     * The marker lives in its own preferences file and goes away with the app's data,
     * so a fresh install or cleared data never looks like a reset.
     */
    private inner class StartOverDetector : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            if (marker.getBoolean(KEY_CREATED, false)) {
                _wasReset.value = true
            } else {
                marker.edit().putBoolean(KEY_CREATED, true).commit()
            }
        }

        override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
            _wasReset.value = true
        }
    }

    companion object {
        private const val MARKER_FILE = "conversation_store"
        private const val KEY_CREATED = "database_created"

        @Volatile
        private var instance: ConversationRepository? = null

        fun get(context: Context): ConversationRepository =
            instance ?: synchronized(this) {
                instance ?: ConversationRepository(context).also { instance = it }
            }
    }
}

/**
 * A corrupt file, a file that cannot be opened, or a database Room refuses to use (or
 * one that was closed underneath us after Android discarded it). Running out of space or
 * a read-only file are not in this list: the saved threads are still there.
 */
private fun Throwable.meansStoreIsUnreadable(): Boolean =
    this is SQLiteDatabaseCorruptException ||
        this is SQLiteCantOpenDatabaseException ||
        this is IllegalStateException

private inline fun <T> runCatchingUnlessCancelled(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
