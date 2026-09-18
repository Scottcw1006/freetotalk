package com.example.demo.chat

/**
 * Searching over conversations, kept deliberately framework-free so the matching, the
 * snippet cropping and the highlight positions are unit-testable on their own; the
 * composables that draw the result live in the ui package. This is the same split
 * [parseModelMarkdown] uses, for the same reason.
 *
 * The rule that keeps the split honest: everything about *what matches* is decided here.
 * If the drawing code ever needs to call `contains` or `lowercase`, then a second copy of
 * the matching rules has appeared where no test can see it.
 */

/** How much of a message to show around a hit, and how much of it to spend on lead-in. */
private const val SNIPPET_WINDOW = 60
private const val SNIPPET_LEAD = 20

/** One long thread should not push every other result off the screen. */
private const val MAX_HITS_PER_CONVERSATION = 3

private const val ELLIPSIS = "…"

/**
 * A stretch of [MessageHit.snippet] that matched the query.
 *
 * Indices are UTF-16 code units into the snippet, half-open, non-overlapping and
 * ascending. They are computed here rather than re-derived while drawing, because the
 * text the user sees is not the text that was matched against.
 */
data class Highlight(val start: Int, val endExclusive: Int)

data class MessageHit(
    val message: ChatMessage,
    /** Flattened to one line and cropped to a window; ellipses mark what was cut. */
    val snippet: String,
    val highlights: List<Highlight>,
)

data class ConversationHits(
    val conversation: Conversation,
    /** At most [MAX_HITS_PER_CONVERSATION]; never empty. */
    val hits: List<MessageHit>,
    /** Every matching message, including the ones [hits] left out. */
    val matchedMessageCount: Int,
)

data class SearchUiState(
    val query: String = "",
    val results: List<ConversationHits> = emptyList(),
    /**
     * The query [results] are for. It trails [query] while the field is still being typed
     * in, and what the screen says about the results has to be said about this one.
     */
    val searchedQuery: String = "",
) {
    /**
     * The same flattening the matching uses, rather than [String.isBlank], so that
     * "is this query empty" and "is there anything left to match with" can never drift
     * into two different answers. A test can only tell you once they have; sharing the
     * function means they cannot.
     */
    val isActive: Boolean get() = query.isSearchable

    /** False until the first search since the field was last empty has come back. */
    val hasOutcome: Boolean get() = isActive && searchedQuery.isSearchable
    val matchedConversationCount: Int get() = results.size
    val matchedMessageTotal: Int get() = results.sumOf { it.matchedMessageCount }
}

/** Whether there is anything left of a query to match with once it has been flattened. */
internal val String.isSearchable: Boolean get() = flattenToLine().isNotEmpty()

/**
 * Every message of every conversation is searchable, including the persona's opening
 * line. [ChatViewModel.send] drops those openers when it builds model context, but that
 * is a judgement about what makes the model answer well; this is a judgement about what
 * the user saw on screen. The two questions are allowed different answers, so the two
 * filters stay separate rather than being shared. A deleted message is not on screen,
 * so it is not searched — and the words on its placeholder are not message text.
 *
 * Conversations come back newest-updated first; hits inside one stay in the order the
 * messages were said.
 */
fun searchConversations(
    query: String,
    conversations: List<Conversation>,
): List<ConversationHits> {
    // The query goes through the very same flattening as the messages do: a
    // substring test between two strings held to different rules answers nothing, and
    // without this a query typed with a full-width or a doubled space can never match.
    val needle = query.flattenToLine().normalizeForSearch()
    // Not "no results" but "not searching" — the caller shows the plain history instead.
    if (needle.isEmpty()) return emptyList()

    return conversations
        .sortedByDescending { it.updatedAt }
        .mapNotNull { conversation ->
            val matched = conversation.messages.filterNot { it.deleted }.mapNotNull { it.hitOrNull(needle) }
            // A conversation with no hits must not survive as a bare header row.
            if (matched.isEmpty()) return@mapNotNull null
            ConversationHits(
                conversation = conversation,
                hits = matched.take(MAX_HITS_PER_CONVERSATION),
                matchedMessageCount = matched.size,
            )
        }
}

private fun ChatMessage.hitOrNull(needle: String): MessageHit? {
    // Flatten first: collapsing whitespace shortens the string, so doing it after the
    // match positions were found would shift every one of them. Model replies almost
    // always contain newlines, so this is not a corner case.
    val flat = text.flattenToLine()
    val matches = flat.normalizeForSearch().matchRanges(needle)
    if (matches.isEmpty()) return null

    val first = matches.first()
    val hitLength = first.last - first.first + 1
    // A hit that already fills the window has nowhere to put lead-in.
    val rawStart = if (hitLength >= SNIPPET_WINDOW) {
        first.first
    } else {
        (first.first - SNIPPET_LEAD).coerceAtLeast(0)
    }
    // §4.4: neither end may cut a character in two. The start moves forward out of a
    // character it lands in, unless that would carry it past the hit; the end moves back,
    // unless that would leave nothing of the hit on screen.
    val start = flat.characterBoundaryAtOrAfter(rawStart)
        .takeIf { it <= first.first } ?: flat.characterBoundaryAtOrBefore(rawStart)
    val rawEnd = (start + SNIPPET_WINDOW).coerceAtMost(flat.length)
    val end = flat.characterBoundaryAtOrBefore(rawEnd)
        .takeIf { it > first.first } ?: flat.characterBoundaryAtOrAfter(rawEnd)

    val prefix = if (start > 0) ELLIPSIS else ""
    val suffix = if (end < flat.length) ELLIPSIS else ""
    val snippet = prefix + flat.substring(start, end) + suffix
    val shift = prefix.length - start

    return MessageHit(
        message = this,
        snippet = snippet,
        // Everything visible in the window gets marked, not just the hit it was built
        // around; a partial hit at the edge is clamped to what is actually shown.
        highlights = matches.mapNotNull { range ->
            val from = range.first.coerceAtLeast(start)
            val to = (range.last + 1).coerceAtMost(end)
            if (from >= to) null else Highlight(from + shift, to + shift)
        },
    )
}

/** Non-overlapping, ascending — the order highlights are required to arrive in. */
private fun String.matchRanges(needle: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var from = 0
    while (from <= length - needle.length) {
        val at = indexOf(needle, from)
        if (at < 0) break
        ranges += at until (at + needle.length)
        from = at + needle.length
    }
    return ranges
}

/**
 * Every run of whitespace becomes one plain space so a snippet is one readable line, and
 * so the query and the message end up in the same shape — matching is a substring test,
 * and a test between two strings held to different rules answers nothing. This is the
 * only step allowed to change the length of the text; from here on, indices into the
 * result are the coordinate system everything else uses.
 */
internal fun String.flattenToLine(): String = buildString(length) {
    var gap = false
    for (character in this@flattenToLine) {
        if (character.isFlattenableSpace()) {
            // Leading whitespace opens no gap, and a trailing one is never spent.
            gap = isNotEmpty()
        } else {
            if (gap) append(' ')
            gap = false
            append(character)
        }
    }
}

/**
 * What the search field is allowed to hold while it is being typed in: never a leading
 * space. A space typed into an empty field does nothing, and pasted text loses whatever
 * padding came with it — neither can affect what matches, so removing them costs the user
 * nothing and stops the field from looking like it holds something when it does not.
 *
 * Trailing spaces are left alone here on purpose. The space in the middle of "北京 天氣"
 * IS a trailing space at the moment it is typed; taking it away as it arrives would make
 * a query with a space in it impossible to write.
 */
internal fun String.withoutLeadingSpace(): String = dropWhile { it.isFlattenableSpace() }

/**
 * And what it holds once the caret has gone elsewhere, when there is no longer a word
 * being typed for a trailing space to belong to.
 *
 * Both of these ask [isFlattenableSpace] rather than [String.trim], and that is not
 * fussiness. Kotlin's trim follows `Char.isWhitespace`, which is very nearly our rule —
 * it covers every separator we do — but it also strips U+001C–U+001F, the C0 delimiters,
 * which are not gaps in any text a reader ever sees. Being nearly right is what makes it
 * dangerous: reach for it and the field quietly starts eating characters the rest of the
 * search still counts as content.
 */
internal fun String.withoutTrailingSpace(): String = dropLastWhile { it.isFlattenableSpace() }

/**
 * Whitespace is every Unicode separator plus the ASCII control run — not the handful of
 * characters `\s` happens to cover. A list of four would have to answer why U+00A0 is not
 * on it, and there is no answer: to a line of text every one of these is the same thing,
 * a gap. Drawing the boundary anywhere else just schedules the next character to be missed.
 *
 * Zero-width formatting characters are deliberately NOT whitespace, and this is not an
 * oversight to tidy up later. They occupy no width, so folding one into a visible space
 * would insert a gap the writer never typed — and U+200D is what holds an emoji sequence
 * together, so collapsing it would tear 👨‍👩‍👧 into three separate people. That would be
 * rewriting the message, which is the same line [avoidSplittingPairAt] refuses to cross.
 */
private fun Char.isFlattenableSpace(): Boolean {
    if (this in '\u0009'..'\u000D') return true
    return when (Character.getType(this)) {
        Character.SPACE_SEPARATOR.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt() -> true
        else -> false
    }
}

/**
 * Case- and width-insensitive, and strictly character-for-character: the result must line
 * up index by index with its input, because highlights are found here and then applied to
 * the original text. That rules out [String.lowercase], which turns 'İ' into two
 * characters and would silently shift every later highlight.
 *
 * Width folding here covers the full-width ASCII block only. The full-width space
 * U+3000 is deliberately not part of it: whitespace of every kind is settled earlier, by
 * [flattenToLine], and handling it in both places would mean two answers to the same
 * question. This step maps one character to one character; that one does not survive as
 * a character at all.
 *
 * Nothing beyond case and width is folded — 台 does not find 臺. Anything more needs a
 * mapping table, and "what will this find?" stops being a question the user can answer.
 */
internal fun String.normalizeForSearch(): String = buildString(length) {
    for (character in this@normalizeForSearch) {
        // Full-width ASCII sits exactly 0xFEE0 above its plain counterpart; Chinese
        // input methods produce it constantly, so one word can be typed either way.
        val narrowed = if (character in '！'..'～') {
            character - 0xFEE0
        } else {
            character
        }
        append(narrowed.lowercaseChar())
    }
}

/**
 * A character is what the reader sees as one — an extended grapheme cluster, so 👍🏽,
 * 👨‍👩‍👧, 🇹🇼 and a letter under its accents each count once. Part of one on its own is
 * not something anyone can read. The drawer's title and preview cut with these too: the
 * two places must agree on what cutting a character means.
 *
 * java.text rather than android.icu keeps this plain JVM logic; on Android the same class
 * is backed by ICU.
 */
internal fun String.characterBoundaryAtOrBefore(index: Int): Int {
    if (index <= 0 || index >= length) return index.coerceIn(0, length)
    val breaks = java.text.BreakIterator.getCharacterInstance().also { it.setText(this) }
    return if (breaks.isBoundary(index)) index else breaks.preceding(index)
}

internal fun String.characterBoundaryAtOrAfter(index: Int): Int {
    if (index <= 0 || index >= length) return index.coerceIn(0, length)
    val breaks = java.text.BreakIterator.getCharacterInstance().also { it.setText(this) }
    return if (breaks.isBoundary(index)) index else breaks.following(index)
}
