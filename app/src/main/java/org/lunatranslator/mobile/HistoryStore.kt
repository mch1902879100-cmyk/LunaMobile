package org.lunatranslator.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(val source: String, val target: String, val input: String, val output: String)
class HistoryStore(context: Context) {
 private val prefs = context.getSharedPreferences("translations", Context.MODE_PRIVATE)
 fun load(): List<HistoryItem> = runCatching { JSONArray(prefs.getString("items", "[]")).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { HistoryItem(it.getString("s"),it.getString("t"),it.getString("i"),it.getString("o")) } } } }.getOrDefault(emptyList())
 fun add(item: HistoryItem) { val a=JSONArray(); (listOf(item)+load()).take(50).forEach { a.put(JSONObject().put("s",it.source).put("t",it.target).put("i",it.input).put("o",it.output)) }; prefs.edit().putString("items",a.toString()).apply() }
}
