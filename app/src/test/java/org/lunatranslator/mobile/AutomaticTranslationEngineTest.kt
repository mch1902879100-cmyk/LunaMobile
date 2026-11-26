package org.lunatranslator.mobile

import org.junit.Assert.*
import org.junit.Test

class AutomaticTranslationEngineTest {
    private class ChangingEngine : TranslatorEngine {
        var requests = 0
        var fail = false
        var empty = false
        override fun translate(text: String, source: String, target: String): String =
            translateStreaming(text, source, target) { }
        override fun translateStreaming(text: String, source: String, target: String, onPartial: (String) -> Unit): String {
            requests++
            if (fail) { onPartial("unfinished"); throw IllegalStateException("simulated endpoint error") }
            val output = if (empty) " " else "$requests:$target:$text"
            onPartial(output)
            return output
        }
    }

    @Test fun automaticReopenReplaysFinalOutputWithoutRequestOrHistoryChange() {
        val delegate = ChangingEngine()
        val lookups = mutableListOf<Boolean>()
        val engine = AutomaticTranslationEngine(delegate, onLookup = { lookups.add(it) })
        val first = engine.translateAutomatically("攻撃", "ja", "zh") { }
        val replay = mutableListOf<String>()
        assertEquals(first, engine.translateAutomatically("攻撃", "ja", "zh") { replay.add(it) })
        assertEquals(1, delegate.requests)
        assertEquals(listOf(first), replay)
        assertEquals(listOf(false, true), lookups)
    }

    @Test fun languagesAndCurrentTextCannotShareWrongResult() {
        val delegate = ChangingEngine()
        val engine = AutomaticTranslationEngine(delegate)
        val first = engine.translateAutomatically("A", "ja", "zh") { }
        engine.translateAutomatically("A", "en", "zh") { }
        engine.translateAutomatically("A", "ja", "en") { }
        engine.translateAutomatically("B", "ja", "zh") { }
        assertEquals(4, delegate.requests)
        assertEquals(first, engine.translateAutomatically("A", "ja", "zh") { })
        assertEquals(4, delegate.requests)
    }

    @Test fun manualRetranslationAlwaysRequestsAndDoesNotReplaceAutomaticResult() {
        val delegate = ChangingEngine()
        val engine = AutomaticTranslationEngine(delegate)
        val original = engine.translateAutomatically("A", "ja", "zh") { }
        assertNotEquals(original, engine.translateStreaming("A", "ja", "zh") { })
        assertNotEquals(original, engine.translate("A", "ja", "zh"))
        assertEquals(original, engine.translateAutomatically("A", "ja", "zh") { })
        assertEquals(3, delegate.requests)
    }

    @Test fun disabledCacheAndNewEngineSessionMustRequestAgain() {
        val delegate = ChangingEngine()
        val disabled = AutomaticTranslationEngine(delegate, enabled = false)
        disabled.translateAutomatically("A", "ja", "zh") { }
        disabled.translateAutomatically("A", "ja", "zh") { }
        assertEquals(2, delegate.requests)
        AutomaticTranslationEngine(delegate).translateAutomatically("A", "ja", "zh") { }
        AutomaticTranslationEngine(delegate).translateAutomatically("A", "ja", "zh") { }
        assertEquals(4, delegate.requests)
    }

    @Test fun failedOrEmptyPartialTranslationIsNeverReplayedAsSuccess() {
        val delegate = ChangingEngine()
        val engine = AutomaticTranslationEngine(delegate)
        delegate.fail = true
        assertThrows(IllegalStateException::class.java) { engine.translateAutomatically("A", "ja", "zh") { } }
        delegate.fail = false
        delegate.empty = true
        engine.translateAutomatically("A", "ja", "zh") { }
        delegate.empty = false
        val success = engine.translateAutomatically("A", "ja", "zh") { }
        assertEquals(success, engine.translateAutomatically("A", "ja", "zh") { })
        assertEquals(3, delegate.requests)
    }

    @Test fun entryLimitEvictsLeastRecentlyUsedValue() {
        val delegate = ChangingEngine()
        val engine = AutomaticTranslationEngine(delegate, maxEntries = 2)
        val a = engine.translateAutomatically("A", "ja", "zh") { }
        engine.translateAutomatically("B", "ja", "zh") { }
        assertEquals(a, engine.translateAutomatically("A", "ja", "zh") { })
        engine.translateAutomatically("C", "ja", "zh") { }
        assertEquals(a, engine.translateAutomatically("A", "ja", "zh") { })
        engine.translateAutomatically("B", "ja", "zh") { }
        assertEquals(4, delegate.requests)
    }

    @Test fun characterBudgetIncludesBothDirectionsAndDoesNotRetainOversizedValue() {
        val delegate = ChangingEngine()
        // A result costs 4 language + 1 source + 6 output characters = 11.
        val engine = AutomaticTranslationEngine(delegate, maxCharacters = 11)
        engine.translateAutomatically("A", "ja", "zh") { }
        engine.translateAutomatically("B", "ja", "zh") { }
        engine.translateAutomatically("A", "ja", "zh") { }
        assertEquals(3, delegate.requests)
        val small = AutomaticTranslationEngine(delegate, maxCharacters = 2)
        small.translateAutomatically("A", "ja", "zh") { }
        small.translateAutomatically("A", "ja", "zh") { }
        assertEquals(5, delegate.requests)
    }

    @Test fun normalizedEquivalentTextReusesResultWithoutChangingOriginalInput() {
        val delegate = ChangingEngine()
        val engine = AutomaticTranslationEngine(delegate, normalize = { TranslationPrompts.normalized(it, true) })
        val raw = "Ａ！？"
        val first = engine.translateAutomatically(raw, "ja", "zh") { }
        assertEquals(first, engine.translateAutomatically("A!?", "ja", "zh") { })
        assertEquals(1, delegate.requests)
        assertEquals("Ａ！？", raw)
    }
}
