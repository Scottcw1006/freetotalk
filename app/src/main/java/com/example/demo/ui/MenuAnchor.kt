package com.example.demo.ui

import androidx.compose.ui.geometry.Offset

/**
 * Whether a long-press menu is showing, and where. Kept as two things because a menu
 * keeps being drawn while it fades out after closing: if closing also forgot the
 * position, the fading menu would jump to the bubble's corner on its way out.
 */
data class MenuAnchor(val expanded: Boolean, val position: Offset) {

    fun openedAt(at: Offset): MenuAnchor = MenuAnchor(expanded = true, position = at)

    fun closed(): MenuAnchor = copy(expanded = false)

    companion object {
        val Closed = MenuAnchor(expanded = false, position = Offset.Zero)
    }
}
