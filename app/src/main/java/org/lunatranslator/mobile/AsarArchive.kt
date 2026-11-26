package org.lunatranslator.mobile

import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream

/** Read-only uncompressed Electron ASAR index; members stay in the original archive. */
class AsarArchive(private val fileSize: Long, private val openAt: (Long) -> InputStream) {
    data class Entry(val path: String, val directory: Boolean, val size: Long, val offset: Long, val unpacked: Boolean)
    private val header: AsarHeader
    private val tree: JSONObject
    init {
        openAt(0).use { stream ->
            val prefix = readExactly(stream, 16)
            header = AsarHeader.parse(prefix)
            require(fileSize < 0 || header.bodyOffset <= fileSize) { "ASAR 文件已截断" }
            tree = JSONObject(String(readExactly(stream, header.jsonLength), Charsets.UTF_8))
            require(tree.has("files")) { "ASAR 缺少文件目录" }
        }
    }
    private fun validate(segment: String) {
        require(segment.isNotEmpty() && segment != "." && segment != ".." &&
            !segment.contains('/') && !segment.contains('\\') && !segment.contains('\u0000')) { "无效的 ASAR 路径" }
    }
    private fun node(path: String): JSONObject {
        var current = tree
        if (path.isNotEmpty()) for (part in path.split('/')) {
            validate(part)
            current = current.getJSONObject("files").getJSONObject(part)
            require(!current.has("link")) { "暂不支持 ASAR 内部链接" }
        }
        return current
    }
    private fun entry(path: String, obj: JSONObject): Entry {
        val directory = obj.has("files")
        val size = if (directory) 0L else obj.getLong("size")
        val unpacked = obj.optBoolean("unpacked", false)
        val offset = if (directory || unpacked) 0L else obj.getString("offset").toLong()
        require(size >= 0 && offset >= 0 && offset <= Long.MAX_VALUE - header.bodyOffset - size) { "ASAR 成员范围无效" }
        if (!directory && !unpacked && fileSize >= 0) require(header.bodyOffset + offset + size <= fileSize) { "ASAR 成员已截断" }
        return Entry(path, directory, size, header.bodyOffset + offset, unpacked)
    }
    fun children(path: String): List<Entry> {
        val files = node(path).getJSONObject("files")
        return files.keys().asSequence().map { name ->
            validate(name)
            val value = files.getJSONObject(name)
            require(!value.has("link")) { "暂不支持 ASAR 内部链接" }
            entry(if (path.isEmpty()) name else "$path/$name", value)
        }.toList()
    }
    fun info(path: String) = entry(path, node(path))
    fun open(path: String, start: Long = 0, length: Long? = null): InputStream {
        val e = info(path)
        require(!e.directory && !e.unpacked)
        val count = length ?: (e.size - start)
        require(start in 0..e.size && count >= 0 && count <= e.size - start)
        return MemberInputStream(openAt(e.offset + start), count)
    }
    private fun readExactly(stream: InputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var at = 0
        while (at < length) {
            val n = stream.read(result, at, length - at)
            if (n < 0) throw EOFException("ASAR 文件已截断")
            if (n == 0) continue
            at += n
        }
        return result
    }
}
