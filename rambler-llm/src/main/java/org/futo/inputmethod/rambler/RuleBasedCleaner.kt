package org.futo.inputmethod.rambler

/**
 * Deterministic cleanup used (a) as a fallback when the LLM isn't downloaded/loaded yet,
 * and (b) as a safety net when the LLM returns something unusable.
 *
 * It only handles the easy cases: filler sounds, stuttered words, explicit "scratch that"
 * style restarts, and basic punctuation/capitalisation.
 */
object RuleBasedCleaner {

    private val fillers = Regex(
        """\b(?:u+h+m*|u+m+|e+r+m*|h+m+|m+h?m+|a+h+m+|uh-huh)\b[,.]?""",
        RegexOption.IGNORE_CASE
    )

    // Phrases after which everything the speaker said since the last sentence boundary is dropped.
    private val restartMarkers = Regex(
        """\b(?:scratch that|strike that|forget that|never ?mind that|no wait|wait no|actually no|let me start over|start over|let me rephrase|sorry let me say that again)\b[,.]?\s*""",
        RegexOption.IGNORE_CASE
    )

    private val stutter = Regex("""\b(\w+)(?:\s+\1\b)+""", RegexOption.IGNORE_CASE)

    fun clean(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        text = applyRestarts(text)
        text = fillers.replace(text, " ")
        text = stutter.replace(text) { it.groupValues[1] }
        text = text.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s+([,.!?;:])"""), "$1")
            .replace(Regex("""^[,.;:\s]+"""), "")
            .replace(Regex("""([,;])\s*(?=[.!?])"""), "")
            .trim()
        if (text.isEmpty()) return ""

        text = capitalizeSentences(text)
        if (text.last().isLetterOrDigit()) text += "."
        return text
    }

    /** Drops the clause that precedes a restart marker (back to the previous sentence end). */
    private fun applyRestarts(input: String): String {
        var text = input
        while (true) {
            val m = restartMarkers.find(text) ?: break
            val before = text.substring(0, m.range.first)
            val after = text.substring(m.range.last + 1)
            val boundary = before.indexOfLast { it == '.' || it == '!' || it == '?' }
            val kept = if (boundary >= 0) before.substring(0, boundary + 1) else ""
            text = (kept + " " + after).trim()
        }
        return text
    }

    private fun capitalizeSentences(text: String): String {
        val sb = StringBuilder(text.length)
        var capNext = true
        for (c in text) {
            if (capNext && c.isLetter()) {
                sb.append(c.uppercaseChar()); capNext = false
            } else {
                sb.append(c)
                if (c == '.' || c == '!' || c == '?') capNext = true
                else if (!c.isWhitespace()) capNext = false
            }
        }
        // Standalone "i" -> "I"
        return sb.toString().replace(Regex("""\bi\b"""), "I")
    }
}
