package org.lunatranslator.mobile

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** A local game player with direct engine text, without OCR, file association or game-file edits. */
class WebGameActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var web: WebView
    private lateinit var subtitle: TextView
    private lateinit var folder: GameFolder
    private lateinit var game: LibraryGame
    private val main = Handler(Looper.getMainLooper())
    private val overlay by lazy { getSharedPreferences("overlay", MODE_PRIVATE) }
    private val capture by lazy { getSharedPreferences("capture", MODE_PRIVATE) }
    private val appearance by lazy { getSharedPreferences("appearance", MODE_PRIVATE) }
    private var host = ""
    private var showSubtitle = true
    private var engineSubtitleVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            game = LibraryGame.from(JSONObject(intent.getStringExtra("game") ?: error("请选择游戏")))
            folder = GameFolder(this, game.location)
            require(folder.resolve(folder.webRoot(game), listOf("index.html")) != null) {
                "游戏入口不存在，请返回首页重新选择游戏文件夹"
            }
            host = "${game.id}.appassets.androidplatform.net"
            // A separate origin gives each game separate localStorage/IndexedDB saves.
            stopService(Intent(this, ScreenOcrService::class.java))
            stopService(Intent(this, FloatingTranslationService::class.java))
            ContextCompat.startForegroundService(this, Intent(this, GameTextService::class.java).setAction(GameTextService.BEGIN_HOST))
            buildUi()
            overlay.registerOnSharedPreferenceChangeListener(this)
            web.loadUrl("https://$host/index.html")
        } catch (error: Exception) {
            setContentView(TextView(this).apply { text = "无法打开游戏：${error.message}"; setPadding(30, 30, 30, 30) })
        }
    }

    private fun buildUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            addJavascriptInterface(TextBridge(), "LunaText")
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    val meaningful = title?.trim().orEmpty()
                    if (meaningful.length in 2..180 && meaningful != game.title &&
                        !Regex("loading|tyrano.?script|ティラノスクリプト",RegexOption.IGNORE_CASE).containsMatchIn(meaningful)) {
                        game = game.copy(title = meaningful)
                        LibraryGame.remember(this@WebGameActivity, game)
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.host != host
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    if (request.url.scheme != "https" || request.url.host != host) return failed(403)
                    return runCatching {
                        val root = folder.webRoot(game)
                        val parts = request.url.pathSegments.ifEmpty { listOf("index.html") }
                        val node = folder.resolve(root, parts) ?: return failed(404)
                        if (node.directory) return failed(404)
                        val mime = when (node.name.substringAfterLast('.').lowercase()) {
                            "js" -> "application/javascript"
                            "wasm" -> "application/wasm"
                            "json" -> "application/json"
                            "woff2" -> "font/woff2"
                            "rpgmvp", "rpgmvo", "rpgmvm", "bin" -> "application/octet-stream"
                            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(node.name.substringAfterLast('.').lowercase()) ?: "application/octet-stream"
                        }
                        val indexDocument = parts.size == 1 && node.name.equals("index.html", true)
                        val rangeHeader = request.requestHeaders.entries.firstOrNull { it.key.equals("Range",true) }?.value
                        if (!indexDocument && rangeHeader != null && node.size >= 0) {
                            val range = runCatching { ByteRange.parse(rangeHeader,node.size) }.getOrElse {
                                return WebResourceResponse("text/plain","UTF-8",416,"Range Not Satisfiable",
                                    mapOf("Content-Range" to "bytes */${node.size}"),ByteArrayInputStream(ByteArray(0)))
                            }
                            return WebResourceResponse(mime,"UTF-8",206,"Partial Content",mapOf(
                                "Content-Range" to "bytes ${range.start}-${range.start+range.length-1}/${node.size}",
                                "Accept-Ranges" to "bytes"),
                                // WebView skips the requested start itself. Expose a prefix
                                // ending at the requested end: no double skip or read past it.
                                folder.openRange(node,0,range.start + range.length))
                        }
                        val stream = if (indexDocument) {
                            val original = folder.open(node).bufferedReader(Charsets.UTF_8).use { it.readText() }
                            val bridge = assets.open("luna_mv_mz.js").bufferedReader().use { it.readText() }
                            val injected = "<script>window.LunaGameTextTransport=function(e){LunaText.send(JSON.stringify(e));};" +
                                "window.LunaGameFileExists=function(p){return LunaText.exists(String(p));};" +
                                "window.addEventListener('error',function(e){LunaText.fault(String(e.message));});</script><script>" + bridge + "</script>"
                            val match = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(original)
                            val html = if (match == null) injected + original else original.substring(0, match.range.last + 1) +
                                injected + original.substring(match.range.last + 1)
                            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                        } else folder.open(node)
                        WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-cache"), stream)
                    }.getOrElse { failed(404) }
                }
            }
        }
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        frame.addView(web, FrameLayout.LayoutParams(-1, -1))
        if (game.engine == "Tyrano") {
            val soundHint = TextView(this).apply {
                text = "轻触画面，启用声音并开始游戏"
                textSize = 16f; setTextColor(Color.WHITE); setBackgroundColor(0xc0202020.toInt())
                setPadding(24, 16, 24, 16); gravity = Gravity.CENTER
                isClickable = false; isFocusable = false
            }
            frame.addView(soundHint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            web.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) soundHint.visibility = View.GONE
                false
            }
        }
        subtitle = object : TextView(this) {
            override fun onDraw(canvas: Canvas) { repeat(if (appearance.getBoolean("desktop_shadow", true)) 10 else 1) { super.onDraw(canvas) } }
        }.apply {
            textSize = appearance.getFloat("font_size",18f)
            setTypeface(Typeface.DEFAULT, if (appearance.getBoolean("bold",true)) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(appearance.getInt("text_color",0xffeeeeee.toInt()))
            setShadowLayer(if (appearance.getBoolean("desktop_shadow",true)) textSize*.2f else 0f, 0f, 0f, appearance.getInt("shadow_color", Color.BLUE))
            gravity = Gravity.CENTER; setPadding(10, 3, 10, 3)
            isClickable = false; isFocusable = false; maxLines = 9
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(subtitle, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = (resources.displayMetrics.density * 115).toInt()
            leftMargin = 70; rightMargin = 70
        })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xc0202020.toInt()) }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; textSize = 12f; setOnClickListener { action() } }
        bar.addView(button("返回") { finish() })
        bar.addView(button("取消 / 菜单") { key(27) })
        bar.addView(button("确定") { key(13) })
        bar.addView(button("重新翻译") {
            if (engineSubtitleVisible) startService(Intent(this, GameTextService::class.java).setAction(GameTextService.RETRANSLATE))
            else android.widget.Toast.makeText(this, "当前没有显示可重译的文字", android.widget.Toast.LENGTH_SHORT).show()
        })
        bar.addView(button("暂停翻译") { capture.edit().putBoolean("paused", !capture.getBoolean("paused", false)).apply(); renderSubtitle() })
        bar.addView(button("字幕开关") { showSubtitle = !showSubtitle; renderSubtitle() })
        frame.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        frame.addView(button("⋮") {
            bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            main.postDelayed({ bar.visibility = View.GONE }, 5000)
        }, FrameLayout.LayoutParams(60, 60, Gravity.TOP or Gravity.END))
        main.postDelayed({ bar.visibility = View.GONE }, 5000)
        setContentView(frame)
        web.requestFocus()
        renderSubtitle()
    }

    private fun failed(code: Int) = WebResourceResponse("text/plain", "UTF-8", code,
        if (code == 403) "Forbidden" else "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun key(code: Int) {
        web.evaluateJavascript("document.dispatchEvent(new KeyboardEvent('keydown',{keyCode:$code,which:$code,bubbles:true}));", null)
        main.postDelayed({ if (!isFinishing) web.evaluateJavascript("document.dispatchEvent(new KeyboardEvent('keyup',{keyCode:$code,which:$code,bubbles:true}));", null) }, 140)
    }

    private inner class TextBridge {
        @JavascriptInterface fun exists(path: String): Boolean = runCatching {
            folder.resolve(folder.webRoot(game), path.trim('/').split('/')) != null
        }.getOrDefault(false)
        @JavascriptInterface fun send(json: String) {
            if (json.length > 30000) return
            runCatching {
                val e = JSONObject(json)
                val kind = e.getString("kind")
                if (kind == "visibility") {
                    val visible = e.getBoolean("visible")
                    main.post { if (!isFinishing) { engineSubtitleVisible = visible; renderSubtitle() } }
                    return
                }
                if (kind !in setOf("dialogue", "choices", "battle_message", "battle_log")) return
                val text = e.getString("text").take(8192)
                if (text.isBlank()) return
                val id = e.getString("session").take(80) + "/" + e.getLong("sequence")
                val bounds = e.optJSONObject("bounds")
                main.post {
                    if (!isFinishing) {
                        if (bounds != null) positionSubtitle(bounds)
                        startService(Intent(this@WebGameActivity, GameTextService::class.java)
                            .setAction(GameTextService.HOST_FRAME).putExtra("id", id).putExtra("kind", kind).putExtra("text", text))
                    }
                }
            }
        }
        @JavascriptInterface fun fault(message: String) {
            overlay.edit().putString("status", "游戏运行错误：${message.take(180)}").apply()
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) { main.post { renderSubtitle() } }
    private fun positionSubtitle(bounds: JSONObject) {
        val top = bounds.optDouble("top", 0.7)
        val bottom = bounds.optDouble("bottom", 1.0)
        if (!top.isFinite() || !bottom.isFinite() || top !in 0.0..1.0 || bottom !in top..1.1) return
        val params = subtitle.layoutParams as FrameLayout.LayoutParams
        if (top > 0.3) {
            params.gravity = Gravity.BOTTOM
            params.bottomMargin = ((1.0 - top) * web.height + 12 * resources.displayMetrics.density).toInt()
            params.topMargin = 0
        } else {
            params.gravity = Gravity.TOP
            params.topMargin = (bottom * web.height + 12 * resources.displayMetrics.density).toInt()
            params.bottomMargin = 0
        }
        subtitle.layoutParams = params
    }
    private fun renderSubtitle() {
        if (!::subtitle.isInitialized) return
        subtitle.visibility = if (showSubtitle && engineSubtitleVisible) View.VISIBLE else View.GONE
        val translated = overlay.getString("out", "").orEmpty()
        val content = translated.ifBlank { overlay.getString("status", "等待对白…").orEmpty() }
        subtitle.text = if (capture.getBoolean("paused", false)) "$content\n（自动翻译已暂停）" else content
    }
    override fun onPause() {
        if (::web.isInitialized) {
            web.evaluateJavascript("if(window.Input)Input.clear();if(window.TouchInput)TouchInput.clear();", null)
            web.onPause()
        }
        super.onPause()
    }
    override fun onResume() { super.onResume(); if (::web.isInitialized) web.onResume() }
    override fun onDestroy() {
        overlay.unregisterOnSharedPreferenceChangeListener(this)
        main.removeCallbacksAndMessages(null)
        if (::web.isInitialized) { web.removeJavascriptInterface("LunaText"); web.destroy() }
        stopService(Intent(this, GameTextService::class.java))
        super.onDestroy()
    }
}
