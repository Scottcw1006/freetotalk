package com.example.demo.data

import android.content.Context
import com.example.demo.chat.Author
import com.example.demo.chat.ChatMessage
import com.example.demo.chat.Conversation
import com.example.demo.chat.repairModelText
import com.example.demo.llm.ModelSpec
import com.example.demo.persona.Persona
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Conversations live one-per-file on disk. That is a deliberate choice over a single
 * table: threads can never bleed into one another because they are never in the same
 * place to begin with, and deleting one is deleting a file.
 *
 * The data is a few kilobytes of text per thread, so JSON is plenty — no database, no
 * annotation processor, no schema migrations.
 */
class ConversationStore(context: Context) {

    private val directory = File(context.filesDir, "conversations").apply { mkdirs() }

    suspend fun loadAll(): List<Conversation> = withContext(Dispatchers.IO) {
        directory.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { file -> runCatching { file.readConversation() }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun save(conversation: Conversation) = withContext(Dispatchers.IO) {
        // A thread the user never wrote in is noise in the history list.
        if (conversation.isBlank) {
            delete(conversation.id)
            return@withContext
        }
        fileFor(conversation.id).writeText(conversation.toJson().toString())
    }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { fileFor(id).delete() }
    }

    private fun fileFor(id: String) = File(directory, "$id.json")

    private fun File.readConversation(): Conversation {
        val root = JSONObject(readText())
        val messages = root.getJSONArray("messages").let { array ->
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                ChatMessage(
                    id = item.getLong("id"),
                    author = Author.valueOf(item.getString("author")),
                    // Repaired on the way out, not on the way in: a thread saved
                    // before a fix landed should still read correctly today.
                    text = item.getString("text").repairModelText(),
                    createdAt = item.getLong("createdAt"),
                )
            }
        }
        return Conversation(
            id = root.getString("id"),
            persona = Persona.of(root.getString("persona")),
            // Threads written before models were switchable were all Qwen, so they
            // are pinned to it rather than following whatever the default is today.
            model = ModelSpec.entries.firstOrNull { it.name == root.optString("model") }
                ?: ModelSpec.Qwen05B,
            createdAt = root.getLong("createdAt"),
            updatedAt = root.getLong("updatedAt"),
            messages = messages,
        )
    }

    private fun Conversation.toJson(): JSONObject {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("author", message.author.name)
                    .put("text", message.text)
                    .put("createdAt", message.createdAt)
            )
        }
        return JSONObject()
            .put("id", id)
            .put("persona", persona.name)
            .put("model", model.name)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("messages", array)
    }
}
