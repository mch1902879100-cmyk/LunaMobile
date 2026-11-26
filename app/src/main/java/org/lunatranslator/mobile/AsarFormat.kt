package org.lunatranslator.mobile

import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AsarHeader(val jsonLength: Int, val bodyOffset: Long) {
    companion object {
        fun parse(bytes: ByteArray): AsarHeader {
            require(bytes.size == 16) { "ASAR 文件头不完整" }
            val values = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val size = values.int.toLong() and 0xffffffffL
            val header = values.int.toLong() and 0xffffffffL
            val pickle = values.int.toLong() and 0xffffffffL
            val json = values.int.toLong() and 0xffffffffL
            require(size == 4L && header in 12L..16_777_216L && header % 4 == 0L &&
                pickle == header - 4 && json in 2L..(header - 8)) { "不支持或已损坏的 ASAR 文件头" }
            return AsarHeader(json.toInt(), 8L + header)
        }
    }
}

/** Prevent a response from leaking into the next archive member. */
class MemberInputStream(input: InputStream, length: Long) : FilterInputStream(input) {
    private var remaining = length.also { require(it >= 0) }
    override fun read(): Int {
        if (remaining == 0L) return -1
        val value = super.read()
        if (value >= 0) remaining--
        return value
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = `in`.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
        if (count > 0) remaining -= count
        return count
    }
    override fun skip(n: Long): Long = `in`.skip(minOf(n.coerceAtLeast(0), remaining)).also { remaining -= it }
    override fun available(): Int = minOf(`in`.available().toLong(), remaining).toInt()
    override fun markSupported() = false
}
