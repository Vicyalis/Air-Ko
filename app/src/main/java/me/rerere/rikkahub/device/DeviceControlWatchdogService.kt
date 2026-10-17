/*
 * 设备控制·看门狗前台服务——能力对齐 Reticle 的 WatchdogService.java（Apache-2.0，Copyright 2026
 * Bumfuzzle；已改写为 Kotlin）。
 *
 * 为什么需要它（Reticle 实测教训）：无障碍服务一被系统摘掉，进程就从「服务进程」掉成「空进程」，
 * 优先级一落，系统很快把它收走——看门狗线程随之死掉，于是「看着自己被关，却来不及去开」。
 * 挂上前台服务后，进程优先级接近前台应用，看门狗能撑到下一轮检查。
 *
 * 只做一件事：让看门狗活着（前台通知 + 启动看门狗 + 内存自保）；不碰别的设置。
 *
 * 「用户主动划掉 ＝ 停」：onTaskRemoved（只有从最近任务划掉才回调）→ 置「用户已停」并停手，
 * 不再自动回来（开机自启也不覆盖），直到用户手动打开 App。
 * 「被系统回收 ＝ 自愈」：不走 onTaskRemoved，照旧靠 START_STICKY 回来。
 */
package me.rerere.rikkahub.device

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.DEVICE_CONTROL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R

private const val TAG = "DeviceWatchdogService"

class DeviceControlWatchdogService : Service() {

    companion object {
        const val NOTIFICATION_ID = 2101
        const val ACTION_STOP = "me.rerere.rikkahub.action.DEVICE_CONTROL_WATCHDOG_STOP"

        @Volatile
        private var instance: DeviceControlWatchdogService? = null

        /** 外部（notify 工具的 report 参数/内存自保）写进常驻通知的最近一句。 */
        @Volatile
        private var lastReport: String? = null

        fun isRunning(): Boolean = instance != null

        /** 幂等拉起（用户没停手、开关允许时才起）。 */
        fun ensure(context: Context) {
            if (DeviceControlPrefs.userStopped(context)) return
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DeviceControlWatchdogService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, DeviceControlWatchdogService::class.java))
            }
        }

        /** 把外部一句话写进常驻通知正文（对应 Reticle「把进度写进既有那条通知正文」）。 */
        fun report(context: Context, text: String) {
            lastReport = text.replace('\n', ' ').take(180)
            instance?.refreshNote()
        }

        fun clearReport() {
            lastReport = null
        }

        fun refreshNote(context: Context) {
            instance?.refreshNote()
        }
    }

    private var started = false

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 用户从通知栏点「停止」：等价于设置页关掉看门狗（写回本地开关，UI 下次进页会读到）
            DeviceControlPrefs.setWatchdogEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (DeviceControlPrefs.userStopped(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startForegroundCompat()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 开关关着时：前台服务也不长驻（只把当前这次启动收干净）
        if (!DeviceControlPrefs.watchdogEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        DeviceControlWatchdog.start(this)
        DeviceControlMemGuard.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        DeviceControlWatchdog.stop(this)
        DeviceControlMemGuard.stop(this)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务划掉 = 用户明示停手（同 Reticle）：停看门狗与前台服务；
        // 开机/自启都不覆盖，直到用户手动打开 App。
        DeviceControlPrefs.setUserStopped(this, true)
        clearReport()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        DeviceControlMemGuard.onTrimMemory(this, level)
        super.onTrimMemory(level)
    }

    private fun refreshNote() {
        if (!started) return
        runCatching {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun startForegroundCompat(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        started = true
        true
    } catch (e: Exception) {
        // 个别 OEM ROM 会在系统侧拒绝 FGS 类型（同 WebServerService 的处置）
        Log.e(TAG, "Failed to start foreground service", e)
        false
    }

    private fun buildLaunchPendingIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    private fun buildNotification(): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, DeviceControlWatchdogService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, DEVICE_CONTROL_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_airko)
            .setContentTitle(getString(R.string.device_control_watchdog_service_title))
            .setContentText(noteText())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, getString(R.string.device_control_watchdog_service_stop), stopPendingIntent)
        buildLaunchPendingIntent()?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    private fun noteText(): String {
        val base = getString(R.string.device_control_watchdog_service_text)
        val mem = when (DeviceControlMemGuard.level) {
            DeviceControlMemGuard.Level.Normal -> getString(R.string.device_control_mem_normal)
            DeviceControlMemGuard.Level.Ease -> getString(R.string.device_control_mem_ease)
            DeviceControlMemGuard.Level.Trim -> getString(R.string.device_control_mem_trim)
            DeviceControlMemGuard.Level.Yield -> getString(R.string.device_control_mem_yield)
        }
        val report = lastReport
        return listOfNotNull(base, mem, report).joinToString(" · ")
    }
}
