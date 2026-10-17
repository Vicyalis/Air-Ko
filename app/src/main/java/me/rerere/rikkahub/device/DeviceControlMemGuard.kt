/*
 * 设备控制·内存自保——能力对齐 Reticle 的 MemGuard.java（Apache-2.0，Copyright 2026 Bumfuzzle；
 * 已改写为 Kotlin）。
 *
 * 为什么有它：设备控制是常驻能力（无障碍看门狗 + 前台守护服务），内存一紧它不能硬扛，要自己让路。
 * 三级让路：
 *   一级「缓」＝ 看门狗检查从 10 秒放慢到 60 秒（停掉不必要的定时刷新）；
 *   二级「收」＝ 释放自身缓存（文字识别识别器）＋ 前台通知里给人话一句；
 *   三级「让」＝ 极度紧张持续一段时间 → 暂停看门狗重试，只留最小保活。
 * 恢复：可用内存 > 12% 且持续 30 秒自动升回「正常」。
 *
 * 明令禁止（整机卡死的放大器，一条都不做）：不重启自己、不在紧张时疯狂重试。
 *
 * 判据：① 主接入点 = WatchdogService.onTrimMemory（系统主动喊话，最准最省电）；
 *       ② 轮询（45 秒）= ActivityManager 可用内存 + /proc/meminfo 的 MemAvailable/MemTotal。
 */
package me.rerere.rikkahub.device

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "DeviceControlMemGuard"

object DeviceControlMemGuard {

    enum class Level {
        /** 正常。 */
        Normal,

        /** 一级「缓」：检查放慢。 */
        Ease,

        /** 二级「收」：放掉缓存。 */
        Trim,

        /** 三级「让」：暂停重试，最小保活。 */
        Yield,
    }

    private const val POLL_MS = 45_000L
    private const val FIRST_DELAY_MS = 15_000L
    private const val RESTORE_FREE_RATIO = 0.12
    private const val RESTORE_HOLD_MS = 30_000L

    @Volatile
    var level: Level = Level.Normal
        private set

    /** 最近一次让路事件的人话说明（前台通知与状态页显示）。 */
    @Volatile
    var lastEvent: String = ""
        private set

    @Volatile
    var lastEventAt: Long = 0L
        private set

    @Volatile
    private var running = false

    @Volatile
    private var restoreCandidateSince = 0L

    /** 自测口：强制一个档位并保持（对应 Reticle 的槽位自测口）。 */
    @Volatile
    private var forcedLevelUntil: Long = 0L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "device-memguard").apply { isDaemon = true }
    }

    @Synchronized
    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        scheduler.schedule({ pollAndReschedule(app) }, FIRST_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stop(context: Context) {
        running = false
    }

    private fun pollAndReschedule(app: Context) {
        if (!running) return
        runCatching { evaluate(app) }.onFailure { Log.w(TAG, "evaluate failed", it) }
        if (!running) return
        runCatching { scheduler.schedule({ pollAndReschedule(app) }, POLL_MS, TimeUnit.MILLISECONDS) }
    }

    /** 系统主动喊话（WatchdogService.onTrimMemory）：最准的接入点。 */
    fun onTrimMemory(context: Context, trimLevel: Int) {
        val now = System.currentTimeMillis()
        if (now < forcedLevelUntil) return
        runCatching {
            when (trimLevel) {
                android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
                android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
                android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE,
                -> applyLevel(
                    context.applicationContext,
                    Level.Yield,
                    "system reported critical memory (trim=$trimLevel)",
                )

                android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                    applyLevel(
                        context.applicationContext,
                        Level.Trim,
                        "system reported low memory (trim=$trimLevel)",
                    )

                android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ->
                    applyLevel(
                        context.applicationContext,
                        Level.Ease,
                        "system reported moderate memory pressure (trim=$trimLevel)",
                    )

                else -> Unit
            }
        }.onFailure { Log.w(TAG, "onTrimMemory failed", it) }
    }

    /** 自测口：强制某档位 [holdMs] 毫秒（设置页「自测」按钮用）。 */
    fun forceForTest(context: Context, level: Level, holdMs: Long) {
        val app = context.applicationContext
        val wasForced = forceActive()
        forcedLevelUntil = System.currentTimeMillis() + holdMs.coerceIn(1_000L, 300_000L)
        applyLevel(app, level, "forced by self-test (hold=${holdMs}ms)")
        // 到点自动恢复：起一个一次性定时；若用户又点了一次，后一次生效
        scheduler.schedule({
            if (!forceActive()) {
                restoreTo(app)
            }
        }, holdMs + 300L, TimeUnit.MILLISECONDS)
        if (wasForced) {
            Log.i(TAG, "forceForTest re-armed: $level")
        }
    }

    private fun forceActive(): Boolean = System.currentTimeMillis() < forcedLevelUntil

    /** 轮询评估：只看「有明确信号」的紧张/恢复。 */
    private fun evaluate(context: Context) {
        if (forceActive()) return
        val ratio = availableRatio(context)
        if (level != Level.Normal) {
            if (ratio != null && ratio > RESTORE_FREE_RATIO) {
                val now = System.currentTimeMillis()
                if (restoreCandidateSince == 0L) restoreCandidateSince = now
                if (now - restoreCandidateSince >= RESTORE_HOLD_MS) {
                    restoreTo(context)
                }
            } else {
                restoreCandidateSince = 0L
            }
            return
        }
        // 正常档：只有「低内存」信号才升级；升级后的恢复交给上面的滞回
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val low = runCatching { am?.isLowRamDevice == true && am.memoryClass <= 0 }.getOrDefault(false)
        if (low && ratio != null && ratio < RESTORE_FREE_RATIO) {
            applyLevel(context, Level.Ease, "low memory detected (avail=${percent(ratio)})")
        } else if (ratio != null && ratio < 0.05) {
            applyLevel(context, Level.Trim, "very low memory (avail=${percent(ratio)})")
        }
    }

    private fun restoreTo(context: Context) {
        restoreCandidateSince = 0L
        applyLevel(context, Level.Normal, "memory recovered; back to normal")
    }

    private fun applyLevel(context: Context, target: Level, why: String) {
        if (level == target) return
        level = target
        lastEvent = why
        lastEventAt = System.currentTimeMillis()
        Log.i(TAG, "memory level -> $target ($why)")
        when (target) {
            Level.Normal -> {
                DeviceControlWatchdog.intervalMs = DeviceControlWatchdog.INTERVAL_MS
                DeviceControlWatchdog.paused = false
            }

            Level.Ease -> {
                DeviceControlWatchdog.intervalMs = DeviceControlWatchdog.EASED_INTERVAL_MS
                DeviceControlWatchdog.paused = false
            }

            Level.Trim -> {
                DeviceControlWatchdog.intervalMs = DeviceControlWatchdog.EASED_INTERVAL_MS
                DeviceControlWatchdog.paused = false
                // 二级「收」：放掉文字识别识别器（几十 MB 级，下次用自动重建）
                runCatching { DeviceOcrEngine.drop() }
                runCatching { System.gc() }
            }

            Level.Yield -> {
                DeviceControlWatchdog.intervalMs = DeviceControlWatchdog.EASED_INTERVAL_MS
                // 三级「让」：暂停看门狗重试，只留最小保活
                DeviceControlWatchdog.paused = true
                runCatching { DeviceOcrEngine.drop() }
                runCatching { System.gc() }
            }
        }
        // 让「它在让路」这件事看得见（前台通知正文带一句）
        runCatching { DeviceControlWatchdogService.refreshNote(context) }
    }

    /** 可用内存占比：优先 /proc/meminfo 的 MemAvailable/MemTotal，读不到退回 ActivityManager。 */
    internal fun availableRatio(context: Context): Double? {
        runCatching {
            val info = File("/proc/meminfo").readText()
            val available = Regex("MemAvailable:\\s+(\\d+) kB").find(info)?.groupValues?.get(1)?.toLongOrNull()
            val total = Regex("MemTotal:\\s+(\\d+) kB").find(info)?.groupValues?.get(1)?.toLongOrNull()
            if (available != null && total != null && total > 0) {
                return available.toDouble() / total.toDouble()
            }
        }
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            val memoryInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memoryInfo)
            if (memoryInfo.totalMem <= 0) null else memoryInfo.availMem.toDouble() / memoryInfo.totalMem.toDouble()
        }.getOrNull()
    }

    internal fun percent(ratio: Double): String = "${(ratio * 100).toInt()}%"

    /** 状态页一行显示（英文，模型/日志可读；中文文案在设置页本地化）。 */
    fun statusText(context: Context): String {
        val ratio = availableRatio(context)
        val base = when (level) {
            Level.Normal -> "normal"
            Level.Ease -> "eased (checks slowed to 60s)"
            Level.Trim -> "trimmed (caches released)"
            Level.Yield -> "yielded (retries paused)"
        }
        return if (ratio != null) "$base, avail=${percent(ratio)}" else base
    }
}
