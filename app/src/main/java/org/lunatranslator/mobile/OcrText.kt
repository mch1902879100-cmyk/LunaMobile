package org.lunatranslator.mobile

/** Retain menu rows and paragraphs; never flatten a whole menu into a sentence. */
object OcrText {
    /** Japanese-game quick mode must not translate the Chinese launcher into a giant caption. */
    fun forScreen(value: String, quick: Boolean, source: String, target: String): String {
        val text = normalize(value)
        if (!quick || target != "zh" || (source != "auto" && source != "ja")) return text
        val kana = Regex("[\u3041-\u3096\u30a1-\u30fa\uff66-\uff9d]")
        val lines=text.lines()
        val selected=linkedSetOf<Int>()
        lines.forEachIndexed { index, line ->
            val count=kana.findAll(line).count()
            val letters=line.count { it.isLetter() }.coerceAtLeast(1)
            val sentence=(count>=2 && count.toFloat()/letters>=.15f) ||
                (count>=1 && Regex("[\u3400-\u9fff]").containsMatchIn(line) && Regex("[？?！!]$").containsMatchIn(line))
            if (sentence) {
                // A short immediately preceding name belongs to the dialogue, not the whole screen's Chinese UI.
                if(index>0 && Regex("[\u3400-\u9fff]{1,12}").matches(lines[index-1])) selected.add(index-1)
                selected.add(index)
            }
        }
        return selected.sorted().joinToString("\n") { lines[it] }
    }
    fun normalize(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')
        .split('\n').map { it.replace(Regex("[\\p{Cc}\\t ]+"), " ").trim() }
        .filter { it.isNotBlank() }.joinToString("\n")
}
