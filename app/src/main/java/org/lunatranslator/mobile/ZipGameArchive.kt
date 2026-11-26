package org.lunatranslator.mobile

import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipFile

/** Read-only assets in legacy NW.js ZIP/EXE containers. Never extracts or executes an EXE. */
class ZipGameArchive(private val openZip: () -> ZipFile, overlays: List<() -> ZipFile> = emptyList()) {
    data class Entry(val path: String, val directory: Boolean, val size: Long, val storedName: String, val source: Int = 0)
    private val sources = listOf(openZip) + overlays
    private val entries = LinkedHashMap<String, Entry>()
    private val children = HashMap<String, MutableList<Entry>>()

    init {
        sources.forEachIndexed { source, opener -> opener().use { zip ->
            require(zip.size() <= 100_000) { "游戏包文件数量过多" }
            val seen = HashSet<String>()
            val items = zip.entries()
            while (items.hasMoreElements()) {
                val member = items.nextElement()
                val path = normalize(member.name)
                if (path.isEmpty()) continue
                val parts = path.split('/')
                for (i in 1 until parts.size) {
                    val parent = parts.take(i).joinToString("/")
                    val existing = entries[parent]
                    require(existing == null || existing.directory) { "游戏包目录冲突" }
                    entries.putIfAbsent(parent, Entry(parent, true, 0, ""))
                }
                require(seen.add(path)) { "游戏包包含重复文件" }
                val e = Entry(path, member.isDirectory, if (member.isDirectory) 0 else member.size, member.name, source)
                require(e.directory || (e.size >= 0 && member.method in setOf(0, 8))) { "不支持此游戏包的压缩方式" }
                val old = entries[path]
                require(old == null || old.directory && e.directory || source > 0 && !old.directory && !e.directory) { "游戏包包含重复文件或目录冲突" }
                entries[path] = e
            }
        } }
        entries.values.forEach { children.getOrPut(it.path.substringBeforeLast('/', "")) { mutableListOf() }.add(it) }
    }

    private fun normalize(name: String): String {
        require(name.length <= 4096 && !name.startsWith('/') && !name.contains('\\') &&
            !name.contains(':') && !name.contains('\u0000')) { "无效的游戏包路径" }
        val parts = name.trimEnd('/').split('/').filter { it != "." }
        require(parts.none { it == ".." || it.isEmpty() }) { "游戏包路径越界" }
        return parts.joinToString("/")
    }

    fun children(path: String): List<Entry> = children[path].orEmpty()
    fun info(path: String): Entry = entries[path] ?: error("游戏包缺少资源")

    fun open(path: String, start: Long = 0, length: Long? = null): InputStream {
        val e = info(path)
        val count = length ?: (e.size - start)
        require(!e.directory && start >= 0 && start <= e.size && count >= 0 && count <= e.size - start)
        val zip = sources[e.source]()
        try {
            val stream = zip.getInputStream(zip.getEntry(e.storedName) ?: error("游戏包已改变，请重新选择"))
            val owned = object : FilterInputStream(stream) {
                override fun close() { try { super.close() } finally { zip.close() } }
            }
            try {
                var remaining = start
                while (remaining > 0) {
                    val skipped = owned.skip(remaining)
                    if (skipped > 0) remaining -= skipped
                    else if (owned.read() >= 0) remaining--
                    else throw EOFException("游戏包资源已截断")
                }
                return MemberInputStream(owned, count)
            } catch (error: Exception) { owned.close(); throw error }
        } catch (error: Exception) { zip.close(); throw error }
    }
}
