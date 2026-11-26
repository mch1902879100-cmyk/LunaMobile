package org.lunatranslator.mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import java.security.MessageDigest

class ZipGameArchiveTest {
    @Test fun apkAssetsWithStoredAndCompressedMembersRemainReadOnly() {
        val file = File.createTempFile("luna-android-web-", ".apk")
        val image = ByteArray(1024) { (it % 251).toByte() }
        val text = "これは元のゲーム文章です。".repeat(100).toByteArray()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                fun member(name: String, bytes: ByteArray, stored: Boolean = false) {
                    val entry = ZipEntry(name)
                    if (stored) {
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.crc = CRC32().apply { update(bytes) }.value
                    }
                    zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
                }
                member("AndroidManifest.xml", byteArrayOf(3, 0, 8, 0))
                member("lib/arm64-v8a/libnative.so", byteArrayOf(127, 69, 76, 70), true)
                member("assets/www/index.html", "<html>game</html>".toByteArray())
                member("assets/www/js/main.js", "load('rmmz_core.js')".toByteArray())
                member("assets/www/data/Map001.json", text)
                member("assets/www/img/pictures/test.png", image, true)
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            val archive = ZipGameArchive({ ZipFile(file) })
            assertEquals(setOf("assets/www/index.html", "assets/www/js", "assets/www/data", "assets/www/img"),
                archive.children("assets/www").map { it.path }.toSet())
            assertArrayEquals(text.copyOfRange(17, 777), archive.open("assets/www/data/Map001.json", 17, 760).use { it.readBytes() })
            assertArrayEquals(image.copyOfRange(7, 23), archive.open("assets/www/img/pictures/test.png", 7, 16).use { it.readBytes() })
            assertArrayEquals(digest, MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        } finally { file.delete() }
    }

    private fun archive(names: List<Pair<String, ByteArray>>, body: (ZipGameArchive) -> Unit) {
        val file = File.createTempFile("luna-nw-", ".exe")
        try {
            // SFX preamble plus a real deflated ZIP, matching legacy NW's layout.
            file.outputStream().use { raw ->
                raw.write("MZ-stub-never-executed".toByteArray())
                ZipOutputStream(raw).use { zip ->
                    names.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                }
            }
            body(ZipGameArchive({ ZipFile(file) }))
        } finally { file.delete() }
    }

    @Test fun prefixedZipDirectoriesAndCompressedRanges() {
        val text="あいうえお".repeat(1000).toByteArray()
        archive(listOf("index.html" to "game".toByteArray(), "data/scenario/main.ks" to text,
            "media/raw.bin" to ByteArray(256) { it.toByte() })) { zip ->
            assertEquals(setOf("index.html","data","media"),zip.children("").map { it.path }.toSet())
            assertArrayEquals(text, zip.open("data/scenario/main.ks").use { it.readBytes() })
            assertArrayEquals(text.copyOfRange(499,819),zip.open("data/scenario/main.ks",499,320).use { it.readBytes() })
            assertEquals(-1,zip.open("data/scenario/main.ks",text.size.toLong(),0).use { it.read() })
            try { zip.open("index.html",3,2); fail("range should fail") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun rejectsUnsafePathsAndDirectoryConflicts() {
        for (path in listOf("../index.html","/index.html","C:/index.html","data/../index.html","data\\bad.ks")) {
            try { archive(listOf(path to byteArrayOf(1))) { fail("unsafe archive accepted") }; fail("expected rejection") }
            catch (_: IllegalArgumentException) {}
        }
        try { archive(listOf("data/file" to byteArrayOf(1),"data" to byteArrayOf(2))) { fail("conflict accepted") }; fail("expected rejection") }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun closedResponsesReleaseArchiveHandles() {
        archive(listOf("index.html" to ByteArray(4096) { (it%31).toByte() })) { zip ->
            repeat(300) { assertEquals(7,zip.open("index.html",7,1).use { it.read() }) }
        }
    }
    @Test fun patchOverlayReplacesOnlyMatchingAssets() {
        val base=File.createTempFile("luna-base-", ".zip")
        val patch=File.createTempFile("luna-patch-", ".tpatch")
        fun write(file: File, names: List<Pair<String,String>>) {
            ZipOutputStream(file.outputStream()).use { z -> names.forEach { (name,text) ->
                z.putNextEntry(ZipEntry(name));z.write(text.toByteArray());z.closeEntry()
            } }
        }
        try {
            write(base,listOf("index.html" to "base","data/a.ks" to "old","data/keep.ks" to "keep"))
            write(patch,listOf("data/a.ks" to "new","data/extra.ks" to "extra"))
            val zip=ZipGameArchive({ ZipFile(base) },listOf({ ZipFile(patch) }))
            assertEquals("new",zip.open("data/a.ks").use { it.reader().readText() })
            assertEquals("keep",zip.open("data/keep.ks").use { it.reader().readText() })
            assertEquals("extra",zip.open("data/extra.ks").use { it.reader().readText() })
            assertEquals(3,zip.children("data").size)
        } finally { base.delete();patch.delete() }
    }
}
