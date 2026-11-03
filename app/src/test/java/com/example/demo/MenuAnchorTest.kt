package com.example.demo

import androidx.compose.ui.geometry.Offset
import com.example.demo.ui.MenuAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A long-press menu fades out where it was. Closing it — by picking an item or by
 * tapping elsewhere — used to forget where the finger had been, and the fading menu
 * was drawn at the bubble's top-left corner instead.
 */
class MenuAnchorTest {

    private val finger = Offset(620f, 70f)

    @Test
    fun `a menu opens where the finger was`() {
        val open = MenuAnchor.Closed.openedAt(finger)

        assertTrue(open.expanded)
        assertEquals(finger, open.position)
    }

    @Test
    fun `a closing menu stays where it was while it fades out`() {
        val closed = MenuAnchor.Closed.openedAt(finger).closed()

        assertFalse(closed.expanded)
        assertEquals(finger, closed.position)
    }

    @Test
    fun `opening again moves the menu to the new finger`() {
        val elsewhere = Offset(90f, 300f)

        assertEquals(elsewhere, MenuAnchor.Closed.openedAt(finger).closed().openedAt(elsewhere).position)
    }
}
