package org.lunatranslator.mobile

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class TranslationPromptsTest {
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
    @Test fun matchesObservedDesktopDialogueRequests() {
        // Fingerprints observed from the actual desktop Luna requests, independent of this implementation.
        assertEquals("21c9ee4d8e8e7ba625db5893d1d4bca850e0e1d6adbcd9564f62c46a10871518",
            sha(TranslationPrompts.user("", "尾田ノブナガ\n敵は？", true)))
        assertEquals("dfeb05d6a1e7a946ac7fc77ee54ca297b2978bb05720699b078dbd3e393f11c4",
            sha(TranslationPrompts.user("", "尾田兵士\n明地の旗です！！", true)))
        assertEquals("196ece126e1b54469714b6308f0db886c54cea84f6d2da80b9701cfd6585a068",
            sha(TranslationPrompts.system("", "ja", "zh")))
    }
    @Test fun autoLanguageMatchesDesktopEmptyLanguageName() {
        assertEquals("f65181454ff0aef0681d006313bfefefe5ebab58721e298c68272a643f8a1788",
            sha(TranslationPrompts.system("", "auto", "zh")))
    }
    @Test fun customPromptWithoutSentenceStillIncludesGameText() {
        assertEquals("翻译：こんにちは?", TranslationPrompts.user("翻译：", "こんにちは？", true))
        assertEquals("[A!]/A!", TranslationPrompts.user("[{sentence}]/{sentence}", "Ａ！", true))
    }
    @Test fun originalTextAndDisabledNormalizationRemainIntact() {
        val raw="ＡＢＣ　ｶﾀｶﾅ！！"
        assertEquals("ABC カタカナ!!", TranslationPrompts.user("",raw,true))
        assertEquals(raw,TranslationPrompts.user("",raw,false))
        assertEquals("ＡＢＣ　ｶﾀｶﾅ！！",raw)
    }
}
