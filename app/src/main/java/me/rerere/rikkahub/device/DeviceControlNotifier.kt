/*
 * 设备控制·通知与震动——能力对齐 Reticle 的 Notifier.java（Apache-2.0，Copyright 2026
 * Bumfuzzle；已改写为 Kotlin，去掉「眼线」常驻通知与彩蛋文案，report 改写到 Air-Ko 自己的
 * 设备控制常驻通知上）。
 *
 * 四种形态：
 *   普通通知：创建/更新/清除（同 id 重复发送＝更新同一条）；
 *   原子岛：vivo 原生通知 API 扩展参数 notification.superx.*（本地创建/更新/结束）——
 *     非 vivo 机型这些 extra 会被忽略，通知照常显示为普通通知（这正是「原子岛是 vivo 对普通
 *     通知的扩展」的含义，不是专属 API）；
 *   媒体样式：MediaSession + MediaStyle（借「原子随身听」上岛）；带 1 秒心跳刷新播放位置；
 *   实况/进度样式：反射 ProgressStyle + requestPromotedOngoing（API 36 才有；拿不到就退回普通进度条）。
 */
package me.rerere.rikkahub.device

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.R
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object DeviceControlNotifier {

    const val CH_STAT = "device_notify_stat"
    const val CH_TEST = "device_notify_test"
    const val CH_MEDIA = "device_notify_media"
    const val CH_LIVE = "device_notify_live"

    const val DEF_ID = 1
    private const val VIVO_TAG = "VIVO_SUPERX_TAG"

    private val channelsReady = java.util.concurrent.atomic.AtomicBoolean(false)
    private val ticker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "device-notify-ticker").apply { isDaemon = true }
    }

    // ------------------------------------------------------------------ 权限与渠道

    fun notificationsAllowed(context: Context): Boolean = runCatching {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun ensureChannels(context: Context) {
        if (channelsReady.get()) return
        runCatching {
            val nm = NotificationManagerCompat.from(context)
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(CH_STAT, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.notification_channel_device_tool))
                    .setShowBadge(false)
                    .build()
            )
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(CH_TEST, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.notification_channel_device_tool_test))
                    .build()
            )
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(CH_MEDIA, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.notification_channel_device_media))
                    .setShowBadge(false)
                    .build()
            )
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(CH_LIVE, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.notification_channel_device_live))
                    .setShowBadge(false)
                    .build()
            )
            channelsReady.set(true)
        }
    }

    /** 通知权限缺失时给模型的人话；工具层统一转成结构化错误。 */
    fun permissionError(): String =
        "notification permission not granted (allow notifications for Air-Ko in system settings)"

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun openApp(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            PendingIntent.getActivity(
                context, 0, launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    private fun baseCompat(context: Context, channel: String): NotificationCompat.Builder {
        ensureChannels(context)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_airko)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
        openApp(context)?.let { builder.setContentIntent(it) }
        return builder
    }

    // ------------------------------------------------------------------ 普通通知

    /** 发送/更新一条普通通知（同 id 重复发送＝更新）。 */
    fun send(context: Context, id: Int, title: String, content: String, ongoing: Boolean): String {
        if (!notificationsAllowed(context)) return "error: ${permissionError()}"
        return runCatching {
            val builder = baseCompat(context, CH_STAT)
                .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
                .setContentText(content)
                .setOngoing(ongoing)
                .setPriority(
                    if (ongoing) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT
                )
            manager(context).notify(id, builder.build())
            "ok id=$id title=\"$title\""
        }.getOrElse { "error: ${it.message}" }
    }

    /** 清除一条通知。 */
    fun cancel(context: Context, id: Int): String = runCatching {
        manager(context).cancel(id)
        "ok cleared id=$id"
    }.getOrElse { "error: ${it.message}" }

    // ------------------------------------------------------------------ 原子岛（vivo 扩展）

    /**
     * 原子岛形态 A：notification.superx.* 扩展参数。
     *
     * @param op 0 创建 / 1 更新 / 2 结束
     */
    fun island(
        context: Context,
        id: Int,
        op: Int,
        scene: String,
        title: String,
        content: String,
        ongoing: Boolean,
    ): String {
        if (!notificationsAllowed(context)) return "error: ${permissionError()}"
        return runCatching {
            val builder = baseCompat(context, CH_STAT)
                .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
                .setContentText(content)
                .setOngoing(ongoing)
            val extras = Bundle().apply {
                putInt("notification.superx.operation", op)
                putBoolean("notification.superx.showNotify", true)
                putInt("notification.superx.template", 1)
                putString("notification.superx.scene", scene.ifBlank { "HEALTH_REGISTER" })
                openApp(context)?.let { putParcelable("notification.superx.clickResp", it) }
                putBundle(
                    "notification.superx.baseInfos",
                    Bundle().apply {
                        putCharSequence("notification.superx.baseInfos.title", title.ifBlank { context.getString(R.string.app_name) })
                        putCharSequence("notification.superx.baseInfos.content", content)
                    },
                )
            }
            val notification = builder.build()
            notification.extras.putAll(extras)
            // 厂家 tag：vivo 按要求用专属 tag，别家照常按 id 显示
            manager(context).notify(VIVO_TAG, id, notification)
            val opText = when (op) {
                0 -> "created"
                1 -> "updated"
                else -> "ended"
            }
            "ok island $opText id=$id scene=\"${scene.ifBlank { "HEALTH_REGISTER" }}\" " +
                "(vivo-only extra; normal notification elsewhere)"
        }.getOrElse { "error: ${it.message}" }
    }

    // ------------------------------------------------------------------ 媒体样式（MediaSession）

    private var mediaSession: MediaSession? = null
    private var mediaId = DEF_ID
    private var mediaTitle: String = ""
    private var mediaText: String = ""
    private var mediaPlaying = false
    private var mediaBaseRealtime = 0L
    private var mediaBasePosition = 0L
    private var mediaTicker: ScheduledFuture<*>? = null
    private var mediaIdleTask: ScheduledFuture<*>? = null

    /** 媒体样式通知（供「原子随身听」上岛）；[playing] 决定是否开始位置心跳。 */
    @Synchronized
    fun media(context: Context, id: Int, title: String, content: String, playing: Boolean): String {
        if (!notificationsAllowed(context)) return "error: ${permissionError()}"
        return runCatching {
            val session = mediaSession ?: MediaSession(context, "Air-Ko").also {
                it.setCallback(object : MediaSession.Callback() {})
                it.isActive = true
                mediaSession = it
            }
            mediaId = id
            mediaTitle = title.ifBlank { context.getString(R.string.app_name) }
            mediaText = content.ifBlank { "待命" }
            mediaPlaying = playing
            mediaBaseRealtime = SystemClock.elapsedRealtime()
            mediaBasePosition = 0L
            session.setMetadata(
                android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, mediaTitle)
                    .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, mediaText)
                    .build()
            )
            pushMediaNotification(context, refreshPosition = true)
            if (playing) {
                startMediaTicker(context)
            } else {
                stopMediaTicker()
            }
            "ok media id=$id playing=$playing title=\"$mediaTitle\""
        }.getOrElse { "error: ${it.message}" }
    }

    /** 结束媒体样式（会话保留但通知撤掉、心跳停掉）。 */
    @Synchronized
    fun mediaEnd(context: Context, id: Int): String = runCatching {
        stopMediaTicker()
        mediaIdleTask?.cancel(false)
        mediaIdleTask = null
        mediaPlaying = false
        manager(context).cancel(id)
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        "ok media ended id=$id"
    }.getOrElse { "error: ${it.message}" }

    /** 每个工具调用都摸一下（Reticle 的 mediaTouch）：媒体通知里显示「正在执行 xxx」。 */
    @Synchronized
    fun onToolTouched(context: Context, toolName: String) {
        if (mediaSession == null) return
        runCatching {
            mediaText = "running $toolName"
            pushMediaNotification(context, refreshPosition = false)
            mediaIdleTask?.cancel(false)
            mediaIdleTask = ticker.schedule({
                runCatching { onIdle(context.applicationContext) }
            }, 20L, TimeUnit.SECONDS)
        }
    }

    @Synchronized
    private fun onIdle(context: Context) {
        if (mediaSession == null) return
        runCatching {
            mediaText = "待命"
            mediaPlaying = false
            stopMediaTicker()
            pushMediaNotification(context, refreshPosition = true)
        }
    }

    private fun startMediaTicker(context: Context) {
        stopMediaTicker()
        val app = context.applicationContext
        mediaTicker = ticker.scheduleAtFixedRate({
            runCatching {
                synchronized(this) {
                    if (mediaSession != null && mediaPlaying) {
                        pushMediaNotification(app, refreshPosition = true)
                    }
                }
            }
        }, 1L, 1L, TimeUnit.SECONDS)
    }

    private fun stopMediaTicker() {
        mediaTicker?.cancel(false)
        mediaTicker = null
    }

    @Suppress("DEPRECATION") // Notification.MediaStyle 在新 API 上被 media3 推荐替代，但功能始终可用
    private fun pushMediaNotification(context: Context, refreshPosition: Boolean) {
        val session = mediaSession ?: return
        if (!notificationsAllowed(context)) return
        ensureChannels(context)
        val position = if (refreshPosition) {
            mediaBasePosition + (SystemClock.elapsedRealtime() - mediaBaseRealtime)
        } else {
            mediaBasePosition
        }
        // 播放位置写进会话状态：部分 ROM 的「原子随身听」按它画进度条
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .setState(
                    if (mediaPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    position,
                    if (mediaPlaying) 1.0f else 0f,
                )
                .build()
        )
        val builder = Notification.Builder(context, CH_MEDIA)
            .setSmallIcon(R.drawable.ic_stat_airko)
            .setContentTitle(mediaTitle)
            .setContentText(mediaText)
            .setOngoing(mediaPlaying)
            .setOnlyAlertOnce(true)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
        openApp(context)?.let { builder.setContentIntent(it) }
        manager(context).notify(mediaId, builder.build())
    }

    // ------------------------------------------------------------------ 实况/进度样式（反射，API 36）

    /**
     * 实况型通知试验：ProgressStyle + requestPromotedOngoing（反射实现，编译期不依赖 API 36；
     * 拿不到的机型退回普通进度条——不假装成功）。
     */
    fun promoted(context: Context, id: Int, title: String, content: String, progress: Int): String {
        if (!notificationsAllowed(context)) return "error: ${permissionError()}"
        return runCatching {
            ensureChannels(context)
            val clamped = progress.coerceIn(0, 100)
            val builder = Notification.Builder(context, CH_LIVE)
                .setSmallIcon(R.drawable.ic_stat_airko)
                .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
                .setContentText(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            openApp(context)?.let { builder.setContentIntent(it) }

            var promotedOk = false
            try {
                Notification.Builder::class.java
                    .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                    .invoke(builder, true)
                promotedOk = true
            } catch (_: NoSuchMethodException) {
                // API < 36：没有这个方法，走普通通知 + 进度条
            } catch (t: Throwable) {
                // 个别 ROM 反射被拦
            }

            var styleOk = false
            try {
                val styleClass = Class.forName("android.app.Notification\$ProgressStyle")
                val style = styleClass.getConstructor().newInstance()
                styleClass.getMethod("setProgress", Int::class.javaPrimitiveType).invoke(style, clamped)
                Notification.Builder::class.java
                    .getMethod("setStyle", Class.forName("android.app.Notification\$Style"))
                    .invoke(builder, style)
                styleOk = true
            } catch (_: Throwable) {
                builder.setProgress(100, clamped, false)
            }

            val notification = builder.build()
            if (!promotedOk) {
                runCatching {
                    notification.extras.putBoolean("android.requestPromotedOngoing", true)
                }
            }
            manager(context).notify(id, notification)
            "ok promoted id=$id progress=$clamped" +
                (if (promotedOk) " (promoted)" else " (fallback: plain progress)") +
                (if (styleOk) "" else " (fallback: simple progress bar)")
        }.getOrElse { "error: ${it.message}" }
    }

    // ------------------------------------------------------------------ 汇报（写进设备控制常驻通知）

    /** report：把一句话写进设备控制看门狗的常驻通知正文（不新建第二条）。 */
    fun setReport(context: Context, text: String): String {
        return runCatching {
            DeviceControlWatchdogService.report(context, text)
            if (DeviceControlWatchdogService.isRunning()) {
                "ok report written into the device-control notification"
            } else {
                "ok report stored (the device-control notification is not running right now)"
            }
        }.getOrElse { "error: ${it.message}" }
    }

    // ------------------------------------------------------------------ 进度（下载用）

    /** 后台下载的进度通知（复用 live 渠道；不常驻、可划掉）。 */
    fun progress(context: Context, id: Int, title: String, content: String, percent: Int?): String {
        if (!notificationsAllowed(context)) return "error: ${permissionError()}"
        return runCatching {
            val builder = baseCompat(context, CH_LIVE)
                .setContentTitle(title)
                .setContentText(content)
                .setOngoing(percent != null)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            if (percent != null) {
                builder.setProgress(100, percent.coerceIn(0, 100), false)
            }
            manager(context).notify(id, builder.build())
            "ok"
        }.getOrElse { "error: ${it.message}" }
    }
}

/** 震动提醒（buzz）：只震动——不发声、不弹通知、不点亮屏幕。 */
fun deviceBuzz(context: Context, durationMs: Long): String {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        ?: return "error: vibrator unavailable on this device"
    return runCatching {
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        "ok buzzed ${durationMs}ms"
    }.getOrElse { "error: ${it.message}" }
}
