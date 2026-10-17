/*
 * 设备控制能力改写自 Reticle (https://github.com/Bumfuzzle-app/reticle)
 * Copyright 2026 Bumfuzzle, Apache License 2.0（已改写为 Kotlin，并裁剪掉 Shizuku shell、
 * 看门狗自动重开、文件槽命令循环、通知特效、视觉流程引擎等部分）。
 * 许可全文见仓库根目录 NOTICE 与 LICENSES/Apache-2.0.txt。
 */
package me.rerere.rikkahub.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * 设备控制的无障碍服务：只保留「眼 + 手」。
 *
 * 眼：读界面节点树、按文字/描述找控件、截屏
 * 手：点击/长按/滑动、输入文本、系统按键（返回/主页/最近任务/通知栏/锁屏）、打开应用
 *
 * 具体动作实现见 [me.rerere.rikkahub.device.deviceTap] 等同名文件里的扩展函数。
 */
class DeviceControlService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 跨应用读界面内容 + 拿多窗口信息（Reticle 的同款配置，XML 里也声明了一份）
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        DeviceControlState.onServiceUp(this)
        // 连接事件同时喂给看门狗（标记「确实授权过」+ 催一次检查）
        runCatching {
            DeviceControlWatchdog.onServiceConnected(this)
            // 已授权并连上：看门狗前台服务也拉起来（开关允许时）
            DeviceControlWatchdogService.ensure(this)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        detach("onUnbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach("onDestroy")
        super.onDestroy()
    }

    private fun detach(reason: String) {
        if (DeviceControlState.service === this) {
            DeviceControlState.onServiceDown(this, reason)
        }
        runCatching { DeviceControlWatchdog.onServiceDisconnected(this) }
    }
}
