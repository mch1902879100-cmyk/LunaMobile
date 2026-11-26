package org.lunatranslator.mobile

import java.text.Normalizer

/** Match Luna's default Unicode normalization and prompt expansion, retaining raw game text in the UI. */
object TranslationPrompts {
    private const val DEFAULT_SYSTEM = "You are a translator. Please help me translate the following {srclang} text into {tgtlang}. You should only tell me the translation result without any additional explanations."
    private fun language(code: String) = when (code) {
        "auto" -> ""
        "ja" -> "Japanese"
        "zh" -> "Simplified Chinese"
        "cht" -> "Traditional Chinese"
        "en" -> "English"
        else -> code
    }
    fun system(template: String, source: String, target: String) = template.ifBlank { DEFAULT_SYSTEM }
        .replace("{srclang}", language(source)).replace("{tgtlang}", language(target))
    fun normalized(text: String, enabled: Boolean) = if (enabled) Normalizer.normalize(text, Normalizer.Form.NFKC) else text
    fun user(template: String, text: String, normalizeUnicode: Boolean): String {
        val source = normalized(text, normalizeUnicode)
        var prompt = template.ifBlank { "{sentence}" }
        if (!prompt.contains("{sentence}")) prompt += "{sentence}"
        return prompt.replace("{sentence}", source)
    }
}
