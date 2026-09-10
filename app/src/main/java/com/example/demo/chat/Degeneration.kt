package com.example.demo.chat

/**
 * How much of the tail has to be one phrase on repeat before a reply is written off.
 * Real writing never fills sixty straight characters with the same short unit; a small
 * model that has fallen into a loop fills them in about a second.
 */
private const val LOOP_WINDOW = 60
private const val LOOP_UNIT_MAX = 20

/**
 * True when the tail of [this] is one short phrase repeated over and over — the failure
 * mode where a small model stops answering and starts chanting. Gemma 1B does it on open
 * ended questions; the length cap alone would still hand the reader 800 characters of it.
 */
internal fun CharSequence.isLoopingOnItself(): Boolean {
    if (length < LOOP_WINDOW) return false
    val tail = subSequence(length - LOOP_WINDOW, length)
    // No divisibility requirement: a window that ends mid-phrase is still periodic,
    // and demanding a whole number of rounds silently misses every unit that does not
    // happen to divide the window.
    for (unit in 1..LOOP_UNIT_MAX) {
        if (tail.isBuiltFrom(unit)) return true
    }
    return false
}

/** Whether [this] is its own first [unit] characters, repeated end to end. */
private fun CharSequence.isBuiltFrom(unit: Int): Boolean {
    for (index in unit until length) {
        if (this[index] != this[index - unit]) return false
    }
    return true
}

/**
 * Collapses a repeated tail down to two rounds. The reader needs to see that the model
 * started chanting; they do not need to read the chant.
 */
internal fun String.withoutRepeatingTail(): String {
    for (unit in 1..LOOP_UNIT_MAX) {
        var rounds = 0
        var end = length
        while (end - 2 * unit >= 0 && regionMatches(end - unit, this, end - 2 * unit, unit)) {
            end -= unit
            rounds++
        }
        if (rounds >= 2) return substring(0, (end + 2 * unit).coerceAtMost(length)).trimEnd()
    }
    return this
}
