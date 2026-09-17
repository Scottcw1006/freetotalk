package com.example.demo.data

import java.io.File

/** Where versions before the database kept one JSON file per thread. */
private const val LEGACY_DIRECTORY = "conversations"

/**
 * Removes the old one-file-per-thread directory. Those threads are not carried over, and
 * nothing reads them any more, so leaving them would only keep private conversations on
 * the phone that the user can neither see nor delete.
 *
 * Only that one directory is touched: the unpacked models sit right beside it, and
 * taking one level too many would mean unpacking gigabytes again. A directory that
 * cannot be removed is left for the next launch — this must never stop the app starting.
 */
fun deleteLegacyConversations(filesDir: File) {
    runCatching { File(filesDir, LEGACY_DIRECTORY).deleteRecursively() }
}
