package org.lunatranslator.mobile

import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlin.math.*

/** Desktop-style subtitle surface. Controls appear on hover/tap, never occupy the reading area permanently. */
class FloatingTranslationService:Service() {
    private lateinit var wm:WindowManager
    private lateinit var content:SharedPreferences
    private lateinit var capture:SharedPreferences
    private lateinit var style:SharedPreferences
    private lateinit var panel:LinearLayout
    private lateinit var toolbar:LinearLayout
    private lateinit var original:TextView
    private lateinit var translated:TextView
    private lateinit var status:TextView
    private lateinit var grip:TextView
    private lateinit var pauseButton:TextView
    private lateinit var params:WindowManager.LayoutParams
    private val handler=Handler(Looper.getMainLooper())
    private var collapsed=false
    private var controls=true
    private var dialogOpen=false
    private var passThrough = false
    private lateinit var regionTool: TextView
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleDocked = false
    private var bubbleOnRight = false
    private var quickMenu: View? = null
    private var sizePreview=false
    private var quickPause: Button? = null
    private val hideMenu = Runnable { dismissQuickMenu() }
    private val hide=Runnable {if(!dialogOpen) showControls(false)}
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(key?.startsWith("overlay_")!=true && ::panel.isInitialized) refresh()
    }
    override fun onBind(intent:Intent?):IBinder?=null
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun positionKey(key:String)=if(isJoi())"joi_$key" else if(capture.getBoolean("quick_mode",false))"quick_$key" else key
    private fun bubbleKey(key:String)=if(isJoi())"joi_bubble_$key" else "bubble_$key"
    private fun isJoi()=capture.getString("translation_mode","")=="joiplay"
    private fun sourceRunning()=if(isJoi())GameTextService.isRunning else ScreenOcrService.isRunning
    override fun onCreate() {
        super.onCreate();wm=getSystemService(WINDOW_SERVICE) as WindowManager
        content=getSharedPreferences("overlay",MODE_PRIVATE);capture=getSharedPreferences("capture",MODE_PRIVATE);style=getSharedPreferences("appearance",MODE_PRIVATE)
        listOf(content,capture,style).forEach {it.registerOnSharedPreferenceChangeListener(listener)}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if (intent?.action == ACTION_STOP_ALL) {
            stopService(Intent(this, ScreenOcrService::class.java))
            stopService(Intent(this, GameTextService::class.java))
            stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("luna_overlay","Luna 悬浮字幕",NotificationManager.IMPORTANCE_LOW))
        startForeground(9, subtitleNotification())
        if(!Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY}
        if(!::panel.isInitialized) createPanel()
        if (capture.getBoolean("quick_mode", false)) {
            createBubble()
            if (intent?.action == ACTION_QUICK) setPassThrough(true)
        } else {dismissQuickMenu();bubble?.let{wm.removeView(it)};bubble=null;capture.edit().putBoolean("overlay_bubble_visible",false).apply()}
        if (intent?.action == "unlock_subtitles") setPassThrough(false)
        return START_NOT_STICKY
    }
    private fun createPanel() {
        panel=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(5),dp(2),dp(5),dp(2))}
        toolbar=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(0xdd24252b.toInt())};panel.addView(toolbar)
        fun tool(label:String,description:String,action:()->Unit):TextView=TextView(this).apply {
            text=label;contentDescription=description;textSize=14f;gravity=Gravity.CENTER;setTextColor(Color.WHITE)
            toolbar.addView(this,LinearLayout.LayoutParams(0,dp(34),1f));setOnClickListener {action();scheduleHide()}
        }
        val move=tool("Luna","拖动字幕窗口"){};attachDrag(move)
        regionTool = tool("▣","框选识别区域"){startActivity(Intent(this,RoiSelectorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        pauseButton=tool("Ⅱ","暂停或继续"){capture.edit().putBoolean("paused",!capture.getBoolean("paused",false)).apply()}
        tool("原","显示或隐藏原文"){style.edit().putBoolean("show_original",!style.getBoolean("show_original",false)).apply()}
        tool("抄","复制译文"){getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Luna",content.getString("out","")))}
        tool("历","翻译历史"){showHistory()}
        tool("Aa","字幕外观设置"){showAppearance()}
        tool("透", "开启字幕点击穿透") { setPassThrough(true) }
        tool("－","收起或展开"){collapsed=!collapsed;refresh();resize()}
        tool("×","停止游戏翻译"){
            stopService(Intent(this,ScreenOcrService::class.java))
            stopService(Intent(this,GameTextService::class.java))
            stopSelf()
        }
        original=TextView(this).apply {textSize=17f;setTextColor(Color.MAGENTA);setPadding(dp(4),dp(2),dp(4),dp(2))}
        translated=object:TextView(this){override fun onDraw(canvas:android.graphics.Canvas){repeat(if(style.getBoolean("desktop_shadow",true))10 else 1){super.onDraw(canvas)}}}.apply {setPadding(dp(4),dp(2),dp(4),dp(2));gravity=Gravity.CENTER;setTextColor(0xffeeeeee.toInt())}
        val lines=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;addView(original);addView(translated)}
        val scroll=object:ScrollView(this){override fun onMeasure(w:Int,h:Int){super.onMeasure(w,MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels*(if(isJoi()) .40f else if(capture.getBoolean("quick_mode",false)) .28f else .65f)).toInt(),MeasureSpec.AT_MOST))}}.apply {isFillViewport=false;addView(lines);tag="reading"}
        panel.addView(scroll);attachDrag(translated)
        status=TextView(this).apply {textSize=11f;setTextColor(Color.WHITE);setBackgroundColor(0xaa24252b.toInt());setPadding(dp(4),0,dp(4),0)};panel.addView(status)
        grip=TextView(this).apply {text="◢";contentDescription="拖动调整字幕宽度";gravity=Gravity.RIGHT;setTextColor(0xff777777.toInt());textSize=16f};panel.addView(grip)
        params=WindowManager.LayoutParams(defaultWidth(),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.START
            x=style.getInt(positionKey("x"),dp(if(capture.getBoolean("quick_mode",false))76 else 20))
            y=style.getInt(positionKey("y"),dp(if(capture.getBoolean("quick_mode",false))28 else 48))
        }
        var down=0f;var width=0
        grip.setOnTouchListener {_,event->when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{down=event.rawX;width=params.width;handler.removeCallbacks(hide);true}
            MotionEvent.ACTION_MOVE->{params.width=(width+event.rawX-down).toInt().coerceIn(dp(260),resources.displayMetrics.widthPixels);clamp();wm.updateViewLayout(panel,params);true}
            MotionEvent.ACTION_UP->{style.edit().putInt(positionKey("width"),params.width).apply();scheduleHide();true}
            else->true}}
        panel.setOnHoverListener {_,event->if(event.actionMasked==MotionEvent.ACTION_HOVER_ENTER){showControls(true)}else if(event.actionMasked==MotionEvent.ACTION_HOVER_EXIT){scheduleHide()};false}
        panel.viewTreeObserver.addOnGlobalLayoutListener {
            if(panel.visibility==View.VISIBLE && panel.height>0) {
                val oldX=params.x;val oldY=params.y
                clamp()
                if(params.x!=oldX || params.y!=oldY) wm.updateViewLayout(panel,params)
            }
            publishBounds()
        }
        wm.addView(panel,params);refresh();scheduleHide()
    }
    private fun attachDrag(view:View) {
        var sx=0f;var sy=0f;var px=0;var py=0;var moved=false
        view.setOnTouchListener {_,event->when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{sx=event.rawX;sy=event.rawY;px=params.x;py=params.y;moved=false;handler.removeCallbacks(hide);true}
            MotionEvent.ACTION_MOVE->{if(abs(event.rawX-sx)+abs(event.rawY-sy)>dp(6)) moved=true
                if(moved){params.x=px+(event.rawX-sx).toInt();params.y=py+(event.rawY-sy).toInt();clamp();wm.updateViewLayout(panel,params);panel.post{publishBounds()}};true}
            MotionEvent.ACTION_UP->{if(!moved)showControls(!controls);style.edit().putInt(positionKey("x"),params.x).putInt(positionKey("y"),params.y).apply();scheduleHide();true}
            else->true}}
    }
    private fun defaultWidth()=style.getInt(positionKey("width"),if(capture.getBoolean("quick_mode",false))(resources.displayMetrics.widthPixels*.85f).toInt() else min(dp(520),(resources.displayMetrics.widthPixels*.75f).toInt())).coerceIn(dp(260).coerceAtMost(resources.displayMetrics.widthPixels),resources.displayMetrics.widthPixels)
    private fun scheduleHide(){handler.removeCallbacks(hide);if(passThrough)handler.postDelayed(hide,3000)}
    private fun showControls(show:Boolean){controls=show&&!passThrough;toolbar.visibility=if(controls||collapsed)View.VISIBLE else View.GONE;grip.visibility=if(controls&&!collapsed)View.VISIBLE else View.GONE;refreshStatus()}
    private fun refreshStatus(){
        val value=content.getString("status","").orEmpty();val paused=capture.getBoolean("paused",false)
        status.text=if(paused)"已暂停" else value
        status.visibility=if((controls&&value.contains("失败"))||paused)View.VISIBLE else View.GONE
    }
    private fun refresh(){
        val out=content.getString("out","").orEmpty();val input=content.getString("in","").orEmpty()
        val visibleGameText=!isJoi() || (capture.getBoolean("joi_connected",false) && capture.getBoolean("game_text_visible",false))
        translated.text=if(sizePreview && (out.isBlank() || !visibleGameText)) "字幕大小预览" else out.ifBlank {
            if (capture.getBoolean("direct_text", false)) content.getString("status", "等待游戏文字…")
            else if (capture.getBoolean("auto_fullscreen", false)) "等待画面中的游戏文字…"
            else "等待选定区域中的文字…"
        }
        translated.textSize=style.getFloat("font_size",18f)
        translated.maxLines=if(isJoi())12 else if(capture.getBoolean("quick_mode",false))6 else Int.MAX_VALUE
        translated.ellipsize=if(capture.getBoolean("quick_mode",false))android.text.TextUtils.TruncateAt.END else null
        translated.setTypeface(Typeface.DEFAULT,if(style.getBoolean("bold",true))Typeface.BOLD else Typeface.NORMAL)
        translated.setTextColor(style.getInt("text_color",0xffeeeeee.toInt()))
        translated.setShadowLayer(if(style.getBoolean("desktop_shadow",true))translated.textSize*.2f else 0f,0f,0f,style.getInt("shadow_color",Color.BLUE))
        original.text=input;original.visibility=if(style.getBoolean("show_original",false)&&!collapsed)View.VISIBLE else View.GONE
        panel.findViewWithTag<View>("reading").visibility=if(collapsed)View.GONE else View.VISIBLE
        val alpha=(style.getInt("opacity",1).coerceIn(0,100)*255/100)
        panel.background=GradientDrawable().apply {setColor((alpha shl 24) or 0xffaaff);cornerRadius=dp(4).toFloat()}
        pauseButton.text=if(capture.getBoolean("paused",false))"▶" else "Ⅱ"
        regionTool.visibility = if (capture.getBoolean("direct_text", false)) View.GONE else View.VISIBLE
        if (capture.getBoolean("quick_mode", false)) {
            val connected=visibleGameText || !passThrough || sizePreview
            panel.visibility = if ((out.isNotBlank() || !passThrough || sizePreview) && sourceRunning() && connected &&
                (!capture.getBoolean("paused", false) || !passThrough || sizePreview) && !capture.getBoolean("editing", false)) View.VISIBLE else View.GONE
            refreshBubble()
        } else panel.visibility = View.VISIBLE
        showControls(controls)
    }
    private fun subtitleNotification(): Notification {
        val builder = NotificationCompat.Builder(this, "luna_overlay")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Luna 悬浮字幕")
            .setContentText(if (passThrough) "点击穿透已开启" else "轻触字幕显示工具栏")
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 21,
                Intent(this, MainActivity::class.java).setAction("org.lunatranslator.mobile.SETTINGS"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        builder.addAction(0, "停止翻译", PendingIntent.getService(this, 22,
            Intent(this, FloatingTranslationService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (passThrough) {
            val unlock = Intent(this, FloatingTranslationService::class.java).setAction("unlock_subtitles")
            builder.addAction(0, "恢复字幕操作", PendingIntent.getService(
                this, 19, unlock, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ))
        }
        return builder.build()
    }

    private fun setPassThrough(enabled: Boolean) {
        passThrough = enabled
        if (enabled) {
            collapsed = false
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // Android 12 rejects touches through untrusted overlays above this
            // opacity, even when their view background is fully transparent.
            params.alpha = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(android.hardware.input.InputManager::class.java)
                    .maximumObscuringOpacityForTouch - 0.01f).coerceIn(0f, 1f)
            } else 1f
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            params.alpha = 1f
        }
        refresh()
        showControls(!enabled)
        resize()
        getSystemService(NotificationManager::class.java).notify(9, subtitleNotification())
        if (!enabled) scheduleHide()
    }
    private fun publishViewBounds(view: View, prefix: String) {
        val at=IntArray(2);view.getLocationOnScreen(at)
        capture.edit().putInt("${prefix}_left",at[0]).putInt("${prefix}_top",at[1])
            .putInt("${prefix}_right",at[0]+view.width).putInt("${prefix}_bottom",at[1]+view.height)
            .putBoolean("${prefix}_visible",view.visibility==View.VISIBLE && view.isAttachedToWindow).apply()
    }
    private fun publishBounds(){publishViewBounds(panel,"overlay")}

    private fun createBubble() {
        if (bubble != null) return
        val view = object : TextView(this) {
            private val edgePaint = Paint()

            override fun onDraw(canvas: Canvas) {
                if (!bubbleDocked) {
                    super.onDraw(canvas)
                    return
                }
                val stripe = dp(1).coerceAtLeast(1)
                val edge = if (bubbleOnRight) width - stripe else 0
                val inner = if (bubbleOnRight) edge - stripe else stripe
                val top = (height - dp(24)) / 2f
                val bottom = top + dp(24)
                edgePaint.color = 0x26ffffff
                canvas.drawRect(edge.toFloat(), top, (edge + stripe).toFloat(), bottom, edgePaint)
                edgePaint.color = 0x20000000
                canvas.drawRect(inner.toFloat(), top, (inner + stripe).toFloat(), bottom, edgePaint)
            }
        }.apply {
            gravity=Gravity.CENTER;setTextColor(Color.WHITE)
            elevation=dp(4).toFloat()
            setOnClickListener { showQuickMenu() }
        }
        val p=WindowManager.LayoutParams(dp(58),dp(58),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.START
            x=capture.getInt(bubbleKey("x"),dp(8));y=capture.getInt(bubbleKey("y"),dp(100))
        }
        bubble=view;bubbleParams=p
        bubbleDocked = capture.getBoolean(bubbleKey("docked"), capture.contains(bubbleKey("x")))
        bubbleOnRight = capture.getBoolean(bubbleKey("right"), p.x > dp(8))
        clampBubble()
        var sx=0f;var sy=0f;var px=0;var py=0;var moved=false;var startedDocked=false
        view.setOnTouchListener {_,e->when(e.actionMasked) {
            MotionEvent.ACTION_DOWN->{
                sx=e.rawX;sy=e.rawY
                startedDocked=bubbleDocked
                px=if(startedDocked && bubbleOnRight) (resources.displayMetrics.widthPixels-dp(58)).coerceAtLeast(0) else p.x
                py=p.y;moved=false;true
            }
            MotionEvent.ACTION_MOVE->{
                if(!moved && abs(e.rawX-sx)+abs(e.rawY-sy)>dp(6)) {
                    moved=true
                    dismissQuickMenu()
                    bubbleDocked=false
                    refreshBubble()
                }
                if(moved){
                    p.x=px+(e.rawX-sx).toInt();p.y=py+(e.rawY-sy).toInt()
                    clampBubble();wm.updateViewLayout(view,p)
                }
                true
            }
            MotionEvent.ACTION_UP->{if(moved) dockBubble() else view.performClick();true}
            MotionEvent.ACTION_CANCEL->{
                if(moved){
                    bubbleDocked=startedDocked
                    p.x=px;p.y=py
                    clampBubble();wm.updateViewLayout(view,p);refreshBubble()
                }
                true
            }
            else->true
        }}
        view.viewTreeObserver.addOnGlobalLayoutListener{publishViewBounds(view,"overlay_bubble")}
        wm.addView(view,p);refreshBubble()
    }

    private fun clampBubble() {
        bubbleParams?.let {p->
            p.width=dp(if(bubbleDocked) 26 else 58)
            p.gravity=Gravity.TOP or if(bubbleDocked && bubbleOnRight) Gravity.RIGHT else Gravity.LEFT
            p.x=if(bubbleDocked) 0 else p.x.coerceIn(0,(resources.displayMetrics.widthPixels-p.width).coerceAtLeast(0))
            p.y=p.y.coerceIn(0,(resources.displayMetrics.heightPixels-p.height-dp(24)).coerceAtLeast(0))
        }
    }

    private fun dockBubble() {
        val view=bubble ?: return
        val p=bubbleParams ?: return
        if(!bubbleDocked) bubbleOnRight=p.x+p.width/2>=resources.displayMetrics.widthPixels/2
        bubbleDocked=true
        clampBubble()
        wm.updateViewLayout(view,p)
        refreshBubble()
        capture.edit().putBoolean(bubbleKey("docked"),true)
            .putBoolean(bubbleKey("right"),bubbleOnRight).putInt(bubbleKey("y"),p.y).apply()
    }

    private fun refreshBubble() {
        val paused=capture.getBoolean("paused",false)
        val value=content.getString("status","").orEmpty()
        val text=when {
            !sourceRunning()->"未运行"
            paused->"已暂停"
            isJoi() && !capture.getBoolean("joi_connected",false)->"待游戏"
            value.contains("失败")->"需重试"
            value.contains("正在翻译")->"翻译中"
            isJoi()->capture.getString("game_title","已连接").orEmpty().take(7)
            else->"识别中"
        }
        bubble?.text=if(bubbleDocked) "" else (if(isJoi())"Joi" else "Luna")+"\n$text"
        bubble?.textSize=11f
        bubble?.contentDescription=if(bubbleDocked) "Luna 侧边把手，$text，点击打开控制菜单" else "Luna 悬浮球，$text，点击打开菜单，拖动可收进侧边"
        bubble?.elevation=if(bubbleDocked) 0f else dp(4).toFloat()
        bubble?.background=if(bubbleDocked) null else GradientDrawable().apply {
            shape=GradientDrawable.OVAL
            setColor(if(paused || !sourceRunning())0xe6505360.toInt() else 0xe635558c.toInt())
            setStroke(dp(1),0x996e9bdf.toInt())
        }
        bubble?.invalidate()
        quickPause?.text=if(paused)"继续翻译" else "暂停翻译"
    }

    private fun showQuickMenu() {
        if (quickMenu!=null) { dismissQuickMenu();return }
        val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(6),dp(4),dp(6),dp(4));setBackgroundColor(0xf0242935.toInt())}
        fun item(label:String, action:()->Unit):Button=Button(this).apply {
            text=label;textSize=13f;setOnClickListener{action()};box.addView(this,LinearLayout.LayoutParams(-1,dp(43)))
        }
        box.addView(TextView(this).apply{text=if(isJoi())"JoiPlay · "+capture.getString("game_title","等待游戏") else "OCR 屏幕识别备选";setTextColor(Color.WHITE);textSize=12f;setPadding(dp(6),dp(6),dp(6),dp(6));maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
        val sizeRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        val sizeLabel=TextView(this).apply { setTextColor(Color.WHITE);gravity=Gravity.CENTER;textSize=13f }
        val example=TextView(this).apply {text="字幕大小预览";gravity=Gravity.CENTER;setPadding(dp(2),dp(2),dp(2),dp(4))}
        fun sizeText(){
            val value=style.getFloat("font_size",18f)
            sizeLabel.text="字幕 ${value.toInt()}";example.textSize=value
            example.setTextColor(style.getInt("text_color",0xffeeeeee.toInt()))
            example.setTypeface(Typeface.DEFAULT,if(style.getBoolean("bold",true))Typeface.BOLD else Typeface.NORMAL)
            example.setShadowLayer(if(style.getBoolean("desktop_shadow",true))example.textSize*.2f else 0f,0f,0f,style.getInt("shadow_color",Color.BLUE))
        }
        fun sizeButton(label:String,delta:Int)=Button(this).apply {
            text=label;textSize=20f;contentDescription=if(delta<0)"缩小字幕" else "放大字幕"
            setOnClickListener {
                val next=(style.getFloat("font_size",18f)+delta).coerceIn(8f,36f)
                sizePreview=true;style.edit().putFloat("font_size",next).apply();sizeText();refresh();handler.removeCallbacks(hideMenu)
            }
        }
        sizeRow.addView(sizeButton("−",-1),LinearLayout.LayoutParams(0,dp(43),1f))
        sizeRow.addView(sizeLabel,LinearLayout.LayoutParams(0,dp(43),1f))
        sizeRow.addView(sizeButton("+",1),LinearLayout.LayoutParams(0,dp(43),1f))
        sizeText();box.addView(sizeRow);box.addView(example,LinearLayout.LayoutParams(-1,-2))
        if(!bubbleDocked) item("收起到侧边") {dismissQuickMenu();dockBubble()}
        quickPause=item(if(capture.getBoolean("paused",false))"继续翻译" else "暂停翻译") {
            capture.edit().putBoolean("paused",!capture.getBoolean("paused",false)).apply();dismissQuickMenu()
        }
        if(isJoi()) item("重新翻译当前文字") {dismissQuickMenu();startService(Intent(this,GameTextService::class.java).setAction(GameTextService.RETRANSLATE))}
        if(isJoi()) item("接入其他游戏") {dismissQuickMenu();startActivity(Intent(this,MainActivity::class.java)
            .setAction("org.lunatranslator.mobile.PICK_JOI_GAME").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        if(isJoi()) item("打开 JoiPlay 游戏库") {
            dismissQuickMenu()
            packageManager.getLaunchIntentForPackage("cyou.joiplay.joiplay")?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        item("调整字幕位置 / 外观") {dismissQuickMenu();setPassThrough(false)}
        item("完成调整 · 点击穿透") {dismissQuickMenu();setPassThrough(true)}
        item("返回 Luna 设置") {dismissQuickMenu();startActivity(Intent(this,MainActivity::class.java)
            .setAction("org.lunatranslator.mobile.SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        item("停止翻译并关闭悬浮球") {
            dismissQuickMenu();stopService(Intent(this,ScreenOcrService::class.java));stopService(Intent(this,GameTextService::class.java));stopSelf()
        }
        val p=WindowManager.LayoutParams(min(dp(230),resources.displayMetrics.widthPixels),WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply {
            val anchor=bubbleParams
            gravity=Gravity.TOP or if(bubbleDocked && bubbleOnRight) Gravity.RIGHT else Gravity.LEFT
            val bx=anchor?.x?:0
            x=if(bubbleDocked) min((anchor?.width?:0)+dp(6),(resources.displayMetrics.widthPixels-width).coerceAtLeast(0))
              else (if(bx+dp(58)/2>resources.displayMetrics.widthPixels/2) bx-width-dp(6) else bx+dp(64))
                  .coerceIn(0,(resources.displayMetrics.widthPixels-width).coerceAtLeast(0))
            y=(bubbleParams?.y?:0).coerceAtMost((resources.displayMetrics.heightPixels-dp(450)).coerceAtLeast(0))
        }
        val scroll=object:ScrollView(this) {
            override fun onMeasure(w:Int,h:Int) {
                super.onMeasure(w,View.MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels-dp(24)).coerceAtLeast(dp(100)),View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(box) }
        quickMenu=scroll
        scroll.viewTreeObserver.addOnGlobalLayoutListener{publishViewBounds(scroll,"overlay_menu")}
        wm.addView(scroll,p)
    }

    private fun dismissQuickMenu() {
        handler.removeCallbacks(hideMenu)
        quickMenu?.let{runCatching{wm.removeView(it)}};quickMenu=null;quickPause=null
        if(sizePreview){sizePreview=false;if(::panel.isInitialized)refresh()}
        if (::capture.isInitialized) capture.edit().putBoolean("overlay_menu_visible",false).apply()
    }
    private fun clamp(){params.x=params.x.coerceIn(0,(resources.displayMetrics.widthPixels-params.width).coerceAtLeast(0));params.y=params.y.coerceIn(0,(resources.displayMetrics.heightPixels-panel.height).coerceAtLeast(0))}
    private fun resize(){params.width=if(collapsed)min(dp(360),resources.displayMetrics.widthPixels) else defaultWidth();clamp();wm.updateViewLayout(panel,params)}
    private fun dialog(builder:AlertDialog.Builder){dialogOpen=true;handler.removeCallbacks(hide);val dialog=builder.create();dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);dialog.setOnDismissListener{dialogOpen=false;scheduleHide()};dialog.show()}
    private fun showHistory(){val rows=HistoryStore(this).load();dialog(AlertDialog.Builder(this).setTitle("翻译历史").setItems(rows.map{it.output.take(70)}.toTypedArray()){_,i->content.edit().putString("in",rows[i].input).putString("out",rows[i].output).apply()}.setNegativeButton("关闭",null))}
    private fun showAppearance(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(8))}
        fun slider(label:String,value:Int,max:Int,change:(Int)->Unit){val title=TextView(this).apply{text="$label：$value"};box.addView(title);box.addView(SeekBar(this).apply{this.max=max;progress=value;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){if(user){title.text="$label：$p";change(p)}};override fun onStartTrackingTouch(s:SeekBar?){};override fun onStopTrackingTouch(s:SeekBar?){} })})}
        slider("字号",style.getFloat("font_size",18f).toInt(),36){style.edit().putFloat("font_size",it.coerceAtLeast(8).toFloat()).apply()}
        slider("背景不透明度",style.getInt("opacity",1),100){style.edit().putInt("opacity",it).apply()}
        box.addView(CheckBox(this).apply{text="显示原文";isChecked=style.getBoolean("show_original",false);setOnCheckedChangeListener{_,value->style.edit().putBoolean("show_original",value).apply()}})
        box.addView(CheckBox(this).apply{text="桌面阴影字体";isChecked=style.getBoolean("desktop_shadow",true);setOnCheckedChangeListener{_,value->style.edit().putBoolean("desktop_shadow",value).apply()}})
        dialog(AlertDialog.Builder(this).setTitle("字幕外观").setView(box).setPositiveButton("完成",null))
    }
    override fun onConfigurationChanged(newConfig:Configuration){super.onConfigurationChanged(newConfig);if(::panel.isInitialized)resize();dismissQuickMenu();clampBubble();bubble?.let{wm.updateViewLayout(it,bubbleParams)}}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);listOf(content,capture,style).forEach{it.unregisterOnSharedPreferenceChangeListener(listener)};dismissQuickMenu();capture.edit().putBoolean("overlay_visible",false).putBoolean("overlay_bubble_visible",false).apply();bubble?.let{runCatching{wm.removeView(it)}};if(::panel.isInitialized)runCatching{wm.removeView(panel)};super.onDestroy()}
    companion object {
        const val ACTION_QUICK="org.lunatranslator.mobile.QUICK_OVERLAY"
        const val ACTION_STOP_ALL="org.lunatranslator.mobile.STOP_ALL"
    }
}
