package org.lunatranslator.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/** User-consented, continuously throttled MediaProjection -> OCR -> translation pipeline. */
class ScreenOcrService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ocrRunning = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var lastOcrAt = 0L
    private val gate = TranslationGate()
    @Volatile private var active = false
    private var cachedFrame: Bitmap? = null
    private val captureTick = object : Runnable {
        override fun run() {
            if (!active) return
            imageReader?.let { onFrame(it) }
            mainHandler.postDelayed(this, OCR_INTERVAL_MS)
        }
    }
    private var captureWidth = 0
    private var captureHeight = 0
    private var stopping = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (active && width > 0 && height > 0 && (width != captureWidth || height != captureHeight)) {
                val old = imageReader
                old?.setOnImageAvailableListener(null, null)
                imageReader = makeReader(width, height)
                virtualDisplay?.resize(width, height, resources.configuration.densityDpi)
                virtualDisplay?.surface = imageReader!!.surface
                old?.close()
            }
        }
        override fun onStop() {
            update("屏幕共享已结束。")
            releaseCapture()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            update("屏幕翻译已停止。")
            releaseCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        createChannel()
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Luna 屏幕翻译运行中")
            .setContentText("切换到游戏即可，点悬浮球暂停或停止")
            .setContentIntent(PendingIntent.getActivity(this, 31, Intent(this, MainActivity::class.java)
                .setAction("org.lunatranslator.mobile.SETTINGS"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, "停止翻译", PendingIntent.getService(this, 32,
                Intent(this, FloatingTranslationService::class.java).setAction(FloatingTranslationService.ACTION_STOP_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOngoing(true).build())
        if (projection != null) return START_NOT_STICKY
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode == 0 || data == null) {
            update("屏幕翻译无法启动：请重新授予屏幕共享权限。")
            stopSelf(); return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, data).also {
                // Android 14 requires registering before createVirtualDisplay.
                it.registerCallback(projectionCallback, mainHandler)
            }
            createCapture()
            active = true
            isRunning = true
            getSharedPreferences("capture", MODE_PRIVATE).edit().putBoolean("direct_text", false)
                .putBoolean("screen_running", true).apply()
            mainHandler.post(captureTick)
            update("屏幕翻译已启动，切换到游戏即可。", running = true)
            return START_NOT_STICKY
        } catch (error: Exception) {
            update("屏幕翻译无法启动：请重新授权后重试。")
            releaseCapture(); stopSelf(); return START_NOT_STICKY
        }
    }

    private fun createCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        imageReader = makeReader(width, height)
        virtualDisplay = projection!!.createVirtualDisplay(
            "LunaScreenOcr", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, mainHandler,
        )
    }

    private fun makeReader(width: Int, height: Int): ImageReader {
        captureWidth = width; captureHeight = height
        return ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2).also { reader ->
            // Poll latest frame on a timer; static screens need a second stable observation too.
        }
    }

    private fun onFrame(reader: ImageReader) {
        // Always drain ImageReader. If throttled frames are left queued, the small
        // maxImages buffer fills almost immediately and continuous capture stalls.
        val image = runCatching { reader.acquireLatestImage() }.getOrNull()
        val now = System.currentTimeMillis()
        val capturePrefs = getSharedPreferences("capture", MODE_PRIVATE)
        if (!active || capturePrefs.getBoolean("paused", false) || capturePrefs.getBoolean("editing", false) || now - lastOcrAt < OCR_INTERVAL_MS || !ocrRunning.compareAndSet(false, true)) {
            image?.close()
            return
        }
        lastOcrAt = now
        worker.execute {
            try {
                image.use {
                    val fresh = it?.let { frame -> imageToBitmap(frame) }
                    if (fresh != null) { cachedFrame?.recycle(); cachedFrame = fresh }
                    val bitmap = cachedFrame?.copy(Bitmap.Config.ARGB_8888, true) ?: return@use
                    // Exclude our own overlay without FLAG_SECURE, which breaks screenshots on some emulators.
                    val mask=getSharedPreferences("capture", MODE_PRIVATE)
                    val canvas = android.graphics.Canvas(bitmap)
                    val paint = android.graphics.Paint().apply { color=android.graphics.Color.BLACK }
                    for (prefix in listOf("overlay", "overlay_bubble", "overlay_menu")) {
                        if (mask.getBoolean("${prefix}_visible", false)) canvas.drawRect(
                            mask.getInt("${prefix}_left",0).toFloat(),mask.getInt("${prefix}_top",0).toFloat(),
                            mask.getInt("${prefix}_right",0).toFloat(),mask.getInt("${prefix}_bottom",0).toFloat(), paint)
                    }
                    val cropped = cropToSavedRoi(bitmap)
                    if (cropped !== bitmap) bitmap.recycle()
                    val raw = try { OcrProcessor.recognize(cropped) } finally { cropped.recycle() }
                    val text = OcrText.forScreen(raw, capturePrefs.getBoolean("quick_mode", false),
                        capturePrefs.getString("source", "auto").orEmpty(), capturePrefs.getString("target", "zh").orEmpty())
                    getSharedPreferences("capture", MODE_PRIVATE).edit()
                        .putString("last_ocr", text)
                        .putLong("last_ocr_at", System.currentTimeMillis())
                        .apply()
                    if (text.isBlank() && capturePrefs.getBoolean("quick_mode", false)) {
                        gate.clearScreen()
                        getSharedPreferences("overlay", MODE_PRIVATE).edit().putString("in", "").putString("out", "")
                            .putString("status", "等待日文游戏画面…").apply()
                    }
                    if (!active || !gate.shouldTranslate(text, System.currentTimeMillis())) return@use
                    update("正在翻译…", text)
                    val prefs = getSharedPreferences("capture", MODE_PRIVATE)
                    val requestStarted=System.currentTimeMillis()
                    var partials=0
                    val translated = translatorFromPreferences(this).translateAutomatically(
                        text, prefs.getString("source", "auto").orEmpty(), prefs.getString("target", "zh").orEmpty(),
                    ) { partial ->
                        partials++
                        prefs.edit().putInt("stream_chunks",partials).apply()
                        if(partials==1) prefs.edit().putLong("first_token_ms",System.currentTimeMillis()-requestStarted).apply()
                        if (active && !prefs.getBoolean("paused", false) && !prefs.getBoolean("editing", false)) {
                            getSharedPreferences("overlay", MODE_PRIVATE).edit().putString("in",text).putString("out",partial).putString("status","正在翻译…").apply()
                        }
                    }.trim()
                    prefs.edit().putLong("translation_ms",System.currentTimeMillis()-requestStarted).apply()
                    if (active && !prefs.getBoolean("paused", false) && translated.isNotBlank()) {
                        gate.succeeded(text)
                        getSharedPreferences("overlay", MODE_PRIVATE).edit().putString("in", text).putString("out", translated).apply()
                        HistoryStore(this).add(HistoryItem(prefs.getString("source", "auto").orEmpty(), prefs.getString("target", "zh").orEmpty(), text, translated))
                        update("已翻译，等待下一句", text, translated)
                    }
                }
            } catch (error: Exception) {
                gate.failed(System.currentTimeMillis())
                if (active) update("识别或翻译失败，5 秒后重试：${error.message}")
            } finally { if (!active) { cachedFrame?.recycle(); cachedFrame=null }; ocrRunning.set(false) }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
        val width = image.width; val height = image.height
        val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        val buffer: ByteBuffer = plane.buffer; buffer.rewind(); padded.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(padded, 0, 0, width, height).also { if (it !== padded) padded.recycle() }
    }

    private fun cropToSavedRoi(bitmap: Bitmap): Bitmap {
        val p = getSharedPreferences("capture", MODE_PRIVATE)
        if (p.getBoolean("auto_fullscreen", false)) return bitmap
        val left = p.getFloat("roi_left", 0f); val top = p.getFloat("roi_top", .60f)
        val right = p.getFloat("roi_right", 1f); val bottom = p.getFloat("roi_bottom", 1f)
        val l = (left.coerceIn(0f, .95f) * bitmap.width).toInt()
        val t = (top.coerceIn(0f, .95f) * bitmap.height).toInt()
        val r = max(l + 1, min(bitmap.width, (right.coerceIn(.05f, 1f) * bitmap.width).toInt()))
        val b = max(t + 1, min(bitmap.height, (bottom.coerceIn(.05f, 1f) * bitmap.height).toInt()))
        return Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
    }

    private fun normalize(value: String): String = OcrText.normalize(value)

    private fun update(status: String, ocr: String? = null, translation: String? = null, running: Boolean = false) {
        getSharedPreferences("overlay", MODE_PRIVATE).edit().putString("status", status).apply()
        sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName).putExtra(EXTRA_STATUS, status).putExtra(EXTRA_RUNNING, running).apply {
            ocr?.let { putExtra(EXTRA_OCR, it) }; translation?.let { putExtra(EXTRA_TRANSLATION, it) }
        })
    }

    private fun releaseCapture() {
        active = false
        isRunning = false
        getSharedPreferences("capture", MODE_PRIVATE).edit().putBoolean("screen_running", false).apply()
        mainHandler.removeCallbacks(captureTick)
        if (stopping) return; stopping = true
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop(); projection = null
        stopping = false
    }

    override fun onDestroy() { update("屏幕翻译已停止。"); releaseCapture(); worker.shutdownNow(); super.onDestroy() }
    private fun createChannel() { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Luna screen OCR", NotificationManager.IMPORTANCE_LOW)) }
    companion object {
        @Volatile var isRunning = false
            private set
        const val EXTRA_RUNNING = "running"
        const val ACTION_START = "org.lunatranslator.mobile.START_SCREEN_OCR"; const val ACTION_STOP = "org.lunatranslator.mobile.STOP_SCREEN_OCR"; const val ACTION_UPDATE = "org.lunatranslator.mobile.SCREEN_OCR_UPDATE"
        const val EXTRA_RESULT_CODE = "result_code"; const val EXTRA_RESULT_DATA = "result_data"; const val EXTRA_STATUS = "status"; const val EXTRA_OCR = "ocr"; const val EXTRA_TRANSLATION = "translation"
        private const val OCR_INTERVAL_MS = 1_000L; private const val CHANNEL_ID = "luna_screen_ocr"; private const val NOTIFICATION_ID = 11
    }
}
