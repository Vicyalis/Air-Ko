/*
 * 设备控制·文字驱动的动作——等价 Reticle 的 tap_text / scroll_to_text / paste / wait_text /
 * windows 等能力（改写自 https://github.com/Bumfuzzle-app/reticle，Apache-2.0，Copyright 2026
 * Bumfuzzle；已改写为 Kotlin，去掉 Shizuku 路径）。
 * 许可全文见仓库根目录 NOTICE 与 LICENSES/Apache-2.0.txt。
 */
package me.rerere.rikkahub.device

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// 窗口列表（Reticle 的 windows 工具：分屏/小窗/悬浮窗的探针）
// ---------------------------------------------------------------------------------------------

data class DeviceWindowInfo(
    val type: String,
    val packageName: String?,
    val focused: Boolean,
    val active: Boolean,
    val fullScreen: Boolean,
    val bounds: Rect?,
    val title: String?,
)

fun AccessibilityService.deviceListWindows(): List<DeviceWindowInfo> = runCatching {
    val metrics = resources.displayMetrics
    windows.orEmpty().map { window ->
        val bounds = runCatching { Rect().also { window.getBoundsInScreen(it) } }.getOrNull()
        DeviceWindowInfo(
            type = window.typeLabel(),
            packageName = runCatching { window.root?.packageName?.toString() }.getOrNull(),
            focused = window.isFocused,
            active = window.isActive,
            // 没有 isFullScreen API：用边界是否盖满屏幕来算（差 2px 也算，避免截图/导航栏误差）
            fullScreen = bounds != null &&
                bounds.width() >= metrics.widthPixels - 2 &&
                bounds.height() >= metrics.heightPixels - 2,
            bounds = bounds,
            title = runCatching { window.title?.toString() }.getOrNull(),
        )
    }
}.getOrDefault(emptyList())

private fun AccessibilityWindowInfo.typeLabel(): String = when (type) {
    AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
    AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
    AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider"
    else -> "type_$type"
}

// ---------------------------------------------------------------------------------------------
// 文字匹配（纯函数，便于单测）
// ---------------------------------------------------------------------------------------------

/** 一段文字属性是否命中查询。exact＝忽略大小写完全相等；否则包含匹配。 */
internal fun matchesQuery(value: String?, query: String, exact: Boolean): Boolean {
    if (value.isNullOrBlank() || query.isEmpty()) return false
    return if (exact) {
        value.trim().equals(query.trim(), ignoreCase = true)
    } else {
        value.contains(query, ignoreCase = true)
    }
}

/** 控件 ID 命中：允许只写后半段（如 send_btn）。 */
internal fun matchesViewId(value: String?, query: String): Boolean {
    if (value.isNullOrBlank() || query.isEmpty()) return false
    val short = value.substringAfterLast('/')
    return value.contains(query, ignoreCase = true) || short.contains(query, ignoreCase = true)
}

internal fun nodeMatches(node: AccessibilityNodeInfo, matcher: DeviceTextMatcher): Boolean {
    var checked = false
    if (!matcher.text.isNullOrEmpty()) {
        checked = true
        if (matchesQuery(node.text?.toString(), matcher.text, matcher.exact)) return true
    }
    if (!matcher.desc.isNullOrEmpty()) {
        checked = true
        if (matchesQuery(node.contentDescription?.toString(), matcher.desc, matcher.exact)) return true
    }
    if (!matcher.viewId.isNullOrEmpty()) {
        checked = true
        if (matchesViewId(node.viewIdResourceName, matcher.viewId)) return true
    }
    return !checked
}

/** 文字/描述/控件 id 三选一（或组合）的定位条件。 */
data class DeviceTextMatcher(
    val text: String? = null,
    val desc: String? = null,
    val viewId: String? = null,
    val exact: Boolean = false,
) {
    val isEmpty: Boolean get() = text.isNullOrEmpty() && desc.isNullOrEmpty() && viewId.isNullOrEmpty()

    /** 供 expect 等场景用的单文字匹配。 */
    fun describe(): String = buildList {
        text?.takeIf { it.isNotEmpty() }?.let { add("text=\"$it\"") }
        desc?.takeIf { it.isNotEmpty() }?.let { add("desc=\"$it\"") }
        viewId?.takeIf { it.isNotEmpty() }?.let { add("id=\"$it\"") }
        if (exact) add("exact")
    }.joinToString(", ")
}

// ---------------------------------------------------------------------------------------------
// 定位（控件树优先，截图认字兜底）
// ---------------------------------------------------------------------------------------------

enum class DeviceLocateSource { Node, Ocr }

data class DeviceLocatedTarget(
    val source: DeviceLocateSource,
    val x: Int,
    val y: Int,
    val matchedText: String?,
    /** 控件树命中时的可点节点（无则可点祖先也不存在，用坐标点）。 */
    val clickNode: AccessibilityNodeInfo?,
)

/** 从命中节点往上找可点祖先（控件级点击比坐标点击稳）。 */
internal fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = node
    var depth = 0
    while (current != null && depth < 20) {
        if (current.isClickable && current.isEnabled) return current
        current = runCatching { current.parent }.getOrNull()
        depth++
    }
    return null
}

/**
 * 在活动窗口里按 [matcher] 找第 [index] 个可点节点。
 *
 * 命中节点自己不可点也没关系——往上找可点祖先（Reticle 的 tap_text 同款策略）。
 */
internal fun AccessibilityService.findClickableNodeFor(
    matcher: DeviceTextMatcher,
    index: Int,
): AccessibilityNodeInfo? {
    if (matcher.isEmpty) return null
    val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.addLast(root)
    var visited = 0
    var seen = 0
    while (queue.isNotEmpty() && visited < 2_000) {
        val node = queue.removeFirst()
        visited++
        if (nodeMatches(node, matcher)) {
            if (seen == index) {
                return clickableAncestor(node) ?: node.takeIf { it.isClickable }
            }
            seen++
        }
        for (i in 0 until node.childCount) {
            runCatching { node.getChild(i) }.getOrNull()?.let { queue.addLast(it) }
        }
    }
    return null
}

/** 命中节点的屏幕中心（拿不到边界回 null）。 */
internal fun nodeCenter(node: AccessibilityNodeInfo): Pair<Int, Int>? = runCatching {
    val rect = Rect().also { node.getBoundsInScreen(it) }
    if (rect.width() <= 0 || rect.height() <= 0) null else rect.centerX() to rect.centerY()
}.getOrNull()

/**
 * 定位目标：先读控件树；树里没有（自绘界面）且允许兜底时，截屏认字找行中心。
 *
 * @param timeoutMs 找不到就等到这个上限（默认行为与 Reticle 的 timeout_ms 一致）
 */
suspend fun AccessibilityService.deviceLocate(
    matcher: DeviceTextMatcher,
    index: Int = 0,
    timeoutMs: Long = 1_500L,
    ocrFallback: Boolean = true,
): DeviceLocatedTarget? {
    if (matcher.isEmpty) return null
    val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(0L, 60_000L)
    var ocrLines: List<DeviceOcrLine>? = null
    while (true) {
        val node = findClickableNodeFor(matcher, index)
        if (node != null) {
            val center = nodeCenter(node)
            if (center != null) {
                return DeviceLocatedTarget(
                    source = DeviceLocateSource.Node,
                    x = center.first,
                    y = center.second,
                    matchedText = node.text?.toString() ?: node.contentDescription?.toString(),
                    clickNode = node,
                )
            }
        }
        if (ocrFallback && ocrLines == null) {
            ocrLines = deviceOcrOnScreen()
        }
        ocrLines?.let { lines ->
            val needle = matcher.text ?: matcher.desc ?: matcher.viewId
            if (!needle.isNullOrEmpty()) {
                val hits = lines.filter { matchesQuery(it.text, needle, matcher.exact) }
                hits.getOrNull(index)?.let { line ->
                    return DeviceLocatedTarget(
                        source = DeviceLocateSource.Ocr,
                        x = line.centerX,
                        y = line.centerY,
                        matchedText = line.text,
                        clickNode = null,
                    )
                }
            }
        }
        if (System.currentTimeMillis() >= deadline) return null
        delay(250L)
    }
}

// ---------------------------------------------------------------------------------------------
// 截屏认字（OCR）
// ---------------------------------------------------------------------------------------------

/** 截屏并整屏/分区认字；截图失败回 null（Android 11 以下、屏幕被挡等）。 */
suspend fun AccessibilityService.deviceOcrOnScreen(region: Rect? = null): List<DeviceOcrLine>? {
    val bitmap = deviceScreenshotBitmap() ?: return null
    return try {
        DeviceOcrEngine.recognize(bitmap, region)
    } finally {
        runCatching { bitmap.recycle() }
    }
}

// ---------------------------------------------------------------------------------------------
// 前台包名校验（防点错应用）
// ---------------------------------------------------------------------------------------------

/** 当前前台是不是 [expected]？读不到前台（可能读不到窗口）时返回 false 并在上层如实说明。 */
fun AccessibilityService.deviceForegroundMatches(expected: String): Boolean {
    val actual = deviceForegroundPackage() ?: return false
    return actual.equals(expected.trim(), ignoreCase = true)
}

// ---------------------------------------------------------------------------------------------
// 按文字点击（tap_text）
// ---------------------------------------------------------------------------------------------

data class DeviceTapTextOutcome(
    val ok: Boolean,
    val foundBy: String?,
    val tappedBy: String?,
    val matchedText: String?,
    val message: String,
)

/** 按条件找控件并点它；找不到只回报，不猜坐标。 */
suspend fun AccessibilityService.deviceTapText(
    context: Context,
    matcher: DeviceTextMatcher,
    index: Int = 0,
    timeoutMs: Long = 1_500L,
    durationMs: Long = 80L,
    ocrFallback: Boolean = true,
    expect: String? = null,
    expectPkg: String? = null,
    waitMs: Long = 900L,
): DeviceTapTextOutcome {
    if (matcher.isEmpty) {
        return DeviceTapTextOutcome(false, null, null, null, "no query given (need text/desc/id)")
    }
    if (!expectPkg.isNullOrBlank() && !deviceForegroundMatches(expectPkg)) {
        val actual = deviceForegroundPackage() ?: "unknown"
        return DeviceTapTextOutcome(
            false, null, null, null,
            "foreground is $actual, not $expectPkg; refused to act (check the app first)",
        )
    }
    val target = deviceLocate(matcher, index, timeoutMs, ocrFallback)
        ?: return DeviceTapTextOutcome(
            false, null, null, null,
            "not found (${matcher.describe()}) within ${timeoutMs}ms" +
                if (ocrFallback) "; looked both at the view tree and via screen OCR" else "",
        )

    val byNode = target.clickNode?.let { node ->
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
    } ?: false
    val tappedBy = if (byNode) {
        "node"
    } else {
        val gestureOk = deviceTap(
            target.x.toFloat(),
            target.y.toFloat(),
            durationMs.coerceIn(20L, 60_000L),
        )
        if (!gestureOk) {
            return DeviceTapTextOutcome(
                false, target.source.name.lowercase(), null, target.matchedText,
                "found it (${target.source.name.lowercase()}) but the tap gesture was rejected",
            )
        }
        "gesture"
    }

    var expectResult = ""
    var ok = true
    if (!expect.isNullOrBlank()) {
        delay(150L)
        val appeared = deviceWaitForMatch(DeviceTextMatcher(text = expect), waitMs.coerceIn(100L, 30_000L), gone = false, ocrFallback = false)
        ok = appeared
        expectResult = if (appeared) {
            "; \"$expect\" appeared, tap confirmed"
        } else {
            "; expected \"$expect\" did not appear within ${waitMs}ms, tap may have missed"
        }
    }
    return DeviceTapTextOutcome(
        ok = ok,
        foundBy = target.source.name.lowercase(),
        tappedBy = tappedBy,
        matchedText = target.matchedText,
        message = "tapped \"${target.matchedText ?: matcher.describe()}\" at (${target.x},${target.y})" +
            " via $tappedBy$expectResult",
    )
}

// ---------------------------------------------------------------------------------------------
// 滚动查找（scroll_to_text）
// ---------------------------------------------------------------------------------------------

data class DeviceScrollToTextOutcome(
    val ok: Boolean,
    val screens: Int,
    val foundBy: String?,
    val tapped: Boolean,
    val message: String,
)

/** 逐屏滚动找目标；找到可顺手点；到尽头/超时都如实回报，绝不在没找到时乱点。 */
suspend fun AccessibilityService.deviceScrollToText(
    context: Context,
    matcher: DeviceTextMatcher,
    direction: DeviceScrollDirection,
    maxScroll: Int = 10,
    timeoutMs: Long = 12_000L,
    tap: Boolean = false,
    index: Int = 0,
    ocrFallback: Boolean = true,
    expect: String? = null,
    expectPkg: String? = null,
    waitMs: Long = 900L,
): DeviceScrollToTextOutcome {
    if (matcher.isEmpty) {
        return DeviceScrollToTextOutcome(false, 0, null, false, "no query given (need text/desc/id)")
    }
    if (!expectPkg.isNullOrBlank() && !deviceForegroundMatches(expectPkg)) {
        val actual = deviceForegroundPackage() ?: "unknown"
        return DeviceScrollToTextOutcome(
            false, 0, null, false,
            "foreground is $actual, not $expectPkg; refused to act (check the app first)",
        )
    }
    val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(500L, 120_000L)
    val maxScreens = maxScroll.coerceIn(1, 40)
    var screens = 0
    var staleScreens = 0
    var lastDigest = deviceScrollDigest(ocrFallback)

    while (screens <= maxScreens && System.currentTimeMillis() < deadline) {
        val target = deviceLocate(matcher, index, timeoutMs = 700L, ocrFallback = ocrFallback)
        if (target != null) {
            if (!tap) {
                return DeviceScrollToTextOutcome(
                    true, screens, target.source.name.lowercase(), false,
                    "found \"${target.matchedText ?: matcher.describe()}\" after $screens screen(s) at (${target.x},${target.y})",
                )
            }
            val tapOutcome = tapLocated(context, target, expect, waitMs)
            return DeviceScrollToTextOutcome(
                ok = tapOutcome.first,
                screens = screens,
                foundBy = target.source.name.lowercase(),
                tapped = true,
                message = "found after $screens screen(s); ${tapOutcome.second}",
            )
        }
        if (screens >= maxScreens) break
        deviceScroll(direction, amount = 0.6f, maxSwipes = 1, targetText = null)
        screens++
        delay(250L)
        val digest = deviceScrollDigest(ocrFallback)
        if (digest == lastDigest) {
            staleScreens++
            if (staleScreens >= 2) {
                return DeviceScrollToTextOutcome(
                    false, screens, null, false,
                    "scrolled ${direction.label} $screens time(s) but the screen stopped changing and " +
                        "\"${matcher.describe()}\" was not found (likely the end of the list)",
                )
            }
        } else {
            staleScreens = 0
        }
        lastDigest = digest
    }
    return DeviceScrollToTextOutcome(
        false, screens, null, false,
        "scrolled ${direction.label} $screens time(s) within ${timeoutMs}ms but" +
            " \"${matcher.describe()}\" was not found",
    )
}

private suspend fun AccessibilityService.tapLocated(
    context: Context,
    target: DeviceLocatedTarget,
    expect: String?,
    waitMs: Long,
): Pair<Boolean, String> {
    val byNode = target.clickNode?.let { node ->
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
    } ?: false
    val how = if (byNode) {
        "node"
    } else {
        val ok = deviceTap(target.x.toFloat(), target.y.toFloat(), 80L)
        if (!ok) return false to "the tap gesture was rejected at (${target.x},${target.y})"
        "gesture"
    }
    if (expect.isNullOrBlank()) {
        return true to "tapped \"${target.matchedText ?: "(target)"}\" via $how"
    }
    delay(150L)
    val appeared = deviceWaitForMatch(
        DeviceTextMatcher(text = expect),
        waitMs.coerceIn(100L, 30_000L),
        gone = false,
        ocrFallback = false,
    )
    return appeared to if (appeared) {
        "tapped via $how; \"$expect\" appeared, tap confirmed"
    } else {
        "tapped via $how but \"$expect\" did not appear within ${waitMs}ms (tap may have missed)"
    }
}

/** 画面指纹：控件树为主，树读不到（自绘界面）时用 OCR 文本。用于「滚到底」判断。 */
private suspend fun AccessibilityService.deviceScrollDigest(ocrFallback: Boolean): Int {
    val signature = deviceTreeSignature()
    if (signature != 0) return signature
    if (!ocrFallback) return 0
    val lines = deviceOcrOnScreen() ?: return 0
    return lines.joinToString("\u0001") { "${it.text}@${it.centerX},${it.centerY}" }.hashCode()
}

// ---------------------------------------------------------------------------------------------
// 剪贴板直贴（paste）
// ---------------------------------------------------------------------------------------------

data class DevicePasteOutcome(
    val ok: Boolean,
    /** node_paste / menu_paste / set_text_fallback / none */
    val how: String?,
    val applied: String?,
    val message: String,
)

/**
 * 剪贴板直贴：写剪贴板 → 找输入框 → 依次试「控件级粘贴」「长按出粘贴菜单」「直接写入兜底」。
 *
 * 原剪贴板在 Android 10+ 后台应用读不到，restore 只在读得到时才生效（与 Reticle 同样的边界，
 * 如实写进回执）。
 */
suspend fun AccessibilityService.devicePasteText(
    context: Context,
    text: String,
    restoreClipboard: Boolean = true,
    x: Int? = null,
    y: Int? = null,
): DevicePasteOutcome {
    val clipboard = runCatching {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }.getOrNull() ?: return DevicePasteOutcome(false, null, null, "clipboard service unavailable")

    val original = runCatching { clipboard.primaryClip }.getOrNull()
    val originalReadable = original != null && original.itemCount > 0

    fun writeClip(value: String) {
        runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("airko", value)) }
    }

    fun restore() {
        if (restoreClipboard && originalReadable) {
            runCatching { original?.let { clipboard.setPrimaryClip(it) } }
        }
    }

    writeClip(text)
    if (x != null && y != null) {
        deviceTap(x.toFloat(), y.toFloat(), 80L)
        delay(350L)
    }

    var target: AccessibilityNodeInfo = findEditableTarget(this)
        ?: run {
            restore()
            return DevicePasteOutcome(
                false, null, null,
                "no readable input field; tap the field first (pass x/y) or use device_type",
            )
        }

    nodeCenter(target)?.let { (cx, cy) ->
        if (!target.isFocused) {
            deviceTap(cx.toFloat(), cy.toFloat(), 80L)
            delay(300L)
            target = findEditableTarget(this) ?: target
        }
    }

    // ① 控件级粘贴
    val pasted = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
    delay(300L)
    if (pasted) {
        val fresh = findEditableTarget(this)?.text?.toString()
        if (fresh?.contains(text) == true) {
            restore()
            return DevicePasteOutcome(true, "node_paste", fresh, "pasted into the field via ACTION_PASTE")
        }
    }

    // ② 长按输入框 → 点「粘贴」菜单
    val center = nodeCenter(target)
    if (center != null) {
        deviceTap(center.first.toFloat(), center.second.toFloat(), 750L)
        delay(500L)
        val menu = deviceLocate(
            DeviceTextMatcher(text = "粘贴", exact = false),
            timeoutMs = 1_200L,
            ocrFallback = false,
        ) ?: deviceLocate(
            DeviceTextMatcher(text = "Paste", exact = false),
            timeoutMs = 800L,
            ocrFallback = false,
        )
        if (menu != null) {
            val menuClicked = menu.clickNode?.let { node ->
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            } ?: deviceTap(menu.x.toFloat(), menu.y.toFloat(), 80L)
            delay(400L)
            if (menuClicked) {
                val fresh = findEditableTarget(this)?.text?.toString()
                if (fresh?.contains(text) == true) {
                    restore()
                    return DevicePasteOutcome(true, "menu_paste", fresh, "pasted via the long-press paste menu")
                }
            }
        }
    }

    // ③ 直接写入兜底（不属于粘贴通道，但总比什么都没做成好；回执里说清楚）
    val fallback = deviceTypeText(text, append = true)
    restore()
    return if (fallback.ok) {
        DevicePasteOutcome(
            true, "set_text_fallback", fallback.applied,
            "paste channels did not work here; wrote the text directly instead (verify with device_ui)",
        )
    } else {
        DevicePasteOutcome(
            false, null, fallback.applied,
            "paste failed here (${fallback.message}); try device_tap on the field then device_type",
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 等待某段文字出现/消失（wait_text 的完整版：text/desc/id + exact）
// ---------------------------------------------------------------------------------------------

/**
 * 等 [matcher] 出现（或消失）。
 *
 * 控件树优先；树一条节点都读不到（自绘界面）且允许 OCR 时，用截屏认字兜底（约 1 秒一次，
 * 因为截图本身有节流）。
 */
suspend fun AccessibilityService.deviceWaitForMatch(
    matcher: DeviceTextMatcher,
    timeoutMs: Long = 5_000L,
    gone: Boolean = false,
    ocrFallback: Boolean = true,
): Boolean {
    if (matcher.isEmpty) return false
    val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(100L, 120_000L)
    var lastOcrAt = 0L
    var ocrResult: Boolean? = null
    while (true) {
        val nodeHit = findClickableNodeFor(matcher, 0) != null
        if (nodeHit != gone) return true
        if (!nodeHit && ocrFallback && System.currentTimeMillis() - lastOcrAt > 1_000L) {
            lastOcrAt = System.currentTimeMillis()
            val needle = matcher.text ?: matcher.desc ?: matcher.viewId
            val lines = deviceOcrOnScreen()
            ocrResult = if (needle.isNullOrEmpty() || lines == null) {
                null
            } else {
                lines.any { matchesQuery(it.text, needle, matcher.exact) }
            }
            if (ocrResult == true && !gone) return true
        }
        if (System.currentTimeMillis() >= deadline) {
            return if (gone) {
                false
            } else {
                ocrResult == true
            }
        }
        delay(250L)
    }
}
