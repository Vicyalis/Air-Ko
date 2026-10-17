/*
 * 设备控制能力改写自 Reticle (https://github.com/Bumfuzzle-app/reticle)
 * Copyright 2026 Bumfuzzle, Apache License 2.0（已改写为 Kotlin；连接态判据与轻探针移植自
 * A11yLink.java，去掉 Shizuku 路径）。
 * 许可全文见仓库根目录 NOTICE 与 LICENSES/Apache-2.0.txt。
 */
package me.rerere.rikkahub.device

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设备控制的三态。
 *
 * 无障碍有一个很会骗人的坑（Reticle 源码里的 A11yLink 就是为它写的）：进程被杀后系统会解绑服务，
 * 但**不会**把它从「已启用的无障碍服务」列表里移除。于是「系统设置里显示已开启」而实际完全不可用。
 * 所以状态必须把「已授权」和「已连接」当成两件事判断。
 */
enum class DeviceControlLevel {
    /** 用户没授权无障碍。 */
    Disabled,

    /** 在系统列表里已授权，但服务没连上（多数是被系统/杀进程收回，需要用户重新开关一次）。 */
    Stale,

    /** 已授权且已连接，可用。 */
    Ready,
}

data class DeviceControlStatus(
    val level: DeviceControlLevel = DeviceControlLevel.Disabled,
    /** 当前前台应用包名（连接时才有值）。 */
    val foregroundPackage: String? = null,
    /** 能否截屏：需要已连接 + Android 11（无障碍截屏 API 从 30 起）。 */
    val screenshotSupported: Boolean = false,
    /** 本次连接的时刻（0＝未连接过；状态页显示「已连接多久」）。 */
    val connectedAt: Long = 0L,
    /** 最近一次连接/掉线事件（诊断用；如 onServiceConnected/onUnbind）。 */
    val lastEvent: String = "",
) {
    val authorized: Boolean get() = level != DeviceControlLevel.Disabled
    val connected: Boolean get() = level == DeviceControlLevel.Ready
}

/**
 * 无障碍服务的单例状态出口。
 *
 * 服务由系统创建/销毁（不走 Koin），所以这里用 object 持有实例；UI、工具层都从这里取状态。
 */
object DeviceControlState {
    private val _status = MutableStateFlow(DeviceControlStatus())
    val status: StateFlow<DeviceControlStatus> = _status.asStateFlow()

    /** 当前已连接的服务实例，未连接时为 null。 */
    @Volatile
    var service: DeviceControlService? = null
        internal set

    /** 本次连接时刻。 */
    @Volatile
    var connectedAt: Long = 0L
        private set

    /** 最近一次连接/掉线事件。 */
    @Volatile
    var lastEvent: String = ""
        private set

    /** 「在册但没连上」开始的时刻（0＝当前正常）。 */
    @Volatile
    private var noConnSince: Long = 0L

    /** 轻探针结果：1 拿到画面、0 没拿到、-1 没探过；连续失败次数只作备注不当断开判据。 */
    @Volatile
    var probeResult: Int = -1
        private set

    @Volatile
    var probeFails: Int = 0
        private set

    /** 服务连上：刷新状态并记录事件。 */
    fun onServiceUp(service: DeviceControlService, why: String = "onServiceConnected") {
        this.service = service
        connectedAt = System.currentTimeMillis()
        noConnSince = 0L
        lastEvent = why
        refresh(service)
    }

    /** 服务掉线：刷新状态并开始计时（下次检查宽限期过了才催修）。 */
    fun onServiceDown(context: Context, why: String = "onUnbind") {
        if (service != null) {
            lastEvent = why
        }
        service = null
        if (noConnSince == 0L) {
            noConnSince = System.currentTimeMillis()
        }
        refresh(context)
    }

    /** 「在册但没连上」持续了多久（毫秒；当前正常时为 0）。 */
    fun noConnFor(): Long = if (noConnSince == 0L) 0L else System.currentTimeMillis() - noConnSince

    /**
     * 轻探针：拿得到活动窗口根节点吗？
     *
     * 拿不到**不直接**当断开判据（屏幕锁着/当前没有窗口也会拿不到），只作状态页备注。
     */
    fun probeNow(): Int {
        val svc = service ?: run {
            probeResult = 0
            probeFails++
            return 0
        }
        val ok = runCatching { svc.rootInActiveWindow != null }.getOrDefault(false)
        probeResult = if (ok) 1 else 0
        if (ok) {
            probeFails = 0
        } else {
            probeFails++
        }
        return probeResult
    }

    /** 观察一次「在册 vs 连接」，维护 noConnSince（看门狗每个周期调用）。 */
    fun observeLevel(context: Context): DeviceControlLevel {
        val status = refresh(context)
        if (status.connected) {
            noConnSince = 0L
        } else if (status.authorized && noConnSince == 0L) {
            noConnSince = System.currentTimeMillis()
        }
        return status.level
    }

    /** 重新计算并发布状态。UI 在 onResume、工具在调用前都可以刷一次。 */
    fun refresh(context: Context, foregroundPackage: String? = null): DeviceControlStatus {
        val connected = service != null
        val status = DeviceControlStatus(
            level = when {
                !context.isDeviceControlAuthorized() -> DeviceControlLevel.Disabled
                !connected -> DeviceControlLevel.Stale
                else -> DeviceControlLevel.Ready
            },
            foregroundPackage = foregroundPackage,
            screenshotSupported = connected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            connectedAt = connectedAt,
            lastEvent = lastEvent,
        )
        _status.value = status
        return status
    }
}

/** 服务是否出现在系统的已启用无障碍服务列表里（注意：不代表当前已连接）。 */
fun Context.isDeviceControlAuthorized(): Boolean {
    val component = ComponentName(this, DeviceControlService::class.java)
    val candidates = setOf(
        component.flattenToString(),
        component.flattenToShortString(),
        component.flattenToShortString().replace('/', '$'),
    )
    val enabledServices = runCatching {
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    }.getOrNull().orEmpty()
    val listed = enabledServices.split(':').any { entry ->
        candidates.any { it.equals(entry, ignoreCase = true) }
    } || enabledServices.replace('$', '/').split(':').any { entry ->
        candidates.any { it.equals(entry, ignoreCase = true) }
    }
    val masterOn = runCatching {
        Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
    }.getOrDefault(0) == 1
    return listed && masterOn
}
