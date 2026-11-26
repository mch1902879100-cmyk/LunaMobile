package org.lunatranslator.mobile

/** Session-only automatic results. Manual requests always reach the original engine. */
class AutomaticTranslationEngine(
    private val delegate: TranslatorEngine,
    private val enabled: Boolean = true,
    private val maxEntries: Int = 128,
    private val maxCharacters: Int = 262144,
    private val normalize: (String) -> String = { it },
    private val onLookup: ((Boolean) -> Unit)? = null,
) : TranslatorEngine {
    private data class Key(val source: String, val target: String, val text: String)
    private val results = LinkedHashMap<Key, String>(16, 0.75f, true)
    private var characters = 0

    init { require(maxEntries > 0 && maxCharacters > 0) }

    override fun translate(text: String, source: String, target: String) = delegate.translate(text, source, target)
    override fun translateStreaming(text: String, source: String, target: String, onPartial: (String) -> Unit) =
        delegate.translateStreaming(text, source, target, onPartial)

    @Synchronized override fun translateAutomatically(text: String, source: String, target: String, onPartial: (String) -> Unit): String {
        val key = Key(source, target, normalize(text))
        val previous = if (enabled) results[key] else null
        runCatching { onLookup?.invoke(previous != null) }
        if (previous != null) {
            // A hit must not append model history or make another network request.
            onPartial(previous)
            return previous
        }
        val result = delegate.translateStreaming(text, source, target, onPartial)
        val size = source.length + target.length + key.text.length + result.length
        if (enabled && result.isNotBlank() && size <= maxCharacters) {
            results[key] = result
            characters += size
            while (results.size > maxEntries || characters > maxCharacters) {
                val oldest = results.entries.iterator().next()
                characters -= oldest.key.source.length + oldest.key.target.length + oldest.key.text.length + oldest.value.length
                results.remove(oldest.key)
            }
        }
        return result
    }
}
