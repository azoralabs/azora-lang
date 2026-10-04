/* Copyright 2026 AzoraLabs. Licensed under the Apache License, Version 2.0. */
package org.azora.lang.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SuggestionsTest {
    private val names = listOf("Experimental", "Stable", "Deprecated", "Serializable")

    @Test fun swappedLettersFindTheName() = assertEquals("Experimental", closestName("Experiemntal", names))

    @Test fun aChangeOfCaseIsCloserThanAnyOtherEdit() = assertEquals("Stable", closestName("stable", names))

    @Test fun aWordTooFarFromEveryNameFindsNothing() = assertNull(closestName("Frobnicate", names))

    @Test fun theNameItselfIsNotASuggestion() = assertNull(closestName("Stable", listOf("Stable")))

    @Test fun aShortNameAllowsOneEdit() {
        assertEquals("abs", closestName("abd", listOf("abs", "max")))
        assertNull(closestName("xyz", listOf("abs", "max")))
    }

    @Test fun theSentenceIsEmptyWithoutASuggestion() {
        assertEquals(" - did you mean '@Stable'?", didYouMean("Stabel", names) { "@$it" })
        assertEquals("", didYouMean("Frobnicate", names))
    }
}
