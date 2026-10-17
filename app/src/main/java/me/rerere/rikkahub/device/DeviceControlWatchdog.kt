/*
 * 设备控制·无障碍看门狗——能力对齐 Reticle 的 A11yWatchdog.java（Apache-2.0，Copyright 2026
 * Bumfuzzle；已改写为 Kotlin，去掉 Shizuku content insert 兜底路径）。
 *
 * 为什么有它：vivo/一加等系统会定期或在打开系统「无障碍」设置页时，自动禁用第三方无障碍服务。
 * 应用内无法阻止。看门狗每 10 秒看一眼「本应用的无障碍还在不在系统的在册列表里」，不在就加回去
 * （只动本应用这一条，其它辅助服务原样保留）。
 *
 * 写回的唯一路径：Settings.Secure.putString（需要 WRITE_SECURE_SETTINGS——普通应用装不了，
 * 但可用 ADB 一次授予：adb shell pm grant <包名> android.permission.WRITE_SECURE_SETTINGS）。
 * 没有这条权限时**老实报告「开不回来」，只提示用户手动修一次**——不许假装成功。
 *
 * 独立于无障碍服务本身：自己起线程跑，即使无障碍被系统关掉，只要进程还在就能去救它；
 * 配合 DeviceControlWatchdogService（前台服务）在系统把进程降级回收之前活到下一轮检查。
 */
package me.rerere.rikkahub.device

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "DeviceControlWatchdog"

object DeviceControlWatchdog {

    /** 正常检查间隔（Reticle 的 a11yWatchdogIntervalDefault）。 */
    const val INTERVAL_MS = 10_000L

    /** 内存自保「缓」档的放慢间隔。 */
    const val EASED_INTERVAL_MS = 60_000L

    /** 「在册但没连上」的宽限期：短于此不折腾（系统绑定本来就有延迟）。 */
    private const val AUTO_REPAIR_GRACE_MS = 15_000L

    /** 自动修复的最小间隔，避免反复删/写名单。 */
    private const val AUTO_REPAIR_MIN_GAP_MS = 120_000L

    /** 修一次：写回后等连接的上限（Reticle 为 7 秒）。 */
    const val CONNECT_WAIT_MS = 7_000L

    enum class WatchdogNote {
        NotChecked,      // 还没检查过
        Off,             // 开关是关的
        InList,          // 在册且连接正常
        InListNoConn,    // 在册但没连上（宽限内或没权限修）
        RepairedLocal,   // 不在册 → 已写回并确认落地
        RepairFailed,    // 不在册且写不进（多是没权限；需要用户手动开）
        NotAuthorized,   // 用户从没授权过：不替用户做决定，不写系统设置
        NoRead,          // 连名单都读不到（极罕见）
    }

    enum class RepairResult {
        FixedNow,        // 当场修好（写回并确认连上）
        WroteNoConn,     // 写进去了但系统还没绑上（再等等看）
        WriteFailed,     // 写不进（没权限 / 系统拦下）
        NoRead,          // 读不到名单
        TooSoon,         // 自动修复的冷却期内（手动修一次不受此限）
        NotAuthorized,   // 从没授权过
    }

    @Volatile
    var intervalMs: Long = INTERVAL_MS

    @Volatile
    var paused: Boolean = false

    @Volatile
    var lastCheckAt: Long = 0L
        private set

    @Volatile
    var lastInList: Boolean = false
        private set

    @Volatile
    var lastNote: WatchdogNote = WatchdogNote.NotChecked
        private set

    @Volatile
    private var running = false

    @Volatile
    private var lastAutoRepairAt = 0L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "device-a11y-watchdog").apply { isDaemon = true }
    }

    /** 幂等启动（进程内重复调用无副作用）。 */
    @Synchronized
    fun start(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        scheduler.schedule({ tickAndReschedule(app) }, 400L, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stop(context: Context) {
        running = false
    }

    /** 无障碍掉线时催一下（Reticle 的 nudge：不等下一轮）。 */
    fun nudge(context: Context) {
        if (!running) return
        val app = context.applicationContext
        runCatching { scheduler.execute { runCatching { tick(app, respectSwitch = true) } } }
    }

    private fun tickAndReschedule(app: Context) {
        if (!running) return
        runCatching { tick(app, respectSwitch = true) }
            .onFailure { Log.w(TAG, "tick failed", it) }
        if (!running) return
        // 间隔以用户设置为准（默认 10 秒；「缓」档或暂停时用更慢的节奏）
        val configured = runCatching { DeviceControlPrefs.watchdogIntervalMs(app) }.getOrDefault(INTERVAL_MS)
        val delay = when {
            paused -> 5_000L
            intervalMs == EASED_INTERVAL_MS -> EASED_INTERVAL_MS
            else -> configured
        }.coerceIn(2_000L, 120_000L)
        runCatching {
            scheduler.schedule({ tickAndReschedule(app) }, delay, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * 检查一次并（在允许时）修正。
     *
     * @param respectSwitch false ＝ 忽略用户开关（自测/开机恢复等显式动作使用）
     */
    fun tick(context: Context, respectSwitch: Boolean): WatchdogNote {
        lastCheckAt = System.currentTimeMillis()
        DeviceControlPrefs.setLastCheckAt(context, lastCheckAt)

        if (respectSwitch && !DeviceControlPrefs.watchdogEnabled(context)) {
            return note(context, WatchdogNote.Off)
        }

        val self = selfComponents(context)
        val raw = readEnabledList(context) ?: return note(context, WatchdogNote.NoRead)
        val level = DeviceControlState.observeLevel(context)
        if (level == DeviceControlLevel.Ready) {
            DeviceControlState.probeNow()
        }

        if (hasServiceInList(raw, self)) {
            lastInList = true
            if (level != DeviceControlLevel.Ready &&
                DeviceControlState.noConnFor() >= AUTO_REPAIR_GRACE_MS &&
                !DeviceControlPrefs.userStopped(context)
            ) {
                when (repairInternal(context, force = false)) {
                    RepairResult.FixedNow -> return note(context, WatchdogNote.InList)
                    else -> return note(context, WatchdogNote.InListNoConn)
                }
            }
            return note(
                context,
                if (level == DeviceControlLevel.Ready) WatchdogNote.InList else WatchdogNote.InListNoConn,
            )
        }

        lastInList = false
        val everAuthorized = DeviceControlPrefs.everAuthorized(context)
        if (!everAuthorized) {
            // 用户从来没授权过：不替用户开无障碍
            return note(context, WatchdogNote.NotAuthorized)
        }

        val wanted = appendServiceToList(raw, self, canonicalSelf(context))
        val wrote = writeEnabledList(context, wanted)
        if (wrote) {
            // 等系统把名单变化落地，再读回验证（settings put 在个别 ROM 上会被拦，必须验证）
            sleepQuietly(1_200L)
            val verify = readEnabledList(context)
            if (verify != null && hasServiceInList(verify, self)) {
                DeviceControlPrefs.bumpWatchdogFixes(context)
                return note(context, WatchdogNote.RepairedLocal)
            }
        }
        return note(context, WatchdogNote.RepairFailed)
    }

    /**
     * 「修一次」：在册未连接 → 把本应用那条删掉再写回（制造一次真变化，逼系统重新绑定）；
     * 不在册 → 直接写回。两条路都需要 WRITE_SECURE_SETTINGS，做不到就如实返回 WriteFailed。
     */
    fun repair(context: Context, force: Boolean): RepairResult = repairInternal(context, force)

    private fun repairInternal(context: Context, force: Boolean): RepairResult {
        if (!canWriteSecureSettings(context)) return RepairResult.WriteFailed
        if (!DeviceControlPrefs.everAuthorized(context)) return RepairResult.NotAuthorized
        val now = System.currentTimeMillis()
        if (!force && now - lastAutoRepairAt < AUTO_REPAIR_MIN_GAP_MS) return RepairResult.TooSoon
        lastAutoRepairAt = now

        val self = selfComponents(context)
        val raw = readEnabledList(context) ?: return RepairResult.NoRead
        val ok = if (hasServiceInList(raw, self)) {
            val stripped = stripServiceFromList(raw, self)
            writeEnabledList(context, stripped)
            sleepQuietly(800L)
            writeEnabledList(context, appendServiceToList(stripped, self, canonicalSelf(context)))
        } else {
            writeEnabledList(context, appendServiceToList(raw, self, canonicalSelf(context)))
        }
        if (!ok) return RepairResult.WriteFailed

        val deadline = System.currentTimeMillis() + CONNECT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (DeviceControlState.observeLevel(context) == DeviceControlLevel.Ready) {
                DeviceControlPrefs.bumpWatchdogFixes(context)
                note(context, WatchdogNote.InList)
                return RepairResult.FixedNow
            }
            sleepQuietly(400L)
        }
        return if (DeviceControlState.observeLevel(context) == DeviceControlLevel.Ready) {
            DeviceControlPrefs.bumpWatchdogFixes(context)
            RepairResult.FixedNow
        } else {
            RepairResult.WroteNoConn
        }
    }

    /**
     * 看门狗自测（对应 Reticle 的 a11ytest）：故意把自己从名单里删掉，再看下一轮检查能不能修回来。
     *
     * 前提：确实有写权限（否则这测试本身就把用户的无障碍弄断且修不回来——Reticle 的口径也是
     * 「需要写权限才谈得上自测」）。测试完不论成败都会把名单恢复回原样。
     */
    data class SelfTestReport(
        val ok: Boolean,
        val code: String,
        val detail: String,
    )

    fun selfTest(context: Context): SelfTestReport {
        if (!canWriteSecureSettings(context)) {
            return SelfTestReport(
                false, "no_write_permission",
                "WRITE_SECURE_SETTINGS not granted; cannot self-test",
            )
        }
        val self = selfComponents(context)
        val raw = readEnabledList(context)
            ?: return SelfTestReport(false, "no_read", "cannot read ENABLED_ACCESSIBILITY_SERVICES")
        if (!hasServiceInList(raw, self)) {
            return SelfTestReport(false, "not_in_list", "the service is not in the enabled list")
        }
        val broken = stripServiceFromList(raw, self)
        if (!writeEnabledList(context, broken)) {
            return SelfTestReport(false, "write_failed", "could not write the broken list")
        }
        val note = runCatching { tick(context, respectSwitch = false) }
            .getOrElse { WatchdogNote.RepairFailed }
        val repairedNow = hasServiceInList(readEnabledList(context).orEmpty(), self)
        // 不论成败都尽力恢复（tick 成功时它已经写回；失败时这里兜底）
        if (!repairedNow) {
            writeEnabledList(context, raw)
        }
        return SelfTestReport(
            ok = repairedNow,
            code = if (repairedNow) "repaired" else "not_repaired",
            detail = "note=$note",
        )
    }

    fun canWriteSecureSettings(context: Context): Boolean = runCatching {
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    // ----------------------------------------------------------------------------------------
    // 系统设置读写
    // ----------------------------------------------------------------------------------------

    internal fun readEnabledList(context: Context): String? = runCatching {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
    }.getOrNull()

    internal fun writeEnabledList(context: Context, value: String): Boolean {
        if (!canWriteSecureSettings(context)) return false
        return runCatching {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                value,
            )
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1,
            )
        }.isSuccess
    }

    private fun note(context: Context, value: WatchdogNote): WatchdogNote {
        lastNote = value
        DeviceControlPrefs.setLastNote(context, value.name)
        if (value == WatchdogNote.InList && lastInList) {
            DeviceControlState.probeNow()
        }
        return value
    }

    // ----------------------------------------------------------------------------------------
    // 纯函数（单测覆盖）
    // ----------------------------------------------------------------------------------------

    /** 本应用的无障碍组件在名单里的几种可能写法。 */
    internal fun selfComponents(context: Context): Set<String> {
        val component = ComponentName(context, DeviceControlService::class.java)
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        return setOf(full, short, short.replace('/', '$'), full.replace('/', '$'))
    }

    /** 写回时使用的规范写法：包名/完整类名。 */
    internal fun canonicalSelf(context: Context): String =
        ComponentName(context, DeviceControlService::class.java).flattenToString()

    private fun normalizeEntry(value: String): String =
        value.trim().replace('$', '/').lowercase()

    internal fun parseEnabledServiceList(raw: String?): List<String> =
        raw.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }

    internal fun joinEnabledServiceList(entries: List<String>): String =
        entries.joinToString(":")

    internal fun hasServiceInList(raw: String?, candidates: Set<String>): Boolean {
        val normalized = candidates.map { normalizeEntry(it) }.toSet()
        return parseEnabledServiceList(raw).any { normalizeEntry(it) in normalized }
    }

    /** 追加本应用条目（已有等价写法就原样返回）。 */
    internal fun appendServiceToList(raw: String?, candidates: Set<String>, self: String): String {
        val entries = parseEnabledServiceList(raw)
        if (hasServiceInList(joinEnabledServiceList(entries), candidates)) {
            return joinEnabledServiceList(entries)
        }
        return joinEnabledServiceList(entries + self)
    }

    /** 删掉本应用条目（其它条目原样保留，含顺序）。 */
    internal fun stripServiceFromList(raw: String?, candidates: Set<String>): String {
        val normalized = candidates.map { normalizeEntry(it) }.toSet()
        val entries = parseEnabledServiceList(raw).filterNot { normalizeEntry(it) in normalized }
        return joinEnabledServiceList(entries)
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    /** 由 DeviceControlService 在连接/掉线时调用。 */
    internal fun onServiceConnected(context: Context) {
        DeviceControlPrefs.markEverAuthorized(context)
        DeviceControlPrefs.setUserStopped(context, false)
        nudge(context)
    }

    internal fun onServiceDisconnected(context: Context) {
        nudge(context)
    }

    /** 供状态页使用的快照（不读系统设置，纯内存 + 常量）。 */
    data class Snapshot(
        val note: WatchdogNote,
        val inList: Boolean,
        val fixes: Int,
        val lastFixAt: Long,
        val lastCheckAt: Long,
        val canWriteSecureSettings: Boolean,
        val intervalMs: Long,
        val paused: Boolean,
    )

    fun snapshot(context: Context): Snapshot = Snapshot(
        note = runCatching { WatchdogNote.valueOf(DeviceControlPrefs.lastNote(context) ?: "") }
            .getOrDefault(lastNote),
        inList = lastInList,
        fixes = DeviceControlPrefs.watchdogFixes(context),
        lastFixAt = DeviceControlPrefs.lastFixAt(context),
        lastCheckAt = if (lastCheckAt != 0L) lastCheckAt else DeviceControlPrefs.lastCheckAt(context),
        canWriteSecureSettings = canWriteSecureSettings(context),
        intervalMs = runCatching { DeviceControlPrefs.watchdogIntervalMs(context) }.getOrDefault(intervalMs),
        paused = paused,
    )
}
