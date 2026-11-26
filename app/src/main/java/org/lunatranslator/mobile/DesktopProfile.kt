package org.lunatranslator.mobile
import android.content.Context
import org.json.JSONObject

object DesktopProfile {
    fun import(context:Context,json:String):String {
        val root=JSONObject(json)
        val args=root.optJSONObject("chatgpt-3rd-party")?.optJSONObject("args") ?: root.optJSONObject("args") ?: root
        require(args.has("API接口地址") && args.has("SECRET_KEY")) {"请选择 Luna 桌面 userconfig/translatorsetting.json。"}
        val key=args.optString("SECRET_KEY").split('|').first().trim()
        require(key.isNotBlank()) {"桌面配置未填写密钥。"}
        val model=args.optString("model","deepseek-v4-flash")
        context.getSharedPreferences("engine",Context.MODE_PRIVATE).edit()
            .putString("provider","compatible").putString("url",args.getString("API接口地址"))
            .putString("key",key).putString("model",model)
            .putInt("context_turns",args.optInt("附带上下文个数",2))
            .putInt("max_tokens",args.optInt("max_tokens",1024))
            .putString("temperature",args.optDouble("Temperature",0.3).toString())
            .putBoolean("stream",args.optBoolean("流式输出",true))
            .putString("system_prompt",if(args.optBoolean("使用自定义promt"))args.optString("自定义promt") else "")
            .putString("user_prompt",if(args.optBoolean("use_user_user_prompt"))args.optString("user_user_prompt") else "")
            .apply()
        return model
    }
}
