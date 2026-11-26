package org.lunatranslator.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class TranslationConfig(val baseUrl:String,val apiKey:String,val model:String,
    val contextTurns:Int=2,val temperature:Double=0.3,val maxTokens:Int=1024,
    val systemPrompt:String="",val userPrompt:String="",val stream:Boolean=true,val normalizeUnicode:Boolean=true,
    val automaticCache:Boolean=true)
interface TranslatorEngine {
    fun translate(text:String,source:String,target:String):String
    fun translateStreaming(text:String,source:String,target:String,onPartial:(String)->Unit):String = translate(text,source,target).also(onPartial)
    fun translateAutomatically(text:String,source:String,target:String,onPartial:(String)->Unit):String = translateStreaming(text,source,target,onPartial)
}
private object EngineSession { var config:TranslationConfig?=null;var engine:TranslatorEngine?=null;var provider:String?=null }
@Synchronized fun resetTranslatorSession() { EngineSession.config=null;EngineSession.engine=null;EngineSession.provider=null }
@Synchronized fun translatorFromPreferences(context:Context):TranslatorEngine {
    val p=context.getSharedPreferences("engine",Context.MODE_PRIVATE)
    val provider=p.getString("provider","compatible").orEmpty()
    val c=TranslationConfig(p.getString("url","").orEmpty(),p.getString("key","").orEmpty(),
        p.getString("model","deepseek-v4-flash").orEmpty(),p.getInt("context_turns",2),
        p.getString("temperature","0.3")?.toDoubleOrNull()?:0.3,p.getInt("max_tokens",1024),
        p.getString("system_prompt","").orEmpty(),p.getString("user_prompt","").orEmpty(),p.getBoolean("stream",true),p.getBoolean("normalize_nfkc",true),
        p.getBoolean("automatic_cache",true))
    if(EngineSession.config!=c || EngineSession.provider!=provider) {
        EngineSession.config=c
        EngineSession.provider=provider
        val app = context.applicationContext
        val debug=app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val audit: ((JSONObject)->Unit)? = if(debug) {
            { body ->
                val prefs=app.getSharedPreferences("request_audit",Context.MODE_PRIVATE)
                synchronized(prefs) { prefs.edit().putString("last",body.toString()).putLong("requests",prefs.getLong("requests",0)+1).apply() }
                Unit
            }
        } else null
        val cacheAudit: ((Boolean)->Unit)? = if(debug) {
            { hit ->
                val prefs=app.getSharedPreferences("cache_audit",Context.MODE_PRIVATE)
                val name=if(hit) "hits" else "misses"
                synchronized(prefs) { prefs.edit().putLong(name,prefs.getLong(name,0)+1).putBoolean("last_hit",hit).apply() }
                Unit
            }
        } else null
        val original=if(provider=="google") GoogleFallbackEngine() else OpenAiCompatibleEngine(c,audit)
        EngineSession.engine=AutomaticTranslationEngine(original,enabled=c.automaticCache,
            normalize={ TranslationPrompts.normalized(it,c.normalizeUnicode) },onLookup=cacheAudit)
    }
    return EngineSession.engine!!
}
class OpenAiCompatibleEngine(private val config:TranslationConfig,private val audit:((JSONObject)->Unit)?=null):TranslatorEngine {
    private val history=ArrayDeque<Pair<String,String>>()
    override fun translate(text:String,source:String,target:String)=translateStreaming(text,source,target){ }
    @Synchronized override fun translateStreaming(text:String,source:String,target:String,onPartial:(String)->Unit):String {
        require(config.baseUrl.isNotBlank() && config.apiKey.isNotBlank()) {"请导入桌面引擎配置，或在设置中填写接口、密钥和模型。"}
        val base=config.baseUrl.trim().trimEnd('/')
        val endpoint=when {base.endsWith("/chat/completions")->base;base.endsWith("/v1")->"$base/chat/completions";else->"$base/v1/chat/completions"}
        val system=TranslationPrompts.system(config.systemPrompt,source,target)
        val query=TranslationPrompts.user(config.userPrompt,text,config.normalizeUnicode)
        val messages=JSONArray().put(JSONObject().put("role","system").put("content",system))
        history.takeLast(config.contextTurns.coerceIn(0,10)).forEach {(input,output)->
            messages.put(JSONObject().put("role","user").put("content",input))
            messages.put(JSONObject().put("role","assistant").put("content",output))
        }
        messages.put(JSONObject().put("role","user").put("content",query))
        val body=JSONObject().put("model",config.model).put("messages",messages).put("stream",config.stream)
            .put("temperature",config.temperature).put("max_tokens",config.maxTokens)
        if (config.model.contains("deepseek", ignoreCase = true)) body.put("thinking", JSONObject().put("type", "disabled"))
        audit?.let { observer -> runCatching { observer(RequestFingerprint.of(body)) } }
        val connection=(URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod="POST";connectTimeout=15000;readTimeout=45000;doOutput=true
            setRequestProperty("Content-Type","application/json");setRequestProperty("Authorization","Bearer ${config.apiKey}")
        }
        try {
            connection.outputStream.use {it.write(body.toString().toByteArray(Charsets.UTF_8))}
            check(connection.responseCode in 200..299) {"翻译接口 HTTP ${connection.responseCode}；请检查桌面同款接口的网络与额度。"}
            val result=StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use {reader->
                if(config.stream && connection.contentType.orEmpty().contains("text/event-stream")) {
                    reader.forEachLine {line->
                        if(line.startsWith("data:")) {
                            val data=line.removePrefix("data:").trim()
                            if(data.isNotBlank() && data!="[DONE]") {
                                val delta=JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                                val part=delta?.optString("content","").orEmpty()
                                if(part.isNotEmpty() && part!="null") {result.append(part);onPartial(result.toString())}
                            }
                        }
                    }
                } else {
                    result.append(JSONObject(reader.readText()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
                    onPartial(result.toString())
                }
            }
            val output=result.toString().trim()
            check(output.isNotBlank()) {"接口没有返回译文。"}
            history.addLast(query to output);while(history.size>config.contextTurns.coerceIn(0,10)) history.removeFirst()
            return output
        } finally {connection.disconnect()}
    }
}
/** Explicit optional provider; never silently replace the user's desktop engine. */
class GoogleFallbackEngine:TranslatorEngine {
    override fun translate(text:String,source:String,target:String):String {
        val url="https://translate.googleapis.com/translate_a/single?client=gtx&sl=$source&tl=$target&dt=t&q="+URLEncoder.encode(text,"UTF-8")
        val c=(URL(url).openConnection() as HttpURLConnection).apply {connectTimeout=15000;readTimeout=30000}
        try {
            check(c.responseCode in 200..299){"Google HTTP ${c.responseCode}"}
            val chunks=JSONArray(c.inputStream.bufferedReader().use{it.readText()}).getJSONArray(0)
            return buildString {for(i in 0 until chunks.length()) append(chunks.optJSONArray(i)?.optString(0,"").orEmpty())}
        } finally {c.disconnect()}
    }
}
