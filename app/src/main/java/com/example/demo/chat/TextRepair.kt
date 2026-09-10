package com.example.demo.chat

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Cleans up what comes back from the model before anyone reads it.
 *
 * Applied both when a reply is generated and when an old thread is read back, so
 * conversations saved before a repair landed are fixed on screen too.
 */
fun String.repairModelText(): String =
    decodeByteLevelTokens()
        .unescape()
        .dropHardBreakMarkers()
        // Stop tokens occasionally leak through into the decoded text.
        .replace("<|im_end|>", "")
        .replace("<|endoftext|>", "")
        .trim()

/**
 * Drops Markdown's trailing-backslash hard break. The models write Markdown, and a
 * backslash at the end of a line means "break here" — which [unescape] has already
 * turned into a real newline, leaving the marker itself as visible litter.
 */
private fun String.dropHardBreakMarkers(): String =
    if (contains('\\')) replace(Regex("""\\+(?=\n)"""), "") else this

/**
 * MediaPipe hands back line breaks as the two characters '\' and 'n' instead of U+000A
 * — measured across every reply, in both Chinese and English, a real newline never once
 * came through. Undone on the accumulated text rather than per streamed fragment, so it
 * still works if a fragment boundary ever falls between the backslash and the n.
 */
private fun String.unescape(): String {
    if (!contains('\\')) return this
    val out = StringBuilder(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        val next = if (index + 1 < length) this[index + 1] else null
        val decoded = when {
            char != '\\' -> null
            next == 'n' -> '\n'
            next == 't' -> '\t'
            next == '\\' -> '\\'
            else -> null
        }
        if (decoded == null) {
            out.append(char)
            index++
        } else {
            out.append(decoded)
            index += 2
        }
    }
    return out.toString()
}

/**
 * Undoes byte-level BPE surface forms that reach the app undecoded.
 *
 * Qwen and SmolLM tokenise bytes, not characters, and represent each byte as a stand-in
 * character — space becomes 'Ġ', byte 0x8C becomes 'Į'. MediaPipe usually converts those
 * back, but for some tokens it does not, and a Chinese character arrives as the three
 * stand-ins for its UTF-8 bytes: "ĠåĮĹ京" instead of " 北京". Gemma is unaffected; it
 * uses SentencePiece, which has no byte alphabet.
 *
 * Only a run that decodes cleanly as UTF-8 *and* yields a genuinely multi-byte character
 * is rewritten. Latin text survives the same round trip by coincidence — "café" is a
 * valid run of stand-ins — so anything that could plausibly be text the model meant to
 * write is left exactly as it wrote it.
 */
private fun String.decodeByteLevelTokens(): String {
    if (none { it.code >= 0x80 && byteDecoder.containsKey(it) }) return this

    val out = StringBuilder(length)
    var index = 0
    while (index < length) {
        if (!byteDecoder.containsKey(this[index])) {
            out.append(this[index])
            index++
            continue
        }
        var end = index
        while (end < length && byteDecoder.containsKey(this[end])) end++
        val run = substring(index, end)
        out.append(run.asDecodedBytes() ?: run)
        index = end
    }
    return out.toString()
}

private fun String.asDecodedBytes(): String? {
    // An all-ASCII run is ordinary text that merely happens to be representable.
    if (none { it.code >= 0x80 }) return null

    val bytes = ByteArray(length) { byteDecoder.getValue(this[it]).toByte() }
    val decoded = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        return null
    }
    // Accented Latin round-trips to itself; only a three-byte sequence proves this run
    // really was an encoded character rather than something the model typed.
    return decoded.takeIf { text -> text.any { it.code >= 0x800 } }
}

/**
 * The inverse of GPT-2's bytes_to_unicode: printable bytes stand for themselves, and the
 * rest are pushed up into U+0100 onwards in byte order. Verified against live output —
 * 0x20 arrives as 'Ġ', 0x8C as 'Į', 0x97 as 'Ĺ'.
 */
private val byteDecoder: Map<Char, Int> = buildMap {
    val printable = ((0x21..0x7E) + (0xA1..0xAC) + (0xAE..0xFF)).toSet()
    printable.forEach { put(it.toChar(), it) }
    var spare = 0
    (0x00..0xFF).forEach { byte ->
        if (byte !in printable) {
            put((0x100 + spare).toChar(), byte)
            spare++
        }
    }
}
