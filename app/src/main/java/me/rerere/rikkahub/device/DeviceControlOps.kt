/*
 * 设备控制能力改写自 Reticle (https://github.com/Bumfuzzle-app/reticle)
 * Copyright 2026 Bumfuzzle, Apache License 2.0（已改写为 Kotlin；去掉了 Shizuku 兜底路径）。
 * 许可全文见仓库根目录 NOTICE 与 LICENSES/Apache-2.0.txt。
 */
package me.rerere.rikkahub.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

private const val TAG = "DeviceControlOps"

/** 回传给模型的截图上限（超过就继续降质），与 Reticle 一致。 */
const val DEVICE_SCREENSHOT_MAX_BYTES = 700 * 1024

/** 界面树回传给模型的字符预算，防止超大页面把上下文吃掉。 */
private const val UI_TREE_CHAR_BUDGET = 20_000

// ---------------------------------------------------------------------------------------------
// 手势（点击 / 长按 / 滑动）
// ---------------------------------------------------------------------------------------------

/**
 * 派发一条单笔画手势。
 *
 * 系统对极短手势会丢帧，所以点击也给 50ms；回调是异步的，用 [withTimeoutOrNull] 兜住
 * 「回调一直不来」的情况（Reticle 用的是 CountDownLatch 阻塞等待，这里换成挂起）。
 */
private suspend fun AccessibilityService.deviceDispatch(
    path: Path,
    durationMs: Long,
    timeoutMs: Long = 8_000L,
): Boolean = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine { cont ->
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = runCatching {
            dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
        }.onFailure { Log.w(TAG, "dispatchGesture failed", it) }.getOrDefault(false)
        // dispatchGesture 返回 false 表示这次派发没被接受（比如用户正在触摸），直接给失败
        if (!accepted && cont.isActive) cont.resume(false)
    }
} ?: false

suspend fun AccessibilityService.deviceTap(
    x: Float,
    y: Float,
    durationMs: Long = 50L,
): Boolean = deviceDispatch(Path().apply { moveTo(x, y) }, durationMs.coerceIn(20L, 60_000L))

suspend fun AccessibilityService.deviceSwipe(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    durationMs: Long = 300L,
): Boolean = deviceDispatch(
    Path().apply {
        moveTo(startX, startY)
        lineTo(endX, endY)
    },
    durationMs.coerceIn(50L, 20_000L),
)

// ---------------------------------------------------------------------------------------------
// 系统按键（能做的只有全局动作；电源/音量键系统不给第三方）
// ---------------------------------------------------------------------------------------------

enum class DeviceGlobalKey(val label: String) {
    Back("back"),
    Home("home"),
    Recents("recents"),
    Notifications("notifications"),
    QuickSettings("quick_settings"),
    LockScreen("lock"),
    ;

    companion object {
        fun from(raw: String): DeviceGlobalKey? =
            entries.firstOrNull { it.label == raw.trim().lowercase() }
    }
}

fun AccessibilityService.deviceGlobalKey(key: DeviceGlobalKey): Boolean {
    val action = when (key) {
        DeviceGlobalKey.Back -> AccessibilityService.GLOBAL_ACTION_BACK
        DeviceGlobalKey.Home -> AccessibilityService.GLOBAL_ACTION_HOME
        DeviceGlobalKey.Recents -> AccessibilityService.GLOBAL_ACTION_RECENTS
        DeviceGlobalKey.Notifications -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        DeviceGlobalKey.QuickSettings -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        DeviceGlobalKey.LockScreen ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            } else {
                null
            }
    } ?: return false
    return performGlobalAction(action)
}

// ---------------------------------------------------------------------------------------------
// 界面节点树
// ---------------------------------------------------------------------------------------------

data class DeviceUiNode(
    val index: Int,
    val depth: Int,
    val className: String,
    val text: String?,
    val description: String?,
    val hint: String?,
    val viewId: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    /** 自身不可点但可点祖先已入列时，指向那个祖先的序号（模型据此知道点这里会触发谁）。 */
    val tapTargetIndex: Int?,
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()
}

/** 只收「有意义」的节点：有文字/描述/提示，或本身可点/可输/可滚/可勾选。 */
private fun isMeaningfulNode(node: AccessibilityNodeInfo): Boolean =
    !node.text.isNullOrBlank() ||
        !node.contentDescription.isNullOrBlank() ||
        !node.hintText.isNullOrBlank() ||
        node.isClickable || node.isLongClickable || node.isEditable ||
        node.isScrollable || node.isCheckable ||
        node.className?.contains("WebView") == true

/**
 * 广度优先抓当前活动窗口的节点树。
 *
 * 无障碍节点是**快照**，用完不回收（API 33 起 recycle 已是空操作）；深度/节点数都有上限，
 * 避免超大列表把内存和上下文一起吃掉。
 */
fun AccessibilityService.deviceSnapshotUi(maxDepth: Int = 12, maxNodes: Int = 300): List<DeviceUiNode> {
    val root = runCatching { rootInActiveWindow }.getOrNull() ?: return emptyList()
    val nodes = ArrayList<DeviceUiNode>()
    val queue = ArrayDeque<Array<Any>>()
    queue.addLast(arrayOf(root, 0, -1))
    var visited = 0
    val visitLimit = maxNodes * 8

    while (queue.isNotEmpty() && nodes.size < maxNodes && visited < visitLimit) {
        val current = queue.removeFirst()
        val node = current[0] as AccessibilityNodeInfo
        val depth = current[1] as Int
        var clickableAncestor = current[2] as Int
        visited++

        val visible = node.isVisibleToUser ||
            !node.text.isNullOrBlank() ||
            !node.contentDescription.isNullOrBlank()

        if (visible && isMeaningfulNode(node)) {
            val index = nodes.size
            nodes.add(
                DeviceUiNode(
                    index = index,
                    depth = depth,
                    className = node.className?.toString().orEmpty(),
                    text = node.text?.toString()?.takeIf { it.isNotBlank() },
                    description = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                    hint = node.hintText?.toString()?.takeIf { it.isNotBlank() },
                    viewId = node.viewIdResourceName,
                    bounds = Rect().also { node.getBoundsInScreen(it) },
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    checkable = node.isCheckable,
                    checked = runCatching { node.isChecked }.getOrDefault(false),
                    enabled = node.isEnabled,
                    tapTargetIndex = if (!node.isClickable && clickableAncestor >= 0 && depth > 0) {
                        clickableAncestor
                    } else {
                        null
                    },
                )
            )
            if (node.isClickable) clickableAncestor = index
        }

        if (depth < maxDepth) {
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                queue.addLast(arrayOf(child, depth + 1, clickableAncestor))
            }
        }
    }
    return nodes
}

/** 按文字/描述/控件 id 找节点（自己的实现，顺带带出可点祖先信息）。 */
fun AccessibilityService.deviceFindNodes(
    query: String,
    byId: Boolean = false,
    maxResults: Int = 20,
): List<DeviceUiNode> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()
    return deviceSnapshotUi(maxDepth = 24, maxNodes = 1_200)
        .filter { node ->
            if (byId) {
                node.viewId?.substringAfterLast('/')?.lowercase()?.contains(needle) == true
            } else {
                node.text?.lowercase()?.contains(needle) == true ||
                    node.description?.lowercase()?.contains(needle) == true ||
                    node.hint?.lowercase()?.contains(needle) == true
            }
        }
        .take(maxResults)
}

private fun String.abbreviateForTree(max: Int): String =
    if (length <= max) this else take(max - 1) + "…"

fun DeviceUiNode.formatForModel(): String = buildString {
    append("  ".repeat(depth.coerceAtMost(8)))
    append('#').append(index).append(' ')
    append(className.substringAfterLast('.'))
    text?.let { append(" \"").append(it.abbreviateForTree(60)).append('"') }
    description?.let { append(" desc=\"").append(it.abbreviateForTree(60)).append('"') }
    hint?.let { append(" hint=\"").append(it.abbreviateForTree(24)).append('"') }
    viewId?.substringAfterLast('/')?.let { append(" id=").append(it) }
    val flags = buildList {
        if (editable) add("editable")
        if (clickable) add("clickable")
        if (longClickable) add("long-clickable")
        if (scrollable) add("scrollable")
        if (checkable) add(if (checked) "checked" else "checkable")
        if (!enabled) add("disabled")
    }
    if (flags.isNotEmpty()) append(" [").append(flags.joinToString(",")).append(']')
    tapTargetIndex?.let { append(" tap=#").append(it) }
    append(" @(").append(centerX).append(',').append(centerY).append(')')
}

fun List<DeviceUiNode>.formatForModel(): String {
    if (isEmpty()) return ""
    val builder = StringBuilder()
    for (node in this) {
        val line = node.formatForModel()
        if (builder.length + line.length + 1 > UI_TREE_CHAR_BUDGET) {
            builder.append("…（已达字符上限，节点被截断，可用 max_nodes 调大或用 device_find 精确查找）")
            break
        }
        builder.append(line).append('\n')
    }
    return builder.toString().trimEnd()
}

/** 当前前台应用的包名（读到什么算什么，读不到给 null）。 */
fun AccessibilityService.deviceForegroundPackage(): String? =
    runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

/** 把包名换成人类可读的名字，拿不到就退回包名。 */
fun Context.deviceAppLabel(packageName: String): String = runCatching {
    val info = packageManager.getApplicationInfo(packageName, 0)
    packageManager.getApplicationLabel(info).toString()
}.getOrDefault(packageName)

// ---------------------------------------------------------------------------------------------
// 输入文本
// ---------------------------------------------------------------------------------------------

data class DeviceTypeOutcome(
    val ok: Boolean,
    val message: String,
    val applied: String? = null,
)

private fun isEditableNode(node: AccessibilityNodeInfo): Boolean =
    node.isEditable ||
        node.className?.contains("EditText") == true ||
        node.className?.contains("AutoCompleteTextView") == true

internal fun findEditableTarget(service: AccessibilityService): AccessibilityNodeInfo? {
    runCatching { service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
        .getOrNull()?.let { if (isEditableNode(it)) return it }
    val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
    if (isEditableNode(root)) return root
    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (isEditableNode(it)) return it }
    // 有些页面焦点在输入框以外，退化为深度优先找第一个可编辑节点
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var visited = 0
    while (stack.isNotEmpty() && visited < 400) {
        val node = stack.removeLast()
        visited++
        if (isEditableNode(node)) return node
        for (i in 0 until node.childCount) {
            runCatching { node.getChild(i) }.getOrNull()?.let { stack.addLast(it) }
        }
    }
    return null
}

/**
 * 往当前聚焦的输入框写入文本。
 *
 * Reticle 踩过的坑，这里保留了两条：① `ACTION_SET_TEXT` 返回 true 也可能没写进去（部分 ROM/应用），
 * 必须**重新取节点**读回校验；② 校验要用精确比较而不是 contains。
 */
fun AccessibilityService.deviceTypeText(text: String, append: Boolean = false): DeviceTypeOutcome {
    val target = findEditableTarget(this)
        ?: return DeviceTypeOutcome(false, "没有找到可输入的输入框，请先点击目标输入框再输入")
    val current = target.text?.toString().orEmpty()
    val expected = if (append) current + text else text
    val args = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expected)
    }
    val accepted = runCatching {
        target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }.getOrDefault(false)
    if (!accepted) {
        return DeviceTypeOutcome(false, "输入框拒绝了写入（可能是自绘控件，需要改用坐标点击方案）")
    }
    val fresh = findEditableTarget(this)?.text?.toString()
    return when {
        fresh == null -> DeviceTypeOutcome(true, "已写入，但读不回内容（无法校验）", expected)
        fresh == expected -> DeviceTypeOutcome(true, "输入成功", expected)
        else -> DeviceTypeOutcome(
            false,
            "写入后读回内容不一致（实际为 \"${fresh.abbreviateForTree(40)}\"）",
            fresh,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 截屏（Android 11 起，无障碍自带的 takeScreenshot）
// ---------------------------------------------------------------------------------------------

@Volatile
private var lastScreenshotAtMs = 0L

suspend fun AccessibilityService.deviceScreenshotBitmap(): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    // 连续截图太快系统会拒绝（Reticle 用 350ms 节流），这里同样节流一下
    val now = System.currentTimeMillis()
    val wait = 350L - (now - lastScreenshotAtMs)
    if (wait > 0) delay(wait)
    lastScreenshotAtMs = System.currentTimeMillis()

    return withTimeoutOrNull(6_000L) {
        suspendCancellableCoroutine { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val bitmap = runCatching {
                            Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                // 硬件位图不能直接压缩成 JPEG，先拷成软件位图
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }.onFailure { Log.w(TAG, "wrapHardwareBuffer failed", it) }.getOrNull()
                        runCatching { screenshot.hardwareBuffer.close() }
                        if (cont.isActive) cont.resume(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed: $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }
    }
}

/** 缩到 [maxWidth] 宽并压成 JPEG，超预算就继续降质（模型只看得清大概，不必给原图）。 */
fun Bitmap.toScaledJpegBytes(
    maxWidth: Int = 720,
    quality: Int = 70,
    maxBytes: Int = DEVICE_SCREENSHOT_MAX_BYTES,
): ByteArray {
    val scaled = if (width > maxWidth) {
        val ratio = maxWidth.toFloat() / width
        Bitmap.createScaledBitmap(this, maxWidth, (height * ratio).toInt().coerceAtLeast(1), true)
    } else {
        this
    }
    var q = quality
    var bytes: ByteArray
    do {
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, q, stream)
        bytes = stream.toByteArray()
        q -= 15
    } while (bytes.size > maxBytes && q >= 20)
    return bytes
}

// ---------------------------------------------------------------------------------------------
// 等待与滚动（Reticle 的 wait_text / wait_stable / scroll_to_text 的等价实现）
// ---------------------------------------------------------------------------------------------

/** 等界面稳定：连续 [stableMs] 毫秒内节点树签名不变就算稳定。自绘界面会立刻"稳定"，需配合 find/wait 使用。 */
internal fun AccessibilityService.deviceTreeSignature(): Int =
    deviceSnapshotUi(maxDepth = 10, maxNodes = 200)
        .joinToString("\u0001") { node ->
            "${node.className}|${node.text}|${node.description}|${node.bounds}|${node.checked}"
        }
        .hashCode()

suspend fun AccessibilityService.deviceWaitStable(
    stableMs: Long = 800L,
    timeoutMs: Long = 10_000L,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last = deviceTreeSignature()
    var stableSince = System.currentTimeMillis()
    while (System.currentTimeMillis() < deadline) {
        delay(200L)
        val signature = deviceTreeSignature()
        if (signature != last) {
            last = signature
            stableSince = System.currentTimeMillis()
            continue
        }
        if (System.currentTimeMillis() - stableSince >= stableMs) return true
    }
    return false
}

/**
 * 等某段文字/某个 id 出现（或消失）。
 *
 * @param gone 为 true 时表示等它消失（比如等加载遮罩退场）
 */
suspend fun AccessibilityService.deviceWaitForText(
    query: String,
    timeoutMs: Long = 10_000L,
    gone: Boolean = false,
    byId: Boolean = false,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        val found = deviceFindNodes(query, byId = byId, maxResults = 1).isNotEmpty()
        if (found != gone) return true
        if (System.currentTimeMillis() >= deadline) return false
        delay(250L)
    }
}

enum class DeviceScrollDirection(val label: String) {
    Up("up"),
    Down("down"),
    Left("left"),
    Right("right"),
}

data class DeviceScrollOutcome(
    val ok: Boolean,
    val swipes: Int,
    val foundTarget: Boolean?,
    val message: String,
)

/**
 * 手势滚动。方向指"看更远处的方向"：down = 看更下面的内容（手势从下往上）。
 *
 * 坐标避开状态栏与底部手势区；给了 [targetText] 就边滚边找，找到即停。
 */
suspend fun AccessibilityService.deviceScroll(
    direction: DeviceScrollDirection,
    amount: Float = 0.6f,
    maxSwipes: Int = 1,
    targetText: String? = null,
    durationMs: Long = 400L,
): DeviceScrollOutcome {
    val metrics = resources.displayMetrics
    val width = metrics.widthPixels.toFloat()
    val height = metrics.heightPixels.toFloat()
    val left = width * 0.15f
    val right = width * 0.85f
    val top = height * 0.20f
    val bottom = height * 0.80f
    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f
    val ratio = amount.coerceIn(0.1f, 0.9f)
    val vertical = direction == DeviceScrollDirection.Up || direction == DeviceScrollDirection.Down
    val distance = (if (vertical) bottom - top else right - left) * ratio

    var performed = 0
    repeat(maxSwipes.coerceIn(1, 20)) { index ->
        if (targetText != null && deviceFindNodes(targetText, maxResults = 1).isNotEmpty()) {
            return DeviceScrollOutcome(
                ok = true,
                swipes = index,
                foundTarget = true,
                message = "found \"$targetText\" after $index swipe(s)",
            )
        }
        val ok = when (direction) {
            DeviceScrollDirection.Down -> deviceSwipe(centerX, bottom, centerX, bottom - distance, durationMs.coerceIn(50L, 20_000L))
            DeviceScrollDirection.Up -> deviceSwipe(centerX, top, centerX, top + distance, durationMs.coerceIn(50L, 20_000L))
            DeviceScrollDirection.Right -> deviceSwipe(left, centerY, left + distance, centerY, durationMs.coerceIn(50L, 20_000L))
            DeviceScrollDirection.Left -> deviceSwipe(right, centerY, right - distance, centerY, durationMs.coerceIn(50L, 20_000L))
        }
        if (!ok) {
            return DeviceScrollOutcome(false, performed, null, "gesture was rejected while scrolling ${direction.label}")
        }
        performed++
        delay(250L)
    }
    return if (targetText != null) {
        DeviceScrollOutcome(
            ok = false,
            swipes = performed,
            foundTarget = false,
            message = "scrolled ${direction.label} $performed time(s) but \"$targetText\" was not found",
        )
    } else {
        DeviceScrollOutcome(true, performed, null, "scrolled ${direction.label} $performed time(s)")
    }
}

// ---------------------------------------------------------------------------------------------
// 应用
// ---------------------------------------------------------------------------------------------

data class DeviceApp(
    val label: String,
    val packageName: String,
)

fun Context.deviceListApps(limit: Int = 200): List<DeviceApp> {
    val pm = packageManager
    return runCatching {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { DeviceApp(pm.getApplicationLabel(it).toString(), it.packageName) }
            .sortedBy { it.label.lowercase() }
            .take(limit)
            .toList()
    }.getOrDefault(emptyList())
}

/** 按包名或应用名打开应用，返回实际打开的那个（找不到给 null）。 */
fun Context.deviceLaunchApp(target: String): DeviceApp? {
    val app = deviceListApps(limit = 500).firstOrNull {
        it.packageName.equals(target.trim(), ignoreCase = true) ||
            it.label.equals(target.trim(), ignoreCase = true)
    } ?: deviceListApps(limit = 500).firstOrNull {
        it.packageName.contains(target.trim(), ignoreCase = true) ||
            it.label.contains(target.trim(), ignoreCase = true)
    } ?: return null
    val intent = packageManager.getLaunchIntentForPackage(app.packageName) ?: return null
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        startActivity(intent)
        app
    }.onFailure { Log.w(TAG, "launchApp failed: ${app.packageName}", it) }.getOrNull()
}
