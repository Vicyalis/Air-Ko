package me.rerere.rikkahub.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载器的纯函数测试（不碰 Android 框架类）：文件名净化（防目录穿越）、URL 猜名、请求头解析。
 */
class DeviceDownloaderTest {

    @Test
    fun `sanitize replaces separators and control chars`() {
        assertEquals("a_b_c.txt", DeviceDownloader.sanitizeFileName("a/b\\c.txt"))
        assertEquals("q_w", DeviceDownloader.sanitizeFileName("q\u0000w"))
        assertEquals("x_y", DeviceDownloader.sanitizeFileName("x:y"))
    }

    @Test
    fun `sanitize defuses traversal names`() {
        assertEquals("_evil_name.txt", DeviceDownloader.sanitizeFileName("../evil/name.txt"))
        assertEquals("", DeviceDownloader.sanitizeFileName(".."))
        assertEquals("", DeviceDownloader.sanitizeFileName("   "))
        val defused = DeviceDownloader.sanitizeFileName("..\\..\\x")
        assertTrue(!defused.contains("/") && !defused.contains("\\"))
        assertTrue(!defused.startsWith(".."))
    }

    @Test
    fun `sanitize caps the length`() {
        val long = "a".repeat(400) + ".bin"
        assertEquals(160, DeviceDownloader.sanitizeFileName(long).length)
    }

    @Test
    fun `file name from url strips query and decodes`() {
        assertEquals(
            "report final.pdf",
            DeviceDownloader.fileNameFromUrl("https://example.com/files/report%20final.pdf?token=1#frag"),
        )
        assertEquals("pkg.apk", DeviceDownloader.fileNameFromUrl("https://example.com/pkg.apk"))
        assertEquals("", DeviceDownloader.fileNameFromUrl("https://example.com/"))
    }

    @Test
    fun `parse headers json object`() {
        val headers = DeviceDownloader.parseHeaders("""{"Referer":"https://x","Cookie":"a=b"}""")
        assertEquals("https://x", headers["Referer"])
        assertEquals("a=b", headers["Cookie"])
    }

    @Test
    fun `parse headers key value lines`() {
        val headers = DeviceDownloader.parseHeaders("Referer: https://x\n\nCookie: a=b\nbroken line\nKey:")
        assertEquals(2, headers.size)
        assertEquals("https://x", headers["Referer"])
        assertEquals("a=b", headers["Cookie"])
    }

    @Test
    fun `parse headers blank and junk give empty`() {
        assertEquals(0, DeviceDownloader.parseHeaders(null).size)
        assertEquals(0, DeviceDownloader.parseHeaders("   ").size)
        assertEquals(0, DeviceDownloader.parseHeaders("not json {").size)
    }

    @Test
    fun `format bytes bounds`() {
        assertEquals("0B", DeviceDownloader.formatBytes(0))
        assertEquals("512B", DeviceDownloader.formatBytes(512))
        assertEquals("1.5KB", DeviceDownloader.formatBytes(1536))
        assertEquals("1.0MB", DeviceDownloader.formatBytes(1024 * 1024))
    }
}
