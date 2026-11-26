package org.lunatranslator.mobile

/** Pure entry-document patcher: preserve the game scripts and install before their first execution. */
object JoiWebEntry {
    const val TAG = "<script src=\"LunaJoiWeb.js\"></script>"
    fun patch(html:String):String {
        check(!html.contains("LunaJoiWeb.js",true)){"已有未登记的 Luna 网页接入，请先检查。"}
        val head=Regex("<head(?:\\s[^>]*)?>",RegexOption.IGNORE_CASE).find(html)
        check(head!=null){"游戏缺少标准 HTML head，已保留原文件。"}
        return html.substring(0,head.range.last+1)+"\n"+TAG+"\n"+html.substring(head.range.last+1)
    }
}
