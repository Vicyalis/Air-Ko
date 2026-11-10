/*
 * 设备控制·动作自检（对应 Reticle 的 acttest 自证流程：只动 Air-Ko 自己的自测靶窗口）。
 *
 * 工具 device_acttest 与设置页/onboarding 的「运行自检」按钮共用这一份实现：
 * 在自家小窗上跑一遍 tap／长按／输入／滑动，然后读窗口自己的计数回执；
 */
package me.rerere.rikkahub.device

import android.accessibilityservice.AccessibilityService
import android.content.Context
import kotlinx.coroutines.delay

/** 自检结果：窗口没起来时 windowShown=false，其余字段无意义。 */
data class DeviceSelfTestResult(
    val ok: Boolean,
    val windowShown: Boolean,
    val tapCount: Int,
    val typed: String,
    val typedExpected: String,
    val steps: List<String>,
    val message: String,
)

/** 跑一遍动作自检；约 6 秒，会短暂占用前台（只在自家窗口上动手）。 */
internal suspend fun AccessibilityService.runActSelfTest(context: Context): DeviceSelfTestResult {
    DeviceSelfTestActivity.open(context)

    // 部分 ROM 会延迟/拦截后台启动 Activity：先等 1.5 秒，没起来就再补一次启动
    var waited = 0L
    while (!DeviceSelfTestActivity.ready && waited < 1_500L) {
        delay(100L)
        waited += 100L
    }
    if (!DeviceSelfTestActivity.ready) {
        DeviceSelfTestActivity.open(context)
    }

    val readyDeadline = System.currentTimeMillis() + 5_000L
    while (!DeviceSelfTestActivity.ready && System.currentTimeMillis() < readyDeadline) {
        delay(100L)
    }
    if (!DeviceSelfTestActivity.ready) {
        return DeviceSelfTestResult(
            ok = false,
            windowShown = false,
            tapCount = 0,
            typed = "",
            typedExpected = DeviceSelfTestActivity.TYPED_EXPECTED,
            steps = emptyList(),
            message = "the self-test window did not appear in time (the system may block " +
                "background activity starts; keep Air-Ko in the foreground and try again)",
        )
    }
    val steps = mutableListOf<String>()
    val buttonX = DeviceSelfTestActivity.btnX.toFloat()
    val buttonY = DeviceSelfTestActivity.btnY.toFloat()
    return try {
        val tapOk = deviceTap(buttonX, buttonY)
        steps += "tap=${okText(tapOk)}"
        delay(300L)
        val pressOk = deviceTap(buttonX, buttonY, 900L)
        steps += "long_press=${okText(pressOk)}"
        delay(300L)
        val typeOutcome = deviceTypeText(DeviceSelfTestActivity.TYPED_EXPECTED)
        steps += "input=${okText(typeOutcome.ok)}"
        delay(300L)
        val swipeOk = deviceSwipe(
            DeviceSelfTestActivity.width / 2f,
            DeviceSelfTestActivity.top + 30f,
            DeviceSelfTestActivity.width / 2f,
            DeviceSelfTestActivity.top + 130f,
            300L,
        )
        steps += "swipe=${okText(swipeOk)}"
        delay(250L)

        val taps = DeviceSelfTestActivity.tapCount
        val typed = DeviceSelfTestActivity.typed
        val allOk = tapOk && pressOk && typeOutcome.ok && swipeOk &&
            taps >= 1 && typed == DeviceSelfTestActivity.TYPED_EXPECTED
        if (!allOk) {
            // 只看到「tap=ok」却失败时，多半是坐标没打中，把计数一并列出来
            steps += "taps=$taps"
            steps += "typed=\"${typed.take(24)}\""
        }
        DeviceSelfTestResult(
            ok = allOk,
            windowShown = true,
            tapCount = taps,
            typed = typed,
            typedExpected = DeviceSelfTestActivity.TYPED_EXPECTED,
            steps = steps.toList(),
            message = if (allOk) {
                "device control verified end to end (tap, long-press, input, swipe)"
            } else {
                "self-test finished with mismatches; check steps/tap_count/typed"
            },
        )
    } finally {
        // 不再用无障碍「返回」关窗：那一步在部分机型会把 Air-Ko 一起退掉。
        // 由测试靶自己 finish，只关闭自测窗口。
        DeviceSelfTestActivity.close()
    }
}

internal fun okText(ok: Boolean): String = if (ok) "ok" else "FAILED"
