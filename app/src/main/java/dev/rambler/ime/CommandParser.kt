package dev.rambler.ime

/**
 * Splits a transcript into the message and an optional spoken instruction.
 *
 * Commands are introduced by the wake word "Rambler": "see you at six rambler add emojis and
 * translate to German" -> message "see you at six", instruction "add emojis and translate to German".
 * Without the wake word nothing is ever treated as a command.
 */
object CommandParser {
    data class Parsed(val message: String, val instruction: String?)

    private val wake = Regex("""\brambler\b[\s,:.\-]*""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Parsed {
        val m = wake.find(raw) ?: return Parsed(raw.trim(), null)
        val message = raw.substring(0, m.range.first).trim().trimEnd(',', '-', ':')
        val instruction = raw.substring(m.range.last + 1).trim().trimEnd('.', ',')
        // No message before the wake word, or nothing after it: it was just a spoken word.
        if (message.isEmpty() || instruction.isEmpty()) return Parsed(raw.trim(), null)
        return Parsed(message, instruction)
    }
}
