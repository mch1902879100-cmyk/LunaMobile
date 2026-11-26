package org.lunatranslator.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GameTextService : Service() {
    companion object {
        const val BEGIN_HOST = "org.lunatranslator.mobile.BEGIN_HOST"
        const val HOST_FRAME = "org.lunatranslator.mobile.HOST_FRAME"
        const val RETRANSLATE = "org.lunatranslator.mobile.RETRANSLATE"
        const val JOIPLAY = "org.lunatranslator.mobile.JOIPLAY_DIRECT"
        @Volatile var isRunning = false
            private set
    }
    private val reader = Executors.newSingleThreadScheduledExecutor()
    private val translator = Executors.newSingleThreadExecutor()
    private val capture by lazy { getSharedPreferences("capture", MODE_PRIVATE) }
    private val overlay by lazy { getSharedPreferences("overlay", MODE_PRIVATE) }
    private val seen = LinkedHashSet<String>()
    @Volatile private var active = false
    private val requests = GameTranslationRequests()
    private val sessionLock = Any()
    private var initialized = false
    @Volatile private var readingUri: Uri? = null
    private var polledUri: Uri? = null
    @Volatile private var joiMode = false
    private var connectedKey: String? = null
    private var lastHeartbeat=0L
    private var textWasVisible=false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == HOST_FRAME) {
            if (active && !joiMode) requests.offer(GameTextFrame(intent.getStringExtra("id").orEmpty(),
                intent.getStringExtra("kind").orEmpty(), intent.getStringExtra("text").orEmpty()))
            return START_NOT_STICKY
        }
        if (intent?.action == RETRANSLATE) {
            if (!active) stopSelf(startId)
            else if(joiMode && !capture.getBoolean("game_text_visible",false)) overlay.edit().putString("status","当前没有显示中的游戏文字").apply()
            else if (!requests.requestRefresh()) overlay.edit().putString("status", "等待游戏文字后再重译").apply()
            return START_NOT_STICKY
        }
        joiMode = intent?.action == JOIPLAY
        if (intent?.action == BEGIN_HOST || joiMode) {
            readingUri = null
        } else readingUri = intent?.data ?: return START_NOT_STICKY
        capture.edit().putString("translation_mode",if(joiMode)"joiplay" else if(intent?.action==BEGIN_HOST)"host" else "legacy")
            .putBoolean("joi_connected",false).putBoolean("game_text_visible",false).remove("game_title").apply()
        synchronized(sessionLock) {
            requests.clear()
            resetTranslatorSession()
        }
        overlay.edit().remove("in").remove("out").remove("request_mode").putString("status", "等待游戏文字…").apply()
        if (joiMode) connectedKey=null
        if (active) { if(joiMode) announceJoiReady(); return START_NOT_STICKY }
        val channel = "luna_game_text"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Luna 游戏直接取词", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(12, NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Luna 游戏直接取词")
            .setContentText("正在等待对白和战斗文字")
            .setOngoing(true).build())
        overlay.edit().remove("in").remove("out").putString("status", "等待游戏文字…").apply()
        capture.edit().putBoolean("direct_text", true).apply()
        active = true
        isRunning = true
        if(joiMode) announceJoiReady()
        if(joiMode) capture.edit().putBoolean("joi_connected",false).remove("game_title").apply()
        reader.scheduleWithFixedDelay({ if(joiMode) pollJoiPlay() else readingUri?.let { poll(it) } }, 0, 300, TimeUnit.MILLISECONDS)
        translator.execute {
            while (active) {
                var work: GameTranslationWork? = null
                try {
                    val paused = capture.getBoolean("paused", false)
                    work = requests.take(paused, SystemClock.elapsedRealtime())
                    if (work == null) Thread.sleep(if (paused) 180 else 60) else translate(work)
                } catch (_: InterruptedException) {
                    break
                } catch (error: Exception) {
                    if (active) {
                        val current = work?.let(requests::isCurrent) == true
                        val retry = work?.let { requests.retry(it, SystemClock.elapsedRealtime() + 5000) } == true
                        val http = Regex("HTTP (\\d{3})").find(error.message.orEmpty())?.groupValues?.get(1)
                        val detail = http?.let { "HTTP $it" } ?: error.javaClass.simpleName
                        val enginePrefs = getSharedPreferences("engine", MODE_PRIVATE)
                        val missingConfig = enginePrefs.getString("provider", "compatible") != "google" &&
                            (enginePrefs.getString("url", "").isNullOrBlank() || enginePrefs.getString("key", "").isNullOrBlank())
                        if (current) work?.let { failed -> requests.publishIfCurrent(failed) {
                            if (active) overlay.edit().putString("out", "").putString("status", when {
                                missingConfig -> "请返回首页导入桌面翻译配置，或打开翻译引擎设置"
                                retry -> "翻译失败（$detail），5秒后重试"
                                else -> "重译失败（$detail），可再次点击重新翻译"
                            }).apply()
                        } }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun announceJoiReady() {
        sendBroadcast(Intent(ScreenOcrService.ACTION_UPDATE).setPackage(packageName)
            .putExtra(ScreenOcrService.EXTRA_STATUS,"JoiPlay 直接取词已启动，打开已接入的游戏即可")
            .putExtra(ScreenOcrService.EXTRA_RUNNING,true))
    }

    private fun pollJoiPlay() {
        if(!active) return
        val now=System.currentTimeMillis()
        val live=JoiPlayGame.all(this).mapNotNull { game -> runCatching {
            val text=contentResolver.openInputStream(Uri.parse(game.feed))!!.use { input ->
                val data=ByteArray(150_000);var size=0
                while(size<data.size){val n=input.read(data,size,data.size-size);if(n<=0)break;size+=n}
                String(data,0,size,Charsets.UTF_8)
            }
            JoiFeedProtocol.parse(text,now)?.takeIf {it.identity.gameId==game.id}
        }.getOrNull() }.maxByOrNull {it.identity.heartbeat}
        if(!active || !joiMode) return
        if(live==null) {
            // A file may be observed during its bounded truncate/write. A recent
            // already-validated heartbeat remains valid until the same deadline.
            if(connectedKey!=null && now-lastHeartbeat in -2000..5000) return
            if(connectedKey!=null) synchronized(sessionLock){requests.clear();connectedKey=null;seen.clear()}
            capture.edit().putBoolean("joi_connected",false).putBoolean("game_text_visible",false).remove("game_title").apply()
            overlay.edit().putString("in", "").putString("out", "").putString("status", "等待已接入的 JoiPlay 游戏；不会自动切换 OCR").apply()
            return
        }
        val key=live.identity.gameId+"/"+live.identity.session
        lastHeartbeat=live.identity.heartbeat
        if(connectedKey!=key) {
            synchronized(sessionLock){requests.clear();resetTranslatorSession();seen.clear();connectedKey=key}
            textWasVisible=false
            overlay.edit().putString("in", "").putString("out", "").putString("status", "已连接 ${live.identity.title} · 等待游戏文字").apply()
        }
        capture.edit().putBoolean("joi_connected",true).putString("game_id",live.identity.gameId)
            .putString("game_title",live.identity.title).putString("game_engine",live.identity.engine)
            .putBoolean("game_text_visible",live.identity.textVisible).apply()
        if(textWasVisible && !live.identity.textVisible) {
            synchronized(sessionLock){requests.clear()}
            overlay.edit().putString("in", "").putString("out", "").putString("status","已连接 ${live.identity.title} · 等待游戏文字").apply()
        }
        textWasVisible=live.identity.textVisible
        live.frames.forEach { if(seen.add(it.id) && live.identity.textVisible) requests.offer(it) }
        while(seen.size>8192)seen.remove(seen.first())
    }

    private fun poll(uri: Uri) {
        try {
            if (polledUri != uri) {
                seen.clear()
                initialized = false
                polledUri = uri
            }
            // The RGSS writer caps this file and preserves its identity. Ignore
            // the final row if we read during a write.
            val stream = contentResolver.openInputStream(uri) ?: error("Document unavailable")
            val bytes = stream.use {
                val data = ByteArray(150_000)
                var size = 0
                while (size < data.size) {
                    val read = it.read(data, size, data.size - size)
                    if (read <= 0) break
                    size += read
                }
                String(data, 0, size, Charsets.UTF_8)
            }
            val fresh = mutableListOf<GameTextFrame>()
            for (line in bytes.substringBeforeLast('\n', "").lineSequence()) {
                val fields = line.split('\t', limit = 4)
                if (fields.size != 4) continue
                if (fields[2] == "luna_game") continue
                val id = fields[0] + "/" + fields[1]
                if (id in seen) continue
                val hex = fields[3]
                if (hex.length !in 2..16384 || hex.length % 2 != 0) continue
                val text = runCatching {
                    ByteArray(hex.length / 2) { index ->
                        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                    }.toString(Charsets.UTF_8)
                }.getOrNull() ?: continue
                seen.add(id)
                fresh.add(GameTextFrame(id, fields[2], text))
            }
            while (seen.size > 8192) seen.remove(seen.first())
            if (active && initialized) {
                fresh.forEach(requests::offer)
            }
            initialized = true
        } catch (_: Exception) {
            if (active) overlay.edit().putString("status", "读取游戏文字失败，请重新关联文字文件").apply()
        }
    }

    private fun translate(work: GameTranslationWork) {
        val frame = work.frame
        val source = capture.getString("source", "auto").orEmpty()
        val target = capture.getString("target", "zh").orEmpty()
        fun visible() = active && requests.isCurrent(work) && (work.manual || !capture.getBoolean("paused", false))
        fun publish(action: () -> Unit) { requests.publishIfCurrent(work) {
            if (active && (work.manual || !capture.getBoolean("paused", false))) action()
        } }
        if (!visible()) {
            if (active && !work.manual && capture.getBoolean("paused", false)) requests.retry(work, 0)
            return
        }
        val engine = synchronized(sessionLock) {
            if (!active || !requests.isCurrent(work)) null else translatorFromPreferences(this)
        } ?: return
        publish { overlay.edit().putString("in", frame.text).putString("out", "")
            .putString("kind", frame.kind).putString("frame", frame.id)
            .putString("request_mode", if (work.manual) "manual" else "automatic")
            .putLong("request_generation", work.generation).putLong("request_revision", work.revision)
            .putString("status", if (work.manual) "正在重新翻译…" else "正在翻译游戏文字…").apply() }
        val partial: (String) -> Unit = {
            publish { overlay.edit().putString("out", it).apply() }
        }
        if (!visible()) {
            if (active && !work.manual && capture.getBoolean("paused", false)) requests.retry(work, 0)
            return
        }
        val result = (if (work.manual) engine.translateStreaming(frame.text, source, target, partial)
                      else engine.translateAutomatically(frame.text, source, target, partial)).trim()
        if (active && result.isNotEmpty()) {
            HistoryStore(this).add(HistoryItem(source, target, frame.text, result))
            if (visible()) publish { overlay.edit().putString("out", result)
                .putString("status", if (work.manual) "已重译，等待下一句" else "已翻译，等待下一句").apply() }
            else if (!work.manual && capture.getBoolean("paused", false)) requests.retry(work, 0)
        }
    }

    override fun onDestroy() {
        active = false
        isRunning = false
        requests.clear()
        reader.shutdownNow()
        translator.shutdownNow()
        capture.edit().putBoolean("direct_text", false).putBoolean("joi_connected",false).putBoolean("game_text_visible",false).remove("game_title").apply()
        super.onDestroy()
    }
}
