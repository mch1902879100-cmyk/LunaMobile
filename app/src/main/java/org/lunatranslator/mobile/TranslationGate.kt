package org.lunatranslator.mobile

/** Stable text only; failed requests remain retryable. No fuzzy suppression of new lines. */
class TranslationGate {
    private var candidate = ""
    private var observations = 0
    private var translated = ""
    private var retryAt = 0L
    fun shouldTranslate(text: String, now: Long): Boolean {
        if (text.length < 2 || text.none { it.isLetterOrDigit() }) return false
        if (candidate != text) { candidate = text; observations = 1; retryAt = 0; return false }
        observations++
        return observations >= 2 && text != translated && now >= retryAt
    }
    fun succeeded(text: String) { translated = text; retryAt = 0 }
    fun failed(now: Long) { retryAt = now + 5000 }
    fun clearScreen() { candidate = ""; observations = 0; translated = ""; retryAt = 0 }
}
