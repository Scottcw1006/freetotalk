package com.example.demo.data

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.demo.chat.Author
import com.example.demo.chat.Conversation
import com.example.demo.chat.HistoryEntry
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
 * search above all — reads from here alone. It hands saved threads over; it never decides
 * what matches.
 *
 * Three ways the store can let the user down, told apart by whether the saved threads
 * themselves are damaged:
 * - damaged (a corrupt file): thrown away, the store starts over, and [wasReset] says so
 *   once;
 * - not openable, with nothing to show they are damaged (a read-only file, wrong
 *   permissions, a schema version this build has no migration from, or one newer than
 *   this build): left exactly as they are, and [cannotOpen] says so — the next launch,
 *   or the next build, simply tries again;
 * - one write that does not go through: nothing is touched, and the caller is told.
 * When in doubt it is the second: throwing threads away cannot be undone.
 */
class ConversationRepository private constructor(context: Context) {

    private val app = context.applicationContext
    private val marker = app.getSharedPreferences(MARKER_FILE, Context.MODE_PRIVATE)
    private val database = MutableStateFlow(open())
    private val resetting = Mutex()
    /**
     * A fresh database that fails straight away will not be helped by another one, so a
     * reset is only tried again once something has worked since the last one. That still
     * lets a store that goes bad a second time, later in the same session, start over.
     */
    @Volatile
    private var succeededSinceReset = true

    private val _wasReset = MutableStateFlow(false)
    /** True once the stored threads were found unreadable and discarded, until acknowledged. */
    val wasReset: StateFlow<Boolean> = _wasReset.asStateFlow()

    fun acknowledgeReset() {
        _wasReset.value = false
    }

    private val _cannotOpen = MutableStateFlow(false)
    /** True once the saved threads could not be opened this launch; they are still there. */
    val cannotOpen: StateFlow<Boolean> = _cannotOpen.asStateFlow()

    /** One entry per saved thread that can be opened, newest first, re-emitted whenever anything is written. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val history: Flow<List<HistoryEntry>> = database.flatMapLatest { db ->
        db.conversations().observeHistory(Author.You.name, Author.entries.map { it.name })
            .map { it.toHistory() }
            .onEach {
                succeededSinceReset = true
                _cannotOpen.value = false
            }
            // Resetting swaps in a new database, which this flow then follows. Anything
            // else leaves the list as it was rather than taking the app down.
            .catch { e ->
                if (e.meansStoreIsDamaged()) reset(db) else _cannotOpen.value = true
            }
    }

    suspend fun find(id: String): Conversation? = attempt { db ->
        ConversationReader.find(db.conversations().thread(id), id)
    }.getOrNull()

    /**
     * Every readable saved thread, newest first, a few at a time, so that going through
     * all of them never means holding all of them. A batch that cannot be read is
     * skipped; the rest still come.
     */
    fun savedThreads(): Flow<List<Conversation>> = flow {
        val ids = attempt { db -> db.conversations().ids() }.getOrNull().orEmpty()
        for (batch in ids.chunked(THREADS_PER_BATCH)) {
            val threads = attempt { db -> ConversationReader.all(db.conversations().threads(batch)) }
            emit(threads.getOrNull() ?: continue)
        }
    }

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
        val error = first.exceptionOrNull() ?: return first.also { succeededSinceReset = true }
        if (!error.meansStoreIsDamaged()) return first
        reset(db)
        return runCatchingUnlessCancelled { block(database.value) }
            .onSuccess { succeededSinceReset = true }
    }

    private suspend fun reset(broken: ConversationDatabase) = resetting.withLock {
        // Someone else already replaced it while this caller was failing on the old one.
        if (database.value !== broken) return@withLock
        if (!succeededSinceReset) return@withLock
        succeededSinceReset = false
        withContext(Dispatchers.IO) {
            runCatching { broken.close() }
            app.deleteDatabase(ConversationDatabase.FILE_NAME)
        }
        _wasReset.value = true
        database.value = open()
    }

    private fun open(): ConversationDatabase = Room
        .databaseBuilder(app, ConversationDatabase::class.java, ConversationDatabase.FILE_NAME)
        // No destructive fallback in either direction: a version this build cannot get
        // to fails to open, which leaves every thread where it is.
        .addMigrations(*ConversationMigrations.all)
        .addCallback(StartOverDetector())
        .build()

    /**
     * Android's own handling of a corrupt database file is to delete it and quietly
     * create a new one, which Room goes along with. The only trace that leaves is a
     * database being created when one had been created before — so that is what is
     * watched for. A migration works on the file that is already there and never
     * sets this off.
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
    }

    companion object {
        private const val MARKER_FILE = "conversation_store"
        private const val KEY_CREATED = "database_created"
        private const val THREADS_PER_BATCH = 20

        @Volatile
        private var instance: ConversationRepository? = null

        fun get(context: Context): ConversationRepository =
            instance ?: synchronized(this) {
                instance ?: ConversationRepository(context).also { instance = it }
            }
    }
}

/**
 * A corrupt file, a schema Room cannot use, or a database that was closed underneath us
 * after Android discarded it as corrupt. Anything else — a file that cannot be opened,
 * running out of space, a read-only file, any other failure — is not in this list,
 * because resetting throws every saved thread away and those threads may be fine.
 */
private fun Throwable.meansStoreIsDamaged(): Boolean =
    this is SQLiteDatabaseCorruptException ||
        (this is IllegalStateException && message.orEmpty().let { text ->
            ROOM_SCHEMA_MISMATCH in text || CLOSED_BY_CORRUPTION_HANDLER in text
        })

/** Room's message for a file whose tables do not match what this build expects. */
private const val ROOM_SCHEMA_MISMATCH = "Room cannot verify the data integrity"

/** What Android's SQLite says when a connection it already closed is used again. */
private const val CLOSED_BY_CORRUPTION_HANDLER = "attempt to re-open an already-closed object"

private inline fun <T> runCatchingUnlessCancelled(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
