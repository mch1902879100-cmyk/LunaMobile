package org.lunatranslator.mobile

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.max
import kotlin.math.min

/** A simple, usable full-screen ROI editor.  Drag inside to move; drag a corner to resize. */
class RoiSelectorActivity : AppCompatActivity() {
    override fun onResume() {super.onResume();getSharedPreferences("capture", MODE_PRIVATE).edit().putBoolean("editing",true).apply()}
    override fun onPause() {getSharedPreferences("capture", MODE_PRIVATE).edit().putBoolean("editing",false).apply();super.onPause()}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        window.setDimAmount(0f)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.TRANSPARENT)
        val selector = RoiView(this)
        root.addView(selector)
        root.addView(TextView(this).apply {
            text = "拖动框体移动，拖动四角调整大小。\n只识别框内文字，请避开翻译浮窗。"
            setTextColor(Color.WHITE); setBackgroundColor(0xaa000000.toInt()); setPadding(24, 20, 24, 20)
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addView(Button(this).apply { text = "保存区域"; setOnClickListener { selector.save(); finish() } },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 36 })
        setContentView(root)
    }

    private class RoiView(context: android.content.Context) : View(context) {
        private val prefs = context.getSharedPreferences("capture", android.content.Context.MODE_PRIVATE)
        private val rect = RectF(); private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var mode = 0; private var downX = 0f; private var downY = 0f; private var original = RectF()
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            rect.set(prefs.getFloat("roi_left", 0f) * w, prefs.getFloat("roi_top", .60f) * h, prefs.getFloat("roi_right", 1f) * w, prefs.getFloat("roi_bottom", 1f) * h)
        }
        override fun onDraw(canvas: Canvas) {
            // The activity is translucent so the game/app underneath stays visible.
            // We only shade the area outside the OCR rectangle.
            paint.color = 0x88000000.toInt(); canvas.drawRect(0f, 0f, width.toFloat(), rect.top, paint); canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), paint); canvas.drawRect(0f, rect.top, rect.left, rect.bottom, paint); canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 6f; paint.color = 0xff80cbc4.toInt(); canvas.drawRect(rect, paint); paint.style = Paint.Style.FILL
            paint.color = 0xff80cbc4.toInt(); listOf(rect.left to rect.top, rect.right to rect.top, rect.left to rect.bottom, rect.right to rect.bottom).forEach { canvas.drawCircle(it.first, it.second, 22f, paint) }
            paint.color = Color.WHITE; paint.textSize = 34f; canvas.drawText("识别区域", rect.left + 16, rect.top + 42, paint)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX=e.x; downY=e.y; original.set(rect); mode=hit(e.x,e.y); return true }
                MotionEvent.ACTION_MOVE -> { val dx=e.x-downX; val dy=e.y-downY; when(mode) { 1 -> { rect.set(original); rect.offset(dx,dy) }; 2 -> {rect.set(original);rect.left=original.left+dx;rect.top=original.top+dy}; 3 -> {rect.set(original);rect.right=original.right+dx;rect.top=original.top+dy}; 4 -> {rect.set(original);rect.left=original.left+dx;rect.bottom=original.bottom+dy}; 5 -> {rect.set(original);rect.right=original.right+dx;rect.bottom=original.bottom+dy} }; clamp(); invalidate(); return true }
            }; return true
        }
        private fun hit(x: Float, y: Float): Int {
            fun near(a: Float, b: Float) = kotlin.math.abs(x - a) < 55f && kotlin.math.abs(y - b) < 55f
            return when {
                near(rect.left, rect.top) -> 2
                near(rect.right, rect.top) -> 3
                near(rect.left, rect.bottom) -> 4
                near(rect.right, rect.bottom) -> 5
                rect.contains(x, y) -> 1
                else -> 0
            }
        }
        private fun clamp() { val minSize=120f; if(rect.width()<minSize) rect.right=rect.left+minSize; if(rect.height()<minSize) rect.bottom=rect.top+minSize; if(rect.left<0)rect.offset(-rect.left,0f);if(rect.top<0)rect.offset(0f,-rect.top);if(rect.right>width)rect.offset(width-rect.right,0f);if(rect.bottom>height)rect.offset(0f,height-rect.bottom) }
        fun save() { prefs.edit().putFloat("roi_left",rect.left/width).putFloat("roi_top",rect.top/height).putFloat("roi_right",rect.right/width).putFloat("roi_bottom",rect.bottom/height).apply() }
    }
}
