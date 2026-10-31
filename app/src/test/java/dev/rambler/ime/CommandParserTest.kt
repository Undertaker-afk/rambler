package dev.rambler.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {
    @Test fun splitsMessageAndInstruction() {
        val p = CommandParser.parse("see you at six Rambler, add emojis and translate to German")
        assertEquals("see you at six", p.message)
        assertEquals("add emojis and translate to German", p.instruction)
    }

    @Test fun noWakeWordMeansNoCommand() {
        val p = CommandParser.parse("please translate this to German for me")
        assertEquals("please translate this to German for me", p.message)
        assertNull(p.instruction)
    }

    @Test fun wakeWordWithoutMessageIsIgnored() = assertNull(CommandParser.parse("Rambler add emojis").instruction)

    @Test fun trailingWakeWordIsIgnored() = assertNull(CommandParser.parse("thanks Rambler").instruction)
}
