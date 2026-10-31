package org.futo.inputmethod.rambler

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleBasedCleanerTest {
    @Test fun removesFillers() =
        assertEquals("I think we should go.", RuleBasedCleaner.clean("um i think uh we should go"))

    @Test fun collapsesStutter() =
        assertEquals("I want pizza.", RuleBasedCleaner.clean("I I want want pizza"))

    @Test fun dropsRestartedClause() =
        assertEquals("See you at six.", RuleBasedCleaner.clean("see you at five scratch that see you at six"))

    @Test fun keepsEarlierSentence() =
        assertEquals("Hi there. See you at six.", RuleBasedCleaner.clean("hi there. see you at five no wait see you at six"))

    @Test fun emptyStaysEmpty() = assertEquals("", RuleBasedCleaner.clean("  um  "))

    @Test fun keepsExistingPunctuation() =
        assertEquals("Are you coming?", RuleBasedCleaner.clean("uh are you coming?"))
}
