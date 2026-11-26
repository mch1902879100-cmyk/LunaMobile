package org.lunatranslator.mobile

data class ByteRange(val start: Long, val length: Long) {
    companion object {
        fun parse(value: String, size: Long): ByteRange {
            require(size > 0)
            val match = Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE).matchEntire(value.trim())
                ?: throw IllegalArgumentException("Unsupported byte range")
            val (first, last) = match.destructured
            require(first.isNotEmpty() || last.isNotEmpty())
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull() ?: throw IllegalArgumentException("Range overflow")
                require(suffix > 0)
                val count = minOf(suffix, size)
                return ByteRange(size - count, count)
            }
            val start = first.toLongOrNull() ?: throw IllegalArgumentException("Range overflow")
            val end = if (last.isEmpty()) size - 1 else minOf(last.toLongOrNull() ?: throw IllegalArgumentException("Range overflow"), size - 1)
            require(start < size && end >= start)
            return ByteRange(start, end - start + 1)
        }
    }
}
