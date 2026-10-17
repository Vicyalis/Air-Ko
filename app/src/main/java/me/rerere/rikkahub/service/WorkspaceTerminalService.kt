package me.rerere.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.WORKSPACE_TERMINAL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import org.koin.android.ext.android.inject

/**
 * 终端会话的保活前台服务。
 *
 * 会话本身由 [WorkspaceTerminalSessionManager] 单例持有，服务不持有任何会话，
 * 只负责把进程提到前台优先级：否则用户切走应用后进程随时可能被系统回收，
 * PTY 一起断掉（「用久了自动断联」的真正根因）。
 *
 * 注意这是**预防**而不是**复活**：进程真被系统杀掉时，[com.termux.terminal.TerminalSession]
 * 里的 PTY 文件描述符随之消失，无法恢复，所以用 `START_NOT_STICKY` —— 拉起一个没有会话的
 * 前台服务只会留下一条骗人的常驻通知。
 */
class WorkspaceTerminalService : Service() {

    companion object {
        private const val TAG = "WorkspaceTerminalService"
        const val ACTION_START = "me.rerere.rikkahub.action.WORKSPACE_TERMINAL_START"
        const val ACTION_CLOSE_ALL = "me.rerere.rikkahub.action.WORKSPACE_TERMINAL_CLOSE_ALL"
        const val NOTIFICATION_ID = 2005

        /**
         * 幂等启动，只要还有会话在跑就会被调用。此时应用通常刚被用户操作过，
         * 处于允许启动前台服务的状态。若系统仍拒绝（后台限制），会话照常工作，
         * 只是失去保活——下一次会话数变化时会再试。
         */
        fun ensureStarted(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WorkspaceTerminalService::class.java).setAction(ACTION_START),
                )
            }.onFailure {
                Log.w(TAG, "Cannot start foreground service now, sessions will not be kept alive", it)
            }
        }
    }

    private val sessionManager: WorkspaceTerminalSessionManager by inject()
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineName("WorkspaceTerminalService")
    )
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_ALL) {
            serviceScope.launch { sessionManager.closeAllSessions() }
            return START_NOT_STICKY
        }

        if (!startForegroundCompat()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startObservingSessions()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun startObservingSessions() {
        if (observerJob != null) return
        observerJob = serviceScope.launch {
            sessionManager.activeSessionCount.collect { sessionCount ->
                if (sessionCount > 0) {
                    updateNotification(buildRunningNotification(sessionCount))
                } else {
                    // 没有会话就没什么要保活的了，撤掉通知并结束自己
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun startForegroundCompat(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildStartingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildStartingNotification())
            }
            true
        } catch (e: Exception) {
            // 部分 OEM ROM（如 realme UI / ColorOS）会在系统侧拒绝 FGS 类型权限，
            // 即使 Manifest 已声明 FOREGROUND_SERVICE_SPECIAL_USE 也会抛 SecurityException
            Log.e(TAG, "Failed to start foreground service", e)
            false
        }
    }

    private fun updateNotification(notification: Notification) {
        // API 33+ 未授予 POST_NOTIFICATIONS 时通知不会显示，但不该让会话保活因此崩掉
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }.onFailure {
            Log.w(TAG, "Failed to update terminal notification", it)
        }
    }

    private fun buildLaunchPendingIntent() = PendingIntent.getActivity(
        this,
        0,
        packageManager.getLaunchIntentForPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildStartingNotification() = NotificationCompat.Builder(
        this,
        WORKSPACE_TERMINAL_NOTIFICATION_CHANNEL_ID
    )
        .setSmallIcon(R.drawable.ic_stat_airko)
        .setContentTitle(getString(R.string.notification_workspace_terminal_running))
        .setContentIntent(buildLaunchPendingIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun buildRunningNotification(sessionCount: Int): Notification {
        val closeAllIntent = Intent(this, WorkspaceTerminalService::class.java).apply {
            action = ACTION_CLOSE_ALL
        }
        val closeAllPendingIntent = PendingIntent.getService(
            this,
            0,
            closeAllIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, WORKSPACE_TERMINAL_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_airko)
            .setContentTitle(getString(R.string.notification_workspace_terminal_running))
            .setContentText(
                getString(R.string.notification_workspace_terminal_content, sessionCount)
            )
            .setContentIntent(buildLaunchPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .addAction(
                0,
                getString(R.string.notification_workspace_terminal_stop),
                closeAllPendingIntent
            )
            .build()
    }
}
