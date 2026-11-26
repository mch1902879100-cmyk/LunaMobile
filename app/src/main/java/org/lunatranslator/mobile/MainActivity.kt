package org.lunatranslator.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.SharedPreferences
import android.content.IntentFilter
import android.os.Build
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var sourceSpinner: Spinner
    private lateinit var targetSpinner: Spinner
    private lateinit var input: EditText
    private lateinit var output: EditText
    private lateinit var status: TextView
    private lateinit var tts: TextToSpeech
    private lateinit var quickStatus: TextView
    private var quickPending = false
    private var quickConsentPending = false
    private val overlayConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (quickPending && Settings.canDrawOverlays(this)) continueQuickStart()
        else { quickPending = false; status.text = "未开启悬浮窗权限，可再次点击开启。" }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val joiFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri==null) {quickPending=false;status.text="未选择游戏目录，尚未接入。"}
        else {
            runCatching {
                contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                getSharedPreferences("joiplay",MODE_PRIVATE).edit().putString("library_tree",uri.toString()).apply()
                scanJoiLibrary(uri)
            }.onFailure {quickPending=false;status.text="授权失败：${it.message}"}
        }
    }
    private fun showJoiLibrary() {
        val saved=getSharedPreferences("joiplay",MODE_PRIVATE).getString("library_tree",null)
        if(saved==null) joiFolderPicker.launch(null) else scanJoiLibrary(Uri.parse(saved))
    }
    private fun scanJoiLibrary(tree:Uri) {
        status.text="正在查找已授权目录中的游戏…"
        worker.execute {
            runCatching { JoiGameScanner(this,tree).scan() }.onSuccess { games->runOnUiThread {
                if(games.isEmpty()) {
                    quickPending=false;status.text="未找到游戏，请选择包含游戏的文件夹。"
                    AlertDialog.Builder(this).setMessage("没有找到游戏。请选择包含游戏的总文件夹。")
                        .setPositiveButton("重新选择") { _,_->joiFolderPicker.launch(null) }.setNegativeButton("取消",null).show()
                } else {
                    status.text="找到 ${games.size} 个游戏，请选择要接入的游戏。"
                    val filtered=games.toMutableList()
                    val adapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,filtered.map{it.name}.toMutableList())
                    val search=EditText(this).apply {hint="搜索游戏名称或文件夹";setSingleLine(true)}
                    val list=ListView(this).apply {this.adapter=adapter}
                    val box=LinearLayout(this).apply {
                        orientation=LinearLayout.VERTICAL
                        addView(search,LinearLayout.LayoutParams(-1,-2))
                        addView(list,LinearLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.55f).toInt()))
                    }
                    val dialog=AlertDialog.Builder(this).setTitle("选择游戏 · 找到 ${games.size} 个").setView(box)
                        .setNeutralButton("更换文件夹") { _,_->joiFolderPicker.launch(null) }
                        .setNegativeButton("取消") { _,_->quickPending=false }.create()
                    list.setOnItemClickListener { _,_,index,_->
                        val selected=filtered.getOrNull(index) ?: return@setOnItemClickListener
                        dialog.dismiss();installJoiCandidate(tree,selected)
                    }
                    search.addTextChangedListener(object:TextWatcher {
                        override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                        override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                            val query=s?.toString()?.trim().orEmpty()
                            filtered.clear();filtered.addAll(games.filter{it.name.contains(query,true)})
                            adapter.clear();adapter.addAll(filtered.map{it.name});adapter.notifyDataSetChanged()
                        }
                        override fun afterTextChanged(s:Editable?){}
                    })
                    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                    dialog.show()
                }
            } }.onFailure {error->runOnUiThread {quickPending=false;status.text="查找失败：${error.message}"} }
        }
    }
    private fun installJoiCandidate(tree:Uri,game:JoiGameCandidate) {
        status.text="正在接入 ${game.name}…"
        val progress=AlertDialog.Builder(this).setTitle("正在接入游戏").setMessage("请稍候，正在检查游戏文件…").setCancelable(false).create()
        progress.show()
        worker.execute {
            runCatching { JoiPlayInstaller(this,tree,game.root).install() }
                .onSuccess { installed->runOnUiThread {
                    progress.dismiss()
                    if(isFinishing || isDestroyed)return@runOnUiThread
                    status.text="已接入 ${installed.title}；请重启该游戏生效。"
                    if(quickPending)continueQuickStart()
                    else AlertDialog.Builder(this).setTitle("接入成功")
                        .setMessage("${installed.title}\n\n请在 JoiPlay 中打开或重新启动这个游戏，悬浮球会自动识别。")
                        .setPositiveButton("打开 JoiPlay") { _,_->
                            packageManager.getLaunchIntentForPackage("cyou.joiplay.joiplay")?.let { startActivity(it) }
                        }.setNegativeButton("稍后",null).show()
                } }.onFailure {error->runOnUiThread {
                    progress.dismiss();quickPending=false
                    if(isFinishing || isDestroyed)return@runOnUiThread
                    val message=error.message ?: "无法读取或接入这个游戏"
                    status.text="接入失败：$message"
                    AlertDialog.Builder(this).setTitle("接入失败").setMessage(message).setPositiveButton("知道了",null).show()
                } }
        }
    }
    private val history by lazy { HistoryStore(this) }
    private val gameFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addWebGame(uri.toString())
        }.onFailure { status.text = "无法读取该文件夹，请重新选择游戏目录。" }
    }

    private val gameFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addWebGame(uri.toString())
        }.onFailure { status.text = "无法读取该游戏文件，请将游戏包保存到本机后重新选择。" }
    }

    private fun chooseGame() {
        AlertDialog.Builder(this).setTitle("打开 MV / MZ / Tyrano 游戏")
            .setItems(arrayOf("选择游戏文件夹（含更新包）", "选择游戏文件（APK / ASAR / ZIP / NW / EXE）")) { _, index ->
                if (index == 0) gameFolderPicker.launch(null) else gameFilePicker.launch(arrayOf("*/*"))
            }.setNegativeButton("取消", null).show()
    }

    private fun addWebGame(location: String) {
        persistLanguageSelections()
        status.text = "正在识别游戏…"
        worker.execute {
            runCatching { GameFolder(this, location).detect() }.onSuccess { game ->
                LibraryGame.remember(this, game)
                runOnUiThread { openWebGame(game) }
            }.onFailure { error -> runOnUiThread { status.text = error.message.orEmpty() } }
        }
    }

    private fun openWebGame(game: LibraryGame) {
        persistLanguageSelections()
        status.text = "正在打开 ${game.title}…"
        worker.execute {
            runCatching {
                val folder = GameFolder(this, game.location)
                val index = folder.resolve(folder.webRoot(game), listOf("index.html")) ?: error("游戏入口不存在")
                folder.open(index).use { check(it.read() >= 0) }
            }.onSuccess {
                runOnUiThread {
                    if (!isFinishing) {
                        getSharedPreferences("capture", MODE_PRIVATE).edit().putBoolean("paused", false).apply()
                        startActivity(Intent(this, WebGameActivity::class.java).putExtra("game", game.json().toString()))
                        status.text = "${game.engine} · 自动读取游戏文字"
                    }
                }
            }.onFailure {
                runOnUiThread {
                    status.text = "无法读取 ${game.title}。请重新选择游戏文件或文件夹；失效记录可在“我的游戏 → 整理列表”移除。"
                    AlertDialog.Builder(this).setTitle("找不到游戏文件")
                        .setMessage("请重新选择 ${game.title} 的文件或文件夹。游戏文件和已有进度不会被删除。")
                        .setPositiveButton("重新选择") { _, _ -> chooseGame() }
                        .setNegativeButton("返回", null).show()
                }
            }
        }
    }

    private fun manageGameLibrary() {
        val games = LibraryGame.all(this)
        if (games.isEmpty()) return
        val labels = games.map {
            val location = Uri.decode(it.location).substringAfter("document/").substringAfter("tree/").replace("primary:", "内部存储/")
            "${it.title} · ${it.engine}\n$location"
        }
        AlertDialog.Builder(this).setTitle("选择要从列表移除的游戏")
            .setItems(labels.toTypedArray()) { _, index ->
                val game = games[index]
                LibraryGame.forget(this, game.id)
                status.text = "已从列表移除 ${game.title}；游戏文件和已有进度保留。"
            }.setNegativeButton("返回", null).show()
    }

    private fun showGameLibrary() {
        val games = LibraryGame.all(this)
        if (games.isEmpty()) { chooseGame(); return }
        AlertDialog.Builder(this).setTitle("我的游戏")
            .setItems(games.map { "${it.title} · ${it.engine}" }.toTypedArray()) { _, index -> openWebGame(games[index]) }
            .setPositiveButton("添加游戏") { _, _ -> chooseGame() }
            .setNeutralButton("整理列表") { _, _ -> manageGameLibrary() }
            .setNegativeButton("取消", null).show()
    }
    private val gameTextPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            getSharedPreferences("capture", MODE_PRIVATE).edit().putString("game_text_uri", uri.toString()).apply()
            startGameText()
        }.onFailure { status.text = "无法关联游戏文字文件，请重新选择。" }
    }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { runOcr(it) }
        }

    private val desktopProfilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            val json=contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()}
            DesktopProfile.import(this,json)
        }.onSuccess {status.text="已导入桌面引擎：$it；密钥仅保存在应用私有设置。"}
            .onFailure {status.text="导入失败，请选择桌面 translatorsetting.json。"}
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (quickPending) continueQuickStart()
    }
    private val projectionConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(this, Intent(this, ScreenOcrService::class.java).apply {
                action = ScreenOcrService.ACTION_START
                putExtra(ScreenOcrService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenOcrService.EXTRA_RESULT_DATA, result.data)
            })
            if (quickConsentPending) ContextCompat.startForegroundService(this,
                Intent(this, FloatingTranslationService::class.java).setAction(FloatingTranslationService.ACTION_QUICK))
            status.text = "正在启动屏幕翻译…"
        } else {
            quickPending = false
            if (quickConsentPending) stopService(Intent(this, FloatingTranslationService::class.java))
            status.text = "未开启屏幕识别，点击开启可重试。"
        }
        quickConsentPending = false
    }

    private val gameConnectionUpdates = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if(key in setOf("joi_connected","game_title","translation_mode")) runOnUiThread { refreshQuickStatus() }
    }
    private val captureUpdates = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            status.text = intent.getStringExtra(ScreenOcrService.EXTRA_STATUS).orEmpty()
            if (quickPending && status.text.startsWith("屏幕翻译无法启动")) {
                quickPending = false
                stopService(Intent(this@MainActivity, FloatingTranslationService::class.java))
            }
            intent.getStringExtra(ScreenOcrService.EXTRA_OCR)?.let { input.setText(it) }
            intent.getStringExtra(ScreenOcrService.EXTRA_TRANSLATION)?.let { output.setText(it) }
            refreshQuickStatus()
            if (quickPending && intent.getBooleanExtra(ScreenOcrService.EXTRA_RUNNING, false)) {
                quickPending = false
                getSharedPreferences("capture", MODE_PRIVATE).edit().putBoolean("quick_setup_done", true).apply()
                returnToGames()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        quickPending = savedInstanceState?.getBoolean("quick_pending", false) ?: false
        quickConsentPending = savedInstanceState?.getBoolean("quick_consent_pending", false) ?: false
        tts = TextToSpeech(this, this)
        buildUi()
        restoreLanguageSelections()
        acceptIntent(intent)
        if (intent.action == "org.lunatranslator.mobile.PICK_JOI_GAME") window.decorView.post { showJoiLibrary() }
        val prefs = getSharedPreferences("capture", MODE_PRIVATE)
        if (savedInstanceState == null && intent.action == Intent.ACTION_MAIN &&
            prefs.getBoolean("quick_launch", true) && prefs.getBoolean("quick_setup_done", false)) {
            window.decorView.post { startQuickTranslation() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
        if (intent.action == "org.lunatranslator.mobile.PICK_JOI_GAME") showJoiLibrary()
        val p = getSharedPreferences("capture", MODE_PRIVATE)
        if (intent.action == Intent.ACTION_MAIN && p.getBoolean("quick_launch", true) && p.getBoolean("quick_setup_done", false)) {
            startQuickTranslation()
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        scroll.addView(box)
        setContentView(scroll)

        fun label(value: String) = TextView(this).apply {
            text = value
            textSize = 16f
            setPadding(0, 12, 0, 4)
        }

        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            views.forEach { child ->
                addView(
                    child,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(4, 2, 4, 2)
                    },
                )
            }
        }

        fun button(title: String, action: () -> Unit) = Button(this).apply {
            text = title
            setOnClickListener { action() }
        }

        val sourceLanguages = arrayOf("auto", "ja", "zh", "en")
        val targetLanguages = arrayOf("ja", "zh", "en")
        sourceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                sourceLanguages,
            )
        }
        targetSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                targetLanguages,
            )
            setSelection(1) // zh
        }

        input = EditText(this).apply {
            hint = "原文"
            minLines = 3
            gravity = Gravity.TOP
            setTextIsSelectable(true)
        }
        output = EditText(this).apply {
            hint = "译文"
            minLines = 3
            gravity = Gravity.TOP
            isFocusable = false
            setTextIsSelectable(true)
        }
        status = TextView(this).apply { setPadding(0, 12, 0, 24) }

        box.addView(label("Luna · JoiPlay 游戏直接取词").apply { textSize = 23f })
        quickStatus = label("未启动 · 点击下方开启")
        box.addView(quickStatus)
        box.addView(button("开启 JoiPlay 取词悬浮窗") { startQuickTranslation() })
        box.addView(label("插件报告当前游戏，直接读取对白、选项和战斗文字。\n首次接入游戏目录并允许悬浮窗；不需要屏幕共享。"))
        box.addView(android.widget.CheckBox(this).apply {
            text = "下次打开 Luna 时自动开启"
            isChecked = getSharedPreferences("capture", MODE_PRIVATE).getBoolean("quick_launch", true)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences("capture", MODE_PRIVATE).edit().putBoolean("quick_launch", checked).apply()
            }
        })
        box.addView(row(button("停止游戏取词") { stopScreenOcr(); refreshQuickStatus() },
            button("翻译引擎设置") { showSettings() }))
        box.addView(button("选择并接入 JoiPlay 游戏") { showJoiLibrary() })
        box.addView(label("首次授权包含游戏的文件夹，以后可直接从列表选择。接入后在 JoiPlay 里重新打开游戏即可自动识别。不同引擎的验证进度见安装说明。"))
        box.addView(button("内置播放器 · 我的游戏") { showGameLibrary() })
        box.addView(button("添加 RPG 游戏 · 直接取词") { chooseGame() })
        box.addView(label("OCR 仅作为手动备选，放在“更多功能与语言设置”。"))
        val advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        box.addView(button("更多功能与语言设置") {
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        box.addView(advanced)
        advanced.addView(label("原文 / 译文语言"))
        // Existing tools stay available without crowding the quick-start screen.
        val toolsBox = advanced
        toolsBox.addView(row(sourceSpinner, targetSpinner))
        toolsBox.addView(label("游戏翻译"))
        toolsBox.addView(button("选择游戏文件或文件夹 · 打开即翻译") { chooseGame() })
        toolsBox.addView(button("我的游戏") { showGameLibrary() })
        toolsBox.addView(button("OCR 备选 · 手动开启屏幕识别") {
            getSharedPreferences("capture", MODE_PRIVATE).edit().putBoolean("auto_fullscreen", true).apply()
            startScreenOcr()
        })
        toolsBox.addView(button("导入 Luna 桌面翻译配置") { desktopProfilePicker.launch("application/json") })
        toolsBox.addView(button("开始游戏直接取词") { startGameText() })
        toolsBox.addView(button("关联游戏文字文件") { gameTextPicker.launch(arrayOf("text/plain")) })
        toolsBox.addView(row(
            button("框选识别区域") {
                getSharedPreferences("capture", MODE_PRIVATE).edit().putBoolean("auto_fullscreen", false).apply()
                startActivity(Intent(this, RoiSelectorActivity::class.java))
            },
            button("开始屏幕翻译") { startScreenOcr() },
            button("停止屏幕翻译") { stopScreenOcr() },
        ))
        toolsBox.addView(button("打开日文测试页") {
            startActivity(Intent(this, TestSubtitleActivity::class.java))
        })
        toolsBox.addView(label("手动文字 / 图片翻译"))
        toolsBox.addView(input)
        toolsBox.addView(
            row(
                button("读取剪贴板") { readClipboard() },
                button("识别图片") { imagePicker.launch("image/*") },
            ),
        )
        toolsBox.addView(button("翻译") { translate() })
        toolsBox.addView(output)
        toolsBox.addView(
            row(
                button("复制译文") { copyTranslation() },
                button("朗读原文") { speak(input.text.toString()) },
                button("朗读译文") { speak(output.text.toString()) },
            ),
        )
        toolsBox.addView(
            row(
                button("清空") {
                    input.setText("")
                    output.setText("")
                },
                button("翻译历史") { showHistory() },
            ),
        )
        toolsBox.addView(label("悬浮字幕"))
        toolsBox.addView(
            row(
                button("显示字幕") { startOverlay() },
                button("隐藏字幕") {
                    stopService(Intent(this, FloatingTranslationService::class.java))
                    status.text = "字幕已隐藏。"
                },
            ),
        )
        toolsBox.addView(button("翻译引擎设置") { showSettings() })
        box.addView(status, 4)
    }

    private fun acceptIntent(incoming: Intent) {
        // Development-only entry for adb acceptance on emulator copies, never arbitrary release paths.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            incoming.action == "org.lunatranslator.mobile.TEST_LOCAL_GAME") {
            incoming.getStringExtra("game_folder")?.let { addWebGame(it) }
            return
        }
        val sharedText = when (incoming.action) {
            Intent.ACTION_PROCESS_TEXT -> incoming.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        sharedText?.let {
            input.setText(it)
            status.text = "已收到其他应用分享的文字。"
        }
    }

    private fun translate() {
        val text = input.text.toString().trim()
        if (text.isBlank()) {
            status.text = "请先输入或粘贴文字。"
            return
        }

        val sourceLanguage = sourceSpinner.selectedItem.toString()
        val targetLanguage = targetSpinner.selectedItem.toString()
        status.text = "翻译中…"

        persistLanguageSelections()
        worker.execute {
            try {
                val engine = translatorFromPreferences(this)

                val result = engine.translateStreaming(text, sourceLanguage, targetLanguage) { partial ->
                    runOnUiThread { output.setText(partial) }
                }
                runOnUiThread {
                    output.setText(result)
                    status.text = if (getSharedPreferences("engine", MODE_PRIVATE).getString("provider", "compatible") == "google") {
                        "已使用 Google 免费接口翻译。"
                    } else {
                        "已使用自定义引擎翻译。"
                    }
                    history.add(HistoryItem(sourceLanguage, targetLanguage, text, result))
                    getSharedPreferences("overlay", MODE_PRIVATE)
                        .edit()
                        .putString("in", text)
                        .putString("out", result)
                        .apply()
                }
            } catch (error: Exception) {
                runOnUiThread { status.text = "翻译失败：${error.message}" }
            }
        }
    }

    private fun runOcr(uri: Uri) {
        status.text = "正在识别图片…"
        worker.execute {
            try {
                val recognized = OcrProcessor.recognize(this, uri)
                runOnUiThread {
                    input.setText(recognized)
                    status.text = if (recognized.isBlank()) "没有识别到文字。" else "文字识别完成。"
                }
            } catch (error: Exception) {
                runOnUiThread { status.text = "识别失败：${error.message}" }
            }
        }
    }

    private fun readClipboard() {
        val manager = getSystemService(ClipboardManager::class.java)
        val clip = manager.primaryClip
        if (clip != null && clip.itemCount > 0) {
            input.setText(clip.getItemAt(0).coerceToText(this))
            status.text = "已读取剪贴板。"
        } else {
            status.text = "剪贴板为空或暂不可读。"
        }
    }

    private fun copyTranslation() {
        val translated = output.text.toString()
        if (translated.isBlank()) return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Luna translation", translated))
        status.text = "译文已复制。"
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "luna")
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) tts.language = Locale.getDefault()
    }

    private fun showHistory() {
        val items = history.load()
        if (items.isEmpty()) {
            status.text = "No translation history yet."
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Recent translations")
            .setItems(
                items.map { "${it.input.take(55)}\n→ ${it.output.take(55)}" }.toTypedArray(),
            ) { _, which ->
                val item = items[which]
                input.setText(item.input)
                output.setText(item.output)
                selectSpinnerValue(sourceSpinner, item.source)
                selectSpinnerValue(targetSpinner, item.target)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun selectSpinnerValue(spinner: Spinner, value: String) {
        val adapter = spinner.adapter
        for (index in 0 until adapter.count) {
            if (adapter.getItem(index)?.toString() == value) {
                spinner.setSelection(index)
                return
            }
        }
    }

    private fun showSettings() {
        val prefs = getSharedPreferences("engine", MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 8)
        }

        fun field(hintText: String, value: String): EditText = EditText(this).apply {
            hint = hintText
            setText(value)
            layout.addView(this)
        }

        val url = field(
            "接口地址（支持完整地址或 /v1）",
            prefs.getString("url", "").orEmpty(),
        )
        val key = field(
            "API Key（私有保存，不包含在安装包）",
            prefs.getString("key", "").orEmpty(),
        )
        key.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val model = field(
            "模型",
            prefs.getString("model", "deepseek-v4-flash").orEmpty(),
        )
        val normalize = android.widget.CheckBox(this).apply {
            text = "与 Luna 默认一致：Unicode 正规化（全角标点转半角）"
            isChecked = prefs.getBoolean("normalize_nfkc", true)
        }
        layout.addView(normalize)
        val automaticCache = android.widget.CheckBox(this).apply {
            text = "自动翻译复用已译文本（手动翻译始终重新请求）"
            isChecked = prefs.getBoolean("automatic_cache", true)
        }
        layout.addView(automaticCache)

        AlertDialog.Builder(this)
            .setTitle("自定义翻译引擎")
            .setMessage(
                "与桌面使用相同接口和模型；默认流式输出，保留两轮上下文。接口失败会直接报错，不自动换翻译引擎。",
            )
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                prefs.edit()
                    .putString("provider", "compatible")
                    .putString("url", url.text.toString())
                    .putString("key", key.text.toString())
                    .putString("model", model.text.toString())
                    .putBoolean("normalize_nfkc", normalize.isChecked)
                    .putBoolean("automatic_cache", automaticCache.isChecked)
                    .apply()
                status.text = "引擎设置已保存。"
                if (quickPending) continueQuickStart()
            }
            .setNegativeButton("取消") { _, _ -> quickPending = false }
            .setOnCancelListener { quickPending = false }
            .show()
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            status.text = "请允许悬浮窗权限，然后返回点击开始。"
        } else {
            startForegroundService(Intent(this, FloatingTranslationService::class.java))
            status.text = "悬浮字幕已显示。"
        }
    }

    private fun startQuickTranslation() {
        if (quickPending || quickConsentPending) return
        quickPending = true
        val p = getSharedPreferences("engine", MODE_PRIVATE)
        if (p.getString("provider", "compatible") != "google" && p.getString("url", "").isNullOrBlank()) {
            status.text = "首次使用请先设置翻译引擎。"
            showSettings()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else continueQuickStart()
    }

    private fun continueQuickStart() {
        if (!quickPending || quickConsentPending) return
        if (!Settings.canDrawOverlays(this)) {
            overlayConsent.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        persistLanguageSelections()
        val capture = getSharedPreferences("capture", MODE_PRIVATE)
        if(JoiPlayGame.all(this).isEmpty()) {
            status.text="首次使用请选择 JoiPlay 游戏目录，安装直接取词接入。"
            joiFolderPicker.launch(null)
            return
        }
        capture.edit().putBoolean("quick_mode", true).putString("translation_mode","joiplay")
            .putBoolean("paused", false).putBoolean("screen_running",false).apply()
        stopService(Intent(this, ScreenOcrService::class.java))
        if (GameTextService.isRunning && capture.getBoolean("joi_connected",false)) {
            ContextCompat.startForegroundService(this, Intent(this, FloatingTranslationService::class.java)
                .setAction(FloatingTranslationService.ACTION_QUICK))
            quickPending = false
            returnToGames()
            return
        }
        getSharedPreferences("overlay", MODE_PRIVATE).edit().putString("in", "").putString("out", "")
            .putString("status", "等待已接入的 JoiPlay 游戏").apply()
        ContextCompat.startForegroundService(this,Intent(this,GameTextService::class.java).setAction(GameTextService.JOIPLAY))
        ContextCompat.startForegroundService(this,Intent(this,FloatingTranslationService::class.java).setAction(FloatingTranslationService.ACTION_QUICK))
    }

    private fun returnToGames() {
        refreshQuickStatus()
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun refreshQuickStatus() {
        if (!::quickStatus.isInitialized) return
        val capture=getSharedPreferences("capture", MODE_PRIVATE)
        quickStatus.text = if (GameTextService.isRunning && capture.getString("translation_mode","")=="joiplay") {
            if(capture.getBoolean("joi_connected",false)) "已连接：${capture.getString("game_title","")} · 直接取词"
            else "JoiPlay 取词待命 · 请打开已接入游戏"
        } else if (ScreenOcrService.isRunning) {
            if (getSharedPreferences("capture", MODE_PRIVATE).getBoolean("paused", false)) "已暂停 · 点悬浮球继续"
            else "悬浮翻译运行中 · 可以打开你的游戏"
        } else "未启动 · 点击下方开启"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("quick_pending", quickPending)
        outState.putBoolean("quick_consent_pending", quickConsentPending)
        super.onSaveInstanceState(outState)
    }

    private fun startScreenOcr() {
        getSharedPreferences("capture", MODE_PRIVATE).edit().putBoolean("quick_mode", false).putString("translation_mode","screen").apply()
        persistLanguageSelections()
        if (!Settings.canDrawOverlays(this)) {
            status.text = "请先允许悬浮窗权限，以便在游戏中显示字幕。"
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        stopService(Intent(this, GameTextService::class.java))
        getSharedPreferences("capture", MODE_PRIVATE).edit().putBoolean("paused", false).apply()
        startOverlay()
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionConsent.launch(manager.createScreenCaptureIntent())
    }

    private fun stopScreenOcr() {
        stopService(Intent(this, ScreenOcrService::class.java))
        stopService(Intent(this, GameTextService::class.java))
        stopService(Intent(this, FloatingTranslationService::class.java))
        status.text = "游戏翻译已停止。"
    }

    private fun startGameText() {
        getSharedPreferences("capture", MODE_PRIVATE).edit().putBoolean("quick_mode", false).apply()
        if (!Settings.canDrawOverlays(this)) {
            startOverlay()
            return
        }
        val capture = getSharedPreferences("capture", MODE_PRIVATE)
        val uri = capture.getString("game_text_uri", null)
        if (uri == null) {
            status.text = "请先运行已接入的游戏，再选择 Download/RPGAndroid/LunaGameText.txt。"
            gameTextPicker.launch(arrayOf("text/plain"))
            return
        }
        persistLanguageSelections()
        stopService(Intent(this, ScreenOcrService::class.java))
        capture.edit().putBoolean("paused", false).apply()
        startOverlay()
        startForegroundService(Intent(this, GameTextService::class.java).setData(Uri.parse(uri)))
        status.text = "直接取词已启动，切回游戏继续对白或战斗。"
    }

    private fun persistLanguageSelections() {
        getSharedPreferences("capture", MODE_PRIVATE).edit()
            .putString("source", sourceSpinner.selectedItem.toString())
            .putString("target", targetSpinner.selectedItem.toString()).apply()
    }

    private fun restoreLanguageSelections() {
        val prefs = getSharedPreferences("capture", MODE_PRIVATE)
        selectSpinnerValue(sourceSpinner, prefs.getString("source", "auto").orEmpty())
        selectSpinnerValue(targetSpinner, prefs.getString("target", "zh").orEmpty())
    }

    override fun onResume() {
        super.onResume()
        refreshQuickStatus()
        getSharedPreferences("capture", MODE_PRIVATE).edit().putBoolean("editing", true).apply()
        val p=getSharedPreferences("overlay", MODE_PRIVATE)
        if (input.text.isBlank()) input.setText(p.getString("in", ""))
        if (output.text.isBlank()) output.setText(p.getString("out", ""))
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences("capture", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(gameConnectionUpdates)
        ContextCompat.registerReceiver(this, captureUpdates, IntentFilter(ScreenOcrService.ACTION_UPDATE), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        getSharedPreferences("capture", MODE_PRIVATE).edit().putBoolean("editing", false).apply()
        super.onPause()
    }

    override fun onStop() {
        getSharedPreferences("capture", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(gameConnectionUpdates)
        unregisterReceiver(captureUpdates)
        super.onStop()
    }

    override fun onDestroy() {
        tts.shutdown()
        worker.shutdown()
        super.onDestroy()
    }
}
