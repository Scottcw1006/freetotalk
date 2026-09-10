package com.example.demo.data

import android.content.Context
import com.example.demo.llm.ModelSpec
import com.example.demo.persona.Persona

/**
 * Remembers where the user was, so that leaving the app and coming back lands them
 * where they left off rather than wherever the newest saved thread happens to be.
 *
 * The thread id is kept separately from the threads themselves because a thread the
 * user has not written in yet is deliberately never persisted — switching model opens
 * exactly such a thread, and without this the switch would be silently forgotten the
 * moment the process died.
 */
class SessionPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    var lastThreadId: String?
        get() = prefs.getString(KEY_THREAD, null)
        private set(value) = prefs.edit().putString(KEY_THREAD, value).apply()

    var lastModel: ModelSpec
        get() = ModelSpec.of(prefs.getString(KEY_MODEL, null))
        private set(value) = prefs.edit().putString(KEY_MODEL, value.name).apply()

    var lastPersona: Persona
        get() = Persona.of(prefs.getString(KEY_PERSONA, null))
        private set(value) = prefs.edit().putString(KEY_PERSONA, value.name).apply()

    fun remember(threadId: String, model: ModelSpec, persona: Persona) {
        prefs.edit()
            .putString(KEY_THREAD, threadId)
            .putString(KEY_MODEL, model.name)
            .putString(KEY_PERSONA, persona.name)
            .apply()
    }

    private companion object {
        const val KEY_THREAD = "last_thread_id"
        const val KEY_MODEL = "last_model"
        const val KEY_PERSONA = "last_persona"
    }
}
