package com.example.demo.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Every step the tables have taken. A new [SCHEMA_VERSION] needs a new entry here. */
object ConversationMigrations {
    val all: Array<Migration> = arrayOf(MessagesGetTheirOwnRows)
}

/**
 * Version 1 kept one row per thread with every message packed into a JSON column.
 * Version 2 gives each message a row of its own and has no such column.
 *
 * Room runs a migration inside a transaction, so this either happens whole or not at all;
 * nothing here commits on its own. It works on the existing file rather than building a
 * new one beside it: the app tells "the store was thrown away" from the database being
 * created a second time, and a rebuilt file would announce exactly that to everyone who
 * upgrades.
 *
 * What an old row says is decided by [toThreadRecordOrNull], the same function the tests
 * run — not by SQL of its own. A row it cannot make sense of is left behind alone, as is
 * a row that cannot even be fetched. The thread's updated time is carried over untouched:
 * being moved is not being updated.
 */
private object MessagesGetTheirOwnRows : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `conversations` RENAME TO `conversations_v1`")
        // Word for word what Room expects of version 2 — see app/schemas.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `persona` TEXT NOT NULL, " +
                "`model` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `messages` (`conversationId` TEXT NOT NULL, `id` INTEGER NOT NULL, " +
                "`position` INTEGER NOT NULL, `author` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                "PRIMARY KEY(`conversationId`, `id`))"
        )
        for (id in db.legacyIds()) {
            val thread = runCatching { db.legacyRow(id)?.toThreadRecordOrNull() }.getOrNull() ?: continue
            db.insert(thread)
        }
        db.execSQL("DROP TABLE `conversations_v1`")
    }

    private fun SupportSQLiteDatabase.legacyIds(): List<String> =
        query("SELECT `id` FROM `conversations_v1`").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    // One row at a time: a single oversized row then fails on its own instead of
    // failing the cursor that every other row is coming through.
    private fun SupportSQLiteDatabase.legacyRow(id: String): LegacyThreadRow? =
        query(
            "SELECT `id`, `persona`, `model`, `createdAt`, `updatedAt`, `messages` " +
                "FROM `conversations_v1` WHERE `id` = ?",
            arrayOf(id),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            LegacyThreadRow(
                id = cursor.getString(0),
                persona = cursor.getString(1),
                model = cursor.getString(2),
                createdAt = cursor.getLong(3),
                updatedAt = cursor.getLong(4),
                messages = cursor.getString(5),
            )
        }

    private fun SupportSQLiteDatabase.insert(thread: ThreadRecord) {
        insert("conversations", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
            put("id", thread.conversation.id)
            put("persona", thread.conversation.persona)
            put("model", thread.conversation.model)
            put("createdAt", thread.conversation.createdAt)
            put("updatedAt", thread.conversation.updatedAt)
        })
        for (message in thread.messages) {
            insert("messages", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("conversationId", message.conversationId)
                put("id", message.id)
                put("position", message.position)
                put("author", message.author)
                put("text", message.text)
                put("createdAt", message.createdAt)
                put("deleted", if (message.deleted) 1 else 0)
            })
        }
    }
}
