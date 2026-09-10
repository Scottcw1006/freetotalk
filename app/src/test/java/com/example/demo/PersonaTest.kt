package com.example.demo

import com.example.demo.persona.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five voices the assistant can wear. The whole feature is one system prompt plus
 * one opening line, so the things worth pinning down are the ones the reader meets
 * directly: which voice opens, what it says first, and how the drawer tells them apart.
 */
class PersonaTest {

    /**
     * The stored name comes off disk, where a persona that has since been removed or
     * renamed can still be recorded. Falling back keeps the app opening.
     */
    @Test
    fun `an unknown or missing persona name falls back to the default`() {
        assertEquals(Persona.Default, Persona.of(null))
        assertEquals(Persona.Default, Persona.of(""))
        assertEquals(Persona.Default, Persona.of("SomePersonaWeDeleted"))
    }

    @Test
    fun `a known persona name comes back as itself`() {
        Persona.entries.forEach { persona ->
            assertEquals(persona, Persona.of(persona.name))
        }
    }

    /**
     * A thread that opens with nothing is a thread the reader has to start themselves,
     * and search relies on the opener being real text — an empty thread is still
     * findable by what its persona said first.
     */
    @Test
    fun `every persona opens by saying something`() {
        Persona.entries.forEach { persona ->
            assertTrue("${persona.name} opens with nothing", persona.opener.isNotBlank())
        }
    }

    /**
     * Without a system prompt the model invents an identity every time it is asked, and
     * the persona the reader picked stops being the one that answers.
     */
    @Test
    fun `every persona says who it is`() {
        Persona.entries.forEach { persona ->
            assertTrue("${persona.name} has no system prompt", persona.systemPrompt.isNotBlank())
        }
    }

    /**
     * The drawer, the search group headers and the persona picker all label threads by
     * name and emoji. Two personas sharing either makes those labels a lie.
     */
    @Test
    fun `personas are distinguishable by name and by emoji`() {
        assertEquals(Persona.entries.size, Persona.entries.map { it.displayName }.toSet().size)
        assertEquals(Persona.entries.size, Persona.entries.map { it.emoji }.toSet().size)
        assertEquals(Persona.entries.size, Persona.entries.map { it.opener }.toSet().size)
    }

    /** Every persona is offered in the picker, so every one needs its one-line pitch. */
    @Test
    fun `every persona has a tagline and a name`() {
        Persona.entries.forEach { persona ->
            assertTrue("${persona.name} has no tagline", persona.tagline.isNotBlank())
            assertTrue("${persona.name} has no display name", persona.displayName.isNotBlank())
        }
    }
}
