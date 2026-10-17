package me.rerere.rikkahub.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计日志的纯函数测试（不碰 Android 框架类）：单行化、截断、行格式。
 */
class DeviceControlAuditTest {

    @Test
    fun `sanitize collapses newlines and long text`() {
        assertEquals("a b c", DeviceControlAudit.sanitize("a\nb\tc"))
        assertEquals("a b", DeviceControlAudit.sanitize("a   \r\n   b"))
        assertEquals("", DeviceControlAudit.sanitize(null))
        assertEquals("", DeviceControlAudit.sanitize("   "))

        val long = "x".repeat(300)
        val short = DeviceControlAudit.sanitize(long, limit = 20)
        assertTrue(short.startsWith("x".repeat(20)))
        assertTrue(short.contains("300"))
    }

    @Test
    fun `build line carries time tool duration and summaries`() {
        val timestamp = localNoon(2026, 10, 16)
        val line = DeviceControlAudit.buildLine(
            tool = "device_tap",
            args = "{\"x\":1,\n\"y\":2}",
            result = "ok",
            durationMs = 42,
            timestamp = timestamp,
        )
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(timestamp))
        assertTrue(line.startsWith("[$time] device_tap 42ms"))
        assertTrue(line.contains("args={\"x\":1, \"y\":2}"))
        assertTrue(line.contains("result=ok"))
        assertFalse(line.contains("\n"))
    }

    @Test
    fun `file name follows the day`() {
        val dir = java.io.File("unused")
        assertEquals("audit-2026-10-16.log", DeviceControlAudit.fileFor(dir, localNoon(2026, 10, 16)).name)
    }

    private fun localNoon(year: Int, month: Int, day: Int): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.clear()
        calendar.set(year, month - 1, day, 12, 0, 0)
        return calendar.timeInMillis
    }
}
