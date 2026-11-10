package me.rerere.rikkahub.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentHashMap
import me.rerere.rikkahub.DEVICE_CONTROL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R

/**
 * 设备控制的「本次对话」守卫。
 *
 * - 第一次调用设备工具时显示常驻通知，并提供“强制暂停”按钮；
 * - 暂停只加入当前对话 id，不影响之后的新对话；
 * - 通知动作走同进程广播，不需要额外前台服务。
 */
object DeviceControlSessionGuard {
    const val ACTION_PAUSE = "me.rerere.rikkahub.action.DEVICE_CONTROL_PAUSE"
    const val EXTRA_CONVERSATION_ID = "conversation_id"
    private const val NOTIFICATION_ID = 2102

    private val pausedConversations = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var activeConversationId: String? = null

    fun ensureAllowed(context: Context, conversationId: String) {
        if (conversationId in pausedConversations) {
            throw IllegalStateException(
                context.getString(R.string.device_control_session_paused_error)
            )
        }
        activeConversationId = conversationId
        showNotification(context, conversationId, paused = false)
    }

    fun isPaused(conversationId: String): Boolean = conversationId in pausedConversations

    fun pause(context: Context, conversationId: String) {
        pausedConversations += conversationId
        if (activeConversationId == conversationId) {
            showNotification(context, conversationId, paused = true)
        }
    }

    fun end(context: Context, conversationId: String) {
        if (activeConversationId == conversationId) {
            activeConversationId = null
            runCatching {
                NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            }
        }
    }

    /** 供 device_do_chain 的步骤间检查：当前活动对话被暂停时立刻停下。 */
    fun throwIfCurrentPaused() {
        val current = activeConversationId ?: return
        if (current in pausedConversations) {
            throw IllegalStateException("device control paused for this conversation")
        }
    }

    private fun showNotification(context: Context, conversationId: String, paused: Boolean) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context, manager)
        val pauseIntent = PendingIntent.getBroadcast(
            context,
            conversationId.hashCode(),
            Intent(context, DeviceControlPauseReceiver::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, DEVICE_CONTROL_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_airko)
            .setContentTitle(
                context.getString(
                    if (paused) R.string.device_control_session_paused_title
                    else R.string.device_control_session_active_title
                )
            )
            .setContentText(
                context.getString(
                    if (paused) R.string.device_control_session_paused_text
                    else R.string.device_control_session_active_text
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (!paused) {
            builder.addAction(
                0,
                context.getString(R.string.device_control_session_pause),
                pauseIntent,
            )
        }
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    private fun ensureChannel(context: Context, manager: NotificationManagerCompat) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            DEVICE_CONTROL_NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.notification_channel_device_control),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }
}

class DeviceControlPauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DeviceControlSessionGuard.ACTION_PAUSE) return
        val conversationId = intent.getStringExtra(DeviceControlSessionGuard.EXTRA_CONVERSATION_ID)
            ?: return
        DeviceControlSessionGuard.pause(context, conversationId)
    }
}
