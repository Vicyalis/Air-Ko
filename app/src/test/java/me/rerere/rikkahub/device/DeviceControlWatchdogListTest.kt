package me.rerere.rikkahub.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 看门狗·名单处理的纯函数测试（不碰 Android 框架类）。
 *
 * 覆盖 Reticle 的 A11yWatchdog 口径：只看本应用那一条、不动别的辅助服务、兼容 `$` 写法。
 */
class DeviceControlWatchdogListTest {

    private val self = "com.airko.app/me.rerere.rikkahub.device.DeviceControlService"
    private val selfSet = setOf(
        self,
        // 大小写变体（系统名单大小写不保证）
        "COM.AIRKO.APP/ME.RERERE.RIKKAHUB.DEVICE.DEVICECONTROLSERVICE",
        // \$ 写法（flattenToString 在某些系统上的存储形态）
        "com.airko.app\$me.rerere.rikkahub.device.DeviceControlService",
    )

    @Test
    fun `parse filters empties and whitespace`() {
        assertEquals(
            listOf("a/b", "c/d"),
            DeviceControlWatchdog.parseEnabledServiceList(" a/b : c/d : "),
        )
        assertEquals(emptyList<String>(), DeviceControlWatchdog.parseEnabledServiceList(null))
        assertEquals(emptyList<String>(), DeviceControlWatchdog.parseEnabledServiceList(":"))
    }

    @Test
    fun `has service matches dollar form`() {
        assertTrue(
            DeviceControlWatchdog.hasServiceInList(
                "com.other/x:com.airko.app\$me.rerere.rikkahub.device.DeviceControlService",
                selfSet,
            )
        )
        assertFalse(
            DeviceControlWatchdog.hasServiceInList("com.other/x:com.other2/y", selfSet)
        )
        assertFalse(DeviceControlWatchdog.hasServiceInList(null, selfSet))
    }

    @Test
    fun `append keeps existing entries and does not duplicate`() {
        val appended = DeviceControlWatchdog.appendServiceToList("com.other/x", selfSet, self)
        assertEquals("com.other/x:$self", appended)

        val already = DeviceControlWatchdog.appendServiceToList(
            "com.other/x:com.airko.app\$me.rerere.rikkahub.device.DeviceControlService",
            selfSet,
            self,
        )
        assertEquals(
            "com.other/x:com.airko.app\$me.rerere.rikkahub.device.DeviceControlService",
            already,
        )
    }

    @Test
    fun `append on empty list writes just self`() {
        assertEquals(self, DeviceControlWatchdog.appendServiceToList("", selfSet, self))
        assertEquals(self, DeviceControlWatchdog.appendServiceToList(null, selfSet, self))
    }

    @Test
    fun `strip removes only self and keeps others in order`() {
        val raw = "com.other/x:$self:com.other2/y"
        assertEquals("com.other/x:com.other2/y", DeviceControlWatchdog.stripServiceFromList(raw, selfSet))
        assertEquals("", DeviceControlWatchdog.stripServiceFromList(self, selfSet))
        assertEquals(
            "com.other/x",
            DeviceControlWatchdog.stripServiceFromList(
                "com.other/x:com.airko.app\$me.rerere.rikkahub.device.DeviceControlService",
                selfSet,
            ),
        )
    }

    @Test
    fun `join round trips`() {
        val list = listOf("a/b", "c/d")
        assertEquals(list, DeviceControlWatchdog.parseEnabledServiceList(DeviceControlWatchdog.joinEnabledServiceList(list)))
    }
}
