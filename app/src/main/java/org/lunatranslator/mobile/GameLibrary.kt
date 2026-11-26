package org.lunatranslator.mobile

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.FileInputStream
import android.os.ParcelFileDescriptor
import java.util.zip.ZipFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class LibraryGame(val location: String, val root: String, val title: String, val engine: String) {
    val id: String get() = MessageDigest.getInstance("SHA-256").digest((location + root).toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }
    fun json() = JSONObject().put("location", location).put("root", root).put("title", title).put("engine", engine)
    companion object {
        fun from(json: JSONObject) = LibraryGame(json.getString("location"), json.getString("root"),
            json.getString("title"), json.getString("engine"))
        fun all(context: Context): List<LibraryGame> = runCatching {
            val values = JSONArray(context.getSharedPreferences("library", Context.MODE_PRIVATE).getString("games", "[]"))
            (0 until values.length()).map { from(values.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        fun remember(context: Context, game: LibraryGame) {
            val games = listOf(game) + all(context).filter { it.id != game.id }
            context.getSharedPreferences("library", Context.MODE_PRIVATE).edit()
                .putString("games", JSONArray().apply { games.take(50).forEach { put(it.json()) } }.toString()).apply()
        }
        fun forget(context: Context, id: String) {
            val games = all(context).filter { it.id != id }
            context.getSharedPreferences("library", Context.MODE_PRIVATE).edit()
                .putString("games", JSONArray().apply { games.forEach { put(it.json()) } }.toString()).apply()
        }
    }
}

/** Reads only the selected directory or archive. Never patches source files or writes saves there. */
class GameFolder(private val context: Context, private val location: String) {
    data class Node(val id: String, val name: String, val directory: Boolean, val size: Long = -1,
        val archive: String? = null, val member: String = "")
    private val document = if (location.startsWith("content:")) Uri.parse(location) else null
    private val tree = document?.takeIf { DocumentsContract.isTreeUri(it) }
    private val directories = ConcurrentHashMap<String, List<Node>>()
    private val archives = ConcurrentHashMap<String, AsarArchive>()
    private val zipArchives = ConcurrentHashMap<String, ZipGameArchive>()
    private val archiveSources = ConcurrentHashMap<String, Node>()
    private val parents = ConcurrentHashMap<String, Node>()
    private val fileRoot = if (document == null) File(location).canonicalFile else null
    val root: Node = if (tree != null) {
        val id = DocumentsContract.getTreeDocumentId(tree)
        Node(id, id.substringAfterLast('/').substringAfterLast(':'), true)
    } else if (document != null) {
        var name = ""
        var size = -1L
        context.contentResolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0).orEmpty()
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        require(name.isNotBlank()) { "无法读取游戏文件名，请将游戏包保存到本机后重新选择" }
        Node(document.toString(), name, false, size)
    } else Node(fileRoot!!.path, fileRoot.name, fileRoot.isDirectory, if (fileRoot.isFile) fileRoot.length() else -1)

    private fun documentUri(node: Node): Uri = if (tree != null)
        DocumentsContract.buildDocumentUriUsingTree(tree, node.id) else document!!

    fun children(node: Node): List<Node> = directories.getOrPut(node.id + "|" + node.member) {
        if (node.archive != null || isArchive(node)) {
            val source = if (node.archive != null) archiveSources.getValue(node.archive) else node
            archiveSources[source.id] = source
            if (source.name.endsWith(".asar", true)) {
                val archive = archives.getOrPut(source.id) {
                    AsarArchive(source.size) { offset -> rawOpenAt(source, offset) }
                }
                archive.children(node.member).map { e -> Node(source.id, e.path.substringAfterLast('/'), e.directory, e.size, source.id, e.path) }
            } else {
                val archive = zipArchives.getOrPut(source.id) {
                    val patchName = source.name.substringBeforeLast('.') + ".tpatch"
                    val patch = if (source.name.substringAfterLast('.').lowercase() in setOf("exe", "nw"))
                        children(parents[source.id] ?: root).firstOrNull { !it.directory && it.name.equals(patchName,true) } else null
                    ZipGameArchive({ rawZip(source) }, patch?.let { listOf({ rawZip(it) }) }.orEmpty())
                }
                archive.children(node.member).map { e -> Node(source.id, e.path.substringAfterLast('/'), e.directory, e.size, source.id, e.path) }
            }
        } else if (document == null) {
            File(node.id).listFiles().orEmpty().filter { it.canonicalFile.toPath().startsWith(fileRoot!!.toPath()) }
                .map { Node(it.path, it.name, it.isDirectory, if (it.isFile) it.length() else -1).also { child -> parents[child.id] = node } }
        } else if (tree != null) {
            val values = mutableListOf<Node>()
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, node.id)
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                while (c.moveToNext()) values.add(Node(c.getString(0), c.getString(1),
                    c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (c.isNull(3)) -1 else c.getLong(3)).also { parents[it.id] = node })
            }
            values
        } else emptyList()
    }

    private fun isArchive(node: Node) = !node.directory &&
        node.name.substringAfterLast('.').lowercase() in setOf("asar", "zip", "nw", "exe", "apk")

    private fun rawZip(node: Node): ZipFile {
        val descriptor = if (document == null) ParcelFileDescriptor.open(File(node.id), ParcelFileDescriptor.MODE_READ_ONLY)
        else context.contentResolver.openFileDescriptor(documentUri(node), "r")
            ?: error("无法读取游戏包，请复制到手机本机存储后重新选择")
        // Keep the SAF descriptor alive too: concurrent assets must never reuse its /proc path.
        try {
            return object : ZipFile("/proc/self/fd/${descriptor.fd}") {
                override fun close() { try { super.close() } finally { descriptor.close() } }
            }
        } catch (error: Exception) { descriptor.close(); throw error }
    }

    fun resolve(base: Node, path: List<String>): Node? {
        var current = base
        for (segment in path) {
            if (segment.isBlank() || segment == ".") continue
            require(segment != ".." && !segment.contains('/') && !segment.contains('\\') && !segment.contains('\u0000'))
            current = children(current).firstOrNull { it.name == segment }
                ?: children(current).firstOrNull { it.name.equals(segment, true) } ?: return null
        }
        return current
    }

    private fun rawOpen(node: Node): InputStream = if (document == null) File(node.id).inputStream() else
        context.contentResolver.openInputStream(documentUri(node))
            ?: error("无法读取所选目录")

    private fun rawOpenAt(node: Node, offset: Long): InputStream {
        val input = rawOpen(node)
        try {
            val file = input as? FileInputStream ?: error("请将游戏复制到手机本机存储后重选目录")
            file.channel.position(file.channel.position() + offset)
            return input
        } catch (error: Exception) { input.close(); throw error }
    }

    fun open(node: Node): InputStream {
        if (node.archive == null) return rawOpen(node)
        zipArchives[node.archive]?.let { return it.open(node.member) }
        val archive = archives.getValue(node.archive)
        if (!archive.info(node.member).unpacked) return archive.open(node.member)
        val source = archiveSources.getValue(node.archive)
        val unpacked = resolve(parents[source.id] ?: root, listOf(source.name + ".unpacked") + node.member.split('/'))
            ?: error("缺少 ASAR 外置资源，请同时复制 .asar.unpacked 文件夹")
        return rawOpen(unpacked)
    }

    fun openRange(node: Node, start: Long, length: Long): InputStream {
        require(start >= 0 && length >= 0 && node.size >= 0 && start <= node.size && length <= node.size - start)
        if (node.archive == null) return MemberInputStream(rawOpenAt(node,start),length)
        zipArchives[node.archive]?.let { return it.open(node.member,start,length) }
        val archive = archives.getValue(node.archive)
        if (!archive.info(node.member).unpacked) return archive.open(node.member,start,length)
        val source = archiveSources.getValue(node.archive)
        val unpacked = resolve(parents[source.id] ?: root, listOf(source.name + ".unpacked") + node.member.split('/'))
            ?: error("缺少 ASAR 外置资源")
        return MemberInputStream(rawOpenAt(unpacked,start),length)
    }

    fun webRoot(game: LibraryGame) = resolve(root, game.root.split('/')) ?: error("游戏目录已移动，请重新选择")

    fun detect(): LibraryGame {
        val pending = ArrayDeque<Pair<Node, String>>().apply { add(root to "") }
        var visited = 0
        while (pending.isNotEmpty() && ++visited <= 80) {
            val (node, path) = pending.removeFirst()
            // A native Windows EXE is not a ZIP. Skip it and keep looking for actual web assets.
            val entries = try { children(node) } catch (error: Exception) {
                if (isArchive(node) && !node.name.endsWith(".asar",true)) continue else throw error
            }
            if (entries.any { it.name.equals("index.html", true) }) {
                val main = resolve(node, listOf("js", "main.js"))
                val mainText = main?.let { open(it).bufferedReader().use { reader -> reader.readText().take(1000000) } }.orEmpty()
                val engine = when {
                    resolve(node, listOf("js", "rmmz_core.js")) != null && mainText.contains("rmmz_core.js") -> "RPG Maker MZ"
                    resolve(node, listOf("js", "rpg_core.js")) != null -> "RPG Maker MV"
                    resolve(node, listOf("tyrano", "tyrano.js")) != null -> "Tyrano"
                    else -> null
                }
                if (engine != null) {
                    val index = entries.first { it.name.equals("index.html", true) }
                    val html = open(index).bufferedReader().use { it.readText() }
                    var title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .find(html)?.groupValues?.get(1)?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim().take(180) }
                        .orEmpty().ifBlank { node.name }
                    if (engine == "Tyrano") {
                        resolve(node,listOf("data","system","Config.tjs"))?.let { config ->
                            val line = open(config).bufferedReader().use { it.readLines() }.firstOrNull {
                                it.trimStart().startsWith(";System.title",true) && it.contains('=')
                            }
                            line?.substringAfter('=')?.trim()?.trimEnd(';')?.trim()?.removeSurrounding("\"")
                                ?.takeIf { it.isNotBlank() }?.let { title=it.take(180) }
                        }
                    }
                    return LibraryGame(location, path, title, engine)
                }
            }
            if (path.count { it == '/' } < 3) entries.filter { (it.directory || isArchive(it)) && it.name.lowercase() !in
                setOf("img", "audio", "data", "js", "fonts", "movies", "node_modules", "engine") }
                .forEach { pending.add(it to (if (path.isBlank()) it.name else "$path/${it.name}")) }
        }
        error("未找到 MV、MZ 或 Tyrano 的网页游戏。可选择游戏文件夹或包含网页资源的 APK/ASAR/ZIP/NW/EXE 文件；原生 Unity/Unreal APK 和 Windows 原生程序不能用此播放器打开。EXE 带 tpatch 更新时请选择整个文件夹。")
    }
}
