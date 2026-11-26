package org.lunatranslator.mobile

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Deterministic screen-capture test page: the default ROI (bottom 40%) contains large Japanese text. */
class TestSubtitleActivity : AppCompatActivity() {
    private val lines = listOf(
        "今日はいい天気ですね。",
        "あなたをずっと待っていました。",
        "この世界の秘密を一緒に探しましょう。",
    )
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36, 36, 36, 48)
            setBackgroundColor(Color.rgb(25, 25, 28))
        }
        root.addView(TextView(this).apply {
            text = "Luna 屏幕翻译测试\n开启屏幕翻译后，下方日文应自动显示中文字幕。"
            setTextColor(Color.LTGRAY)
            textSize = 18f
            gravity = Gravity.CENTER
        })
        val changeButton = Button(this).apply {
            text = "切换日文字幕"
        }
        root.addView(
            changeButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        val subtitle = TextView(this).apply {
            text = lines[index]
            setTextColor(Color.WHITE)
            textSize = 34f
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xaa000000.toInt())
        }
        root.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        changeButton.setOnClickListener {
            index = (index + 1) % lines.size
            subtitle.text = lines[index]
        }
        setContentView(root)
    }
}
