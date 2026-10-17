package me.rerere.rikkahub.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备控制·文字定位的纯函数测试（不碰 Android 框架类）。
 *
 * 覆盖 Reticle 的 tap_text / wait_text 匹配口径：包含匹配、完全匹配、控件 id 只写后半段。
 */
class DeviceControlTextOpsTest {

    @Test
    fun `contains match is case insensitive`() {
        assertTrue(matchesQuery("Send Message", "send", exact = false))
        assertTrue(matchesQuery("发送消息", "发送", exact = false))
        assertFalse(matchesQuery("Cancel", "send", exact = false))
    }

    @Test
    fun `exact match ignores surrounding whitespace but not extra text`() {
        assertTrue(matchesQuery("  Send  ", "Send", exact = true))
        assertFalse(matchesQuery("Send message", "Send", exact = true))
    }

    @Test
    fun `blank values and empty queries never match`() {
        assertFalse(matchesQuery(null, "send", exact = false))
        assertFalse(matchesQuery("   ", "send", exact = false))
        assertFalse(matchesQuery("send", "", exact = false))
    }

    @Test
    fun `view id matches the short suffix`() {
        assertTrue(matchesViewId("com.example.app:id/send_btn", "send_btn"))
        assertTrue(matchesViewId("com.example.app:id/send_btn", "app:id/send"))
        assertFalse(matchesViewId("com.example.app:id/cancel_btn", "send_btn"))
        assertFalse(matchesViewId(null, "send_btn"))
    }

    @Test
    fun `matcher flags emptiness and describes itself`() {
        val empty = DeviceTextMatcher()
        assertTrue(empty.isEmpty)

        val matcher = DeviceTextMatcher(text = "发送", exact = true)
        assertFalse(matcher.isEmpty)
        val described = matcher.describe()
        assertTrue(described.contains("text=\"发送\""))
        assertTrue(described.contains("exact"))

        val byId = DeviceTextMatcher(viewId = "send_btn")
        assertTrue(byId.describe().contains("id=\"send_btn\""))
    }
}
