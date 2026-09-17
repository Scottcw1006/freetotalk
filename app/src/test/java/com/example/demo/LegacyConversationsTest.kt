package com.example.demo

import com.example.demo.data.deleteLegacyConversations
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The old one-file-per-thread directory sits right next to gigabytes of unpacked model
 * files. Clearing it must take exactly that directory and nothing beside it, and must
 * never be the reason the app fails to start.
 */
class LegacyConversationsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var neighbours: Map<File, ByteArray>

    private fun layOut(withLegacy: Boolean = true) {
        filesDir = temp.newFolder("files")
        if (withLegacy) {
            val legacy = File(filesDir, "conversations").apply { mkdirs() }
            listOf("a", "b", "c").forEach { File(legacy, "$it.json").writeText("""{"id":"$it"}""") }
        }
        val other = File(filesDir, "other").apply { mkdirs() }
        neighbours = listOf(
            File(filesDir, "gemma3-1b.task") to byteArrayOf(1, 2, 3, 4),
            File(filesDir, "qwen.task.part") to byteArrayOf(5, 6),
            File(filesDir, "profileInstalled") to byteArrayOf(7),
            File(filesDir, "conversations.bak") to byteArrayOf(8, 9),
            File(other, "inside.bin") to byteArrayOf(10, 11, 12),
        ).onEach { (file, bytes) -> file.writeBytes(bytes) }.toMap()
    }

    private fun assertNeighboursUntouched() {
        neighbours.forEach { (file, bytes) ->
            assertTrue("${file.name} is gone", file.isFile)
            assertArrayEquals("${file.name} changed", bytes, file.readBytes())
        }
    }

    private val legacy get() = File(filesDir, "conversations")

    @Test
    fun `the old directory goes and everything beside it stays`() {
        layOut()

        deleteLegacyConversations(filesDir)

        assertFalse(legacy.exists())
        assertNeighboursUntouched()
    }

    @Test
    fun `nothing to clear is not an error`() {
        layOut(withLegacy = false)

        deleteLegacyConversations(filesDir)

        assertFalse(legacy.exists())
        assertNeighboursUntouched()
    }

    @Test
    fun `a directory that cannot be cleared does not throw, and is cleared next time`() {
        layOut()
        legacy.setWritable(false)
        try {
            deleteLegacyConversations(filesDir)
            assertTrue("the setup should have made the files undeletable", legacy.exists())
            assertNeighboursUntouched()
        } finally {
            legacy.setWritable(true)
        }

        deleteLegacyConversations(filesDir)

        assertFalse(legacy.exists())
        assertNeighboursUntouched()
    }
}
