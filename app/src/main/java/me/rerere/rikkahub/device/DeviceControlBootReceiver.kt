/*
 * 设备控制·开机自启与版本更新重启——能力对齐 Reticle 的 BootReceiver.java（Apache-2.0，
 * Copyright 2026 Bumfuzzle；已改写为 Kotlin，去掉 Shizuku 依赖）。
 *
 * 干什么：手机开机后，若开关是开的（默认关）——① 把本应用的无障碍写回系统在册列表
 * （复用看门狗的 tick）；② 幂等拉起看门狗前台服务。
 *
 * 三个如实前提（设置页选项旁也写了）：
 *   ① 这条通道依赖系统写权限（WRITE_SECURE_SETTINGS，可用 ADB 授予）；没有权限时写回会失败，
 *      只剩「拉起看门狗等下一次机会」与提示用户手动开；
 *   ② 国产 ROM（vivo 等）还有第二道闸：要在系统里放行「自启动/允许后台运行」，否则这条开机
 *      广播根本收不到（设置页给了「去系统自启动设置」的入口）；
 *   ③ 用户若曾从最近任务划掉过 App（＝明确表示「停了」），开机也不覆盖它。
 *
 * 开机时不许弹界面（Android 10+ 后台禁启 Activity）——本接收器只起服务、只写设置，不弹窗。
 */
package me.rerere.rikkahub.device

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

private const val TAG = "DeviceControlBootReceiver"

class DeviceControlBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pendingResult = goAsync()
        Thread {
            try {
                handle(context.applicationContext, action)
            } catch (t: Throwable) {
                Log.w(TAG, "boot handling failed: $action", t)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun handle(context: Context, action: String) {
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            -> {
                if (DeviceControlPrefs.userStopped(context)) return
                if (!DeviceControlPrefs.bootAutoStartEnabled(context)) return
                // ① 尝试把无障碍写回（没权限就只会失败，tick 里会如实记录）
                runCatching { DeviceControlWatchdog.tick(context, respectSwitch = false) }
                    .onFailure { Log.w(TAG, "boot tick failed", it) }
                // ② 拉起看门狗（MCP 时代之后是拉起守护服务本身）
                if (context.isDeviceControlAuthorized() || DeviceControlPrefs.everAuthorized(context)) {
                    DeviceControlWatchdogService.ensure(context)
                }
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // 覆盖安装后服务会重启：开关开着且已授权就一把拉回来（不需要开机自启开关）
                if (DeviceControlPrefs.userStopped(context)) return
                if (!DeviceControlPrefs.watchdogEnabled(context)) return
                if (context.isDeviceControlAuthorized() || DeviceControlPrefs.everAuthorized(context)) {
                    DeviceControlWatchdogService.ensure(context)
                }
            }
        }
    }

    companion object {
        /**
         * 常见国产 ROM 的「自启动管理」页面。逐个试，全不成就退到本应用的详情页
         * （用户能在那里找到「允许后台运行/自启动」）。
         */
        private val AUTO_START_COMPONENTS = listOf(
            // 小米/红米
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // 华为/荣耀
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            // OPPO / realme / 一加
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            // vivo / iQOO
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            // 三星
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            // 魅族
            "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
            "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC",
        )

        /** 打开系统「自启动管理」（找不到就打开应用详情页）。返回是否打开了专门的页面。 */
        fun openAutoStartSettings(context: Context): Boolean {
            for ((pkg, cls) in AUTO_START_COMPONENTS) {
                val intent = Intent().apply {
                    component = ComponentName(pkg, cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (runCatching { context.startActivity(intent) }.isSuccess) return true
            }
            val detail = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(detail) }
            return false
        }
    }
}
