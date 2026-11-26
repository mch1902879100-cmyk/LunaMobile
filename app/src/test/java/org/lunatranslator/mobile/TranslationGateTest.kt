package org.lunatranslator.mobile
import org.junit.Assert.*
import org.junit.Test
class TranslationGateTest {
    @Test fun waitsForStableText() {
        val g=TranslationGate()
        assertFalse(g.shouldTranslate("今日は",0))
        assertFalse(g.shouldTranslate("今日は晴れ",1000))
        assertTrue(g.shouldTranslate("今日は晴れ",2000))
    }
    @Test fun retriesFailedUnchangedText() {
        val g=TranslationGate();g.shouldTranslate("こんにちは",0)
        assertTrue(g.shouldTranslate("こんにちは",1000));g.failed(1000)
        assertFalse(g.shouldTranslate("こんにちは",2000))
        assertTrue(g.shouldTranslate("こんにちは",6000))
    }
    @Test fun skipsSuccessfulDuplicateButNotReorderedSentence() {
        val g=TranslationGate();g.shouldTranslate("彼は私を待つ",0);g.shouldTranslate("彼は私を待つ",1000);g.succeeded("彼は私を待つ")
        assertFalse(g.shouldTranslate("彼は私を待つ",2000))
        assertFalse(g.shouldTranslate("私は彼を待つ",3000))
        assertTrue(g.shouldTranslate("私は彼を待つ",4000))
    }
    @Test fun rejectsEmptyOrPunctuationFrames() {
        val g=TranslationGate();assertFalse(g.shouldTranslate("",0));assertFalse(g.shouldTranslate("…！",1000));assertFalse(g.shouldTranslate("…！",2000))
    }
}
