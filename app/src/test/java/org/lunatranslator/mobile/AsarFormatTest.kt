package org.lunatranslator.mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AsarFormatTest {
    private fun header(size: Int, pickle: Int, json: Int) = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(4).putInt(size).putInt(pickle).putInt(json).array()
    @Test fun realArchiveHeaderAnd64BitBodyOffset() {
        val h = AsarHeader.parse(header(807964,807960,807953))
        assertEquals(807953,h.jsonLength)
        assertEquals(807972L,h.bodyOffset)
    }
    @Test fun rejectsTruncatedAndOversizedMetadata() {
        for (b in listOf(ByteArray(4),header(0,0,0),header(Int.MAX_VALUE,Int.MAX_VALUE-4,10),header(24,20,17))) {
            try { AsarHeader.parse(b); fail("Invalid header accepted") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun memberReadCannotConsumeFollowingResource() {
        val input=MemberInputStream(ByteArrayInputStream(byteArrayOf(1,2,3,99,100)),3)
        val bytes=ByteArray(10)
        assertEquals(3,input.read(bytes))
        assertArrayEquals(byteArrayOf(1,2,3),bytes.take(3).toByteArray())
        assertEquals(-1,input.read())
        assertEquals(-1,input.read(bytes))
    }
    @Test fun skipAndAvailableRespectMemberBoundary() {
        val input=MemberInputStream(ByteArrayInputStream(ByteArray(10)),4)
        assertEquals(4,input.available())
        assertEquals(4L,input.skip(100))
        assertEquals(0,input.available())
        assertEquals(-1,input.read())
    }
}
