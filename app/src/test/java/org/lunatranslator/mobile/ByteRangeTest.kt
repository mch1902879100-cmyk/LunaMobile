package org.lunatranslator.mobile

import org.junit.Assert.*
import org.junit.Test

class ByteRangeTest {
    @Test fun videoSeekOpenAndClampedRanges() {
        assertEquals(ByteRange(100,100),ByteRange.parse("bytes=100-199",1000))
        assertEquals(ByteRange(100,900),ByteRange.parse("bytes=100-",1000))
        assertEquals(ByteRange(900,100),ByteRange.parse("bytes=900-99999",1000))
    }
    @Test fun suffixReadsCannotStartBeforeMember() {
        assertEquals(ByteRange(900,100),ByteRange.parse("bytes=-100",1000))
        assertEquals(ByteRange(0,1000),ByteRange.parse("bytes=-99999",1000))
    }
    @Test fun malformedOverflowAndOutOfBoundsAreRejected() {
        for(value in listOf("bytes=", "bytes=-0", "bytes=1000-", "bytes=5-4", "bytes=0-1,2-3", "bytes=9999999999999999999999-")) {
            try { ByteRange.parse(value,1000);fail("Invalid range accepted") } catch (_:IllegalArgumentException) {}
        }
    }
}
