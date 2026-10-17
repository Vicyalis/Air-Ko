/*
 * 设备控制工具（模型侧入口）改写自 Reticle 的 McpServer.buildTools()/humanize()
 * (https://github.com/Bumfuzzle-app/reticle, Apache-2.0, Copyright 2026 Bumfuzzle)：
 * 去掉了 MCP/HTTP 层与令牌、审计，改为 Air-Ko 的进程内 Tool，并改成模型可读的文本结果。
 */
package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.device.DeviceControlService
import me.rerere.rikkahub.device.DeviceControlState
import me.rerere.rikkahub.device.DeviceControlAudit
import me.rerere.rikkahub.device.DeviceControlMemGuard
import me.rerere.rikkahub.device.DeviceControlNotifier
import me.rerere.rikkahub.device.DeviceControlPrefs
import me.rerere.rikkahub.device.DeviceControlWatchdog
import me.rerere.rikkahub.device.DeviceDownloader
import me.rerere.rikkahub.device.DeviceGlobalKey
import me.rerere.rikkahub.device.DeviceOcrEngine
import me.rerere.rikkahub.device.DeviceOcrLine
import me.rerere.rikkahub.device.DeviceScrollDirection
import me.rerere.rikkahub.device.DeviceTextMatcher
import me.rerere.rikkahub.device.ShizukuExecResult
import me.rerere.rikkahub.device.ShizukuState
import me.rerere.rikkahub.device.ShizukuSupport
import me.rerere.rikkahub.device.deviceAppLabel
import me.rerere.rikkahub.device.deviceBuzz
import me.rerere.rikkahub.device.deviceFindNodes
import me.rerere.rikkahub.device.deviceForegroundMatches
import me.rerere.rikkahub.device.deviceForegroundPackage
import me.rerere.rikkahub.device.deviceGlobalKey
import me.rerere.rikkahub.device.deviceLaunchApp
import me.rerere.rikkahub.device.deviceListApps
import me.rerere.rikkahub.device.deviceListWindows
import me.rerere.rikkahub.device.deviceOcrOnScreen
import me.rerere.rikkahub.device.devicePasteText
import me.rerere.rikkahub.device.deviceScreenshotBitmap
import me.rerere.rikkahub.device.deviceScroll
import me.rerere.rikkahub.device.deviceScrollToText
import me.rerere.rikkahub.device.deviceSnapshotUi
import me.rerere.rikkahub.device.deviceSwipe
import me.rerere.rikkahub.device.deviceTap
import me.rerere.rikkahub.device.deviceTapText
import me.rerere.rikkahub.device.deviceTypeText
import me.rerere.rikkahub.device.deviceWaitForMatch
import me.rerere.rikkahub.device.deviceWaitForText
import me.rerere.rikkahub.device.deviceWaitStable
import me.rerere.rikkahub.device.formatForModel
import me.rerere.rikkahub.device.runActSelfTest
import me.rerere.rikkahub.device.toScaledJpegBytes
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.io.File

/** 需要审批的默认值：只读工具不打扰用户，会改动设备的默认要确认（设置里可放开）。 */
private const val DEVICE_APPROVAL_DEFAULT = true

/**
 * 免确认的只读工具名单（与各 builder 是否设置 needsApproval 保持一致）——
 * 设置页显示每个工具的「执行前询问」默认状态时用它。
 */
internal val DEVICE_READ_ONLY_TOOLS = setOf(
    "device_status",
    "device_ui",
    "device_screenshot",
    "device_find",
    "device_wait",
    "device_ping",
    "device_ocr",
    "device_windows",
    "device_help",
    "device_download_status",
)

internal class DeviceControlTools(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    private val statusTool by lazy { buildDeviceStatusTool() }
    private val uiTool by lazy { buildDeviceUiTool() }
    private val tapTool by lazy { buildDeviceTapTool() }
    private val swipeTool by lazy { buildDeviceSwipeTool() }
    private val typeTool by lazy { buildDeviceTypeTool() }
    private val keyTool by lazy { buildDeviceKeyTool() }
    private val appsTool by lazy { buildDeviceAppsTool() }
    private val screenshotTool by lazy { buildDeviceScreenshotTool() }
    private val findTool by lazy { buildDeviceFindTool() }
    private val waitTool by lazy { buildDeviceWaitTool() }
    private val scrollTool by lazy { buildDeviceScrollTool() }
    private val pingTool by lazy { buildDevicePingTool() }
    private val ocrTool by lazy { buildDeviceOcrTool() }
    private val windowsTool by lazy { buildDeviceWindowsTool() }
    private val longPressTool by lazy { buildDeviceLongPressTool() }
    private val tapTextTool by lazy { buildDeviceTapTextTool() }
    private val scrollToTextTool by lazy { buildDeviceScrollToTextTool() }
    private val pasteTool by lazy { buildDevicePasteTool() }
    private val notifyTool by lazy { buildDeviceNotifyTool() }
    private val buzzTool by lazy { buildDeviceBuzzTool() }
    private val downloadTool by lazy { buildDeviceDownloadTool() }
    private val downloadStatusTool by lazy { buildDeviceDownloadStatusTool() }
    private val helpTool by lazy { buildDeviceHelpTool() }
    private val actTestTool by lazy { buildDeviceActTestTool() }
    private val doChainTool by lazy { buildDeviceDoChainTool() }
    private val shellTool by lazy { buildDeviceShellTool() }
    private val installTool by lazy { buildDeviceInstallTool() }
    private val uninstallTool by lazy { buildDeviceUninstallTool() }
    private val forceStopTool by lazy { buildDeviceForceStopTool() }
    private val settingsTool by lazy { buildDeviceSettingsTool() }
    private val inputTool by lazy { buildDeviceInputTool() }
    private val screencapTool by lazy { buildDeviceScreencapTool() }

    fun getTools(): List<Tool> = listOf(
        statusTool,
        uiTool,
        tapTool,
        swipeTool,
        typeTool,
        keyTool,
        appsTool,
        screenshotTool,
        findTool,
        waitTool,
        scrollTool,
        pingTool,
        ocrTool,
        windowsTool,
        longPressTool,
        tapTextTool,
        scrollToTextTool,
        pasteTool,
        notifyTool,
        buzzTool,
        downloadTool,
        downloadStatusTool,
        helpTool,
        actTestTool,
        doChainTool,
        shellTool,
        installTool,
        uninstallTool,
        forceStopTool,
        settingsTool,
        inputTool,
        screencapTool,
    ).map { it.withAudit().withApprovalOverride() }

    /**
     * 用户在设置页对单个工具的「执行前询问」覆盖（全局自动放行仍然优先）。
     * 覆盖值只影响这一个工具，缺省时用工具自己的默认值。
     */
    private fun Tool.withApprovalOverride(): Tool {
        val override = runCatching {
            DeviceControlPrefs.toolApprovalOverrides(context)[name]
        }.getOrNull() ?: return this
        return copy(
            needsApproval = { !settingsStore.settingsFlow.value.deviceAgentAutoApprove && override },
        )
    }

    /** 每次调用落一行审计（对应 Reticle 的 logs/ 审计；失败绝不影响工具本身）。 */
    private fun Tool.withAudit(): Tool = copy(
        execute = { args ->
            val startedAt = System.currentTimeMillis()
            // 媒体心跳（对齐 Reticle 的 mediaTouch）：媒体样式通知里显示正在执行哪个工具（buzz 除外）
            if (name != "device_buzz") {
                runCatching { DeviceControlNotifier.onToolTouched(context, name) }
            }
            val result = execute(args)
            runCatching {
                DeviceControlAudit.log(
                    context = context,
                    tool = name,
                    args = DeviceControlAudit.sanitize(args.toString()),
                    result = DeviceControlAudit.sanitize(
                        result.joinToString(" ") { part ->
                            (part as? UIMessagePart.Text)?.text ?: part.toString()
                        }
                    ),
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }
            result
        }
    )

    /** 免确认开关打开时全部放行；否则按每个工具的默认值。 */
    private fun approval(requiredByDefault: Boolean): (JsonElement) -> Boolean = {
        requiredByDefault && !settingsStore.settingsFlow.value.deviceAgentAutoApprove
    }

    private fun buildDeviceStatusTool(): Tool = Tool(
        name = "device_status",
        description = """
            Report the health of device control: accessibility authorized/connected, screenshot support
            (Android 11+), the current foreground app, the watchdog state (is the service still in the
            system's enabled list, repair attempts, whether the app may write system settings), the
            memory-guard level (memory self-protection), the auxiliary permissions (all-files access,
            WRITE_SECURE_SETTINGS), the Shizuku state (running / permission / ready) and the tail of the
            audit log. Call this first when other device tools fail, to find out why.
        """.trimIndent().replace("\n", " "),
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            val status = DeviceControlState.refresh(context)
            val watchdog = DeviceControlWatchdog.snapshot(context)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("authorized", status.authorized)
                        put("connected", status.connected)
                        put("level", status.level.name.lowercase())
                        put("screenshot_supported", status.screenshotSupported)
                        status.foregroundPackage?.let { pkg ->
                            put("foreground_package", pkg)
                            put("foreground_app", context.deviceAppLabel(pkg))
                        }
                        if (status.connectedAt > 0) put("connected_at", status.connectedAt)
                        if (status.lastEvent.isNotBlank()) put("last_event", status.lastEvent)
                        put("watchdog", buildJsonObject {
                            put("enabled", DeviceControlPrefs.watchdogEnabled(context))
                            put("note", watchdog.note.name)
                            put("in_list", watchdog.inList)
                            put("fixes", watchdog.fixes)
                            if (watchdog.lastCheckAt > 0) put("last_check_at", watchdog.lastCheckAt)
                            if (watchdog.lastFixAt > 0) put("last_fix_at", watchdog.lastFixAt)
                            put("can_write_secure_settings", watchdog.canWriteSecureSettings)
                            put("interval_ms", watchdog.intervalMs)
                            put("paused", watchdog.paused)
                        })
                        put("memory_guard", buildJsonObject {
                            put("level", DeviceControlMemGuard.level.name.lowercase())
                            put("text", DeviceControlMemGuard.statusText(context))
                        })
                        put("permissions", buildJsonObject {
                            put("all_files", DeviceDownloader.hasAllFilesAccess(context))
                            put("write_secure_settings", watchdog.canWriteSecureSettings)
                        })
                        put("shizuku", buildJsonObject {
                            val shizuku = ShizukuSupport.info()
                            put("state", shizuku.state.name.lowercase())
                            put("ready", shizuku.ready)
                            if (shizuku.version > 0) put("version", shizuku.version)
                            if (shizuku.uid >= 0) put("uid", shizuku.uid)
                            put("root", shizuku.isRoot)
                        })
                        put("log_tail", buildJsonArray {
                            DeviceControlAudit.recentLines(context, 3).reversed().forEach { add(it) }
                        })
                    }.toString()
                )
            )
        },
    )

    private fun buildDeviceUiTool(): Tool = Tool(
        name = "device_ui",
        description = """
            Read the current screen as a text tree of UI nodes. Each line is:
            #index Class "text" desc="..." id=... [editable,clickable,...] tap=#n @(x,y)
            where @(x,y) is the center point you can pass to device_tap. 'tap=#n' means this node is not
            clickable itself but tapping it hits node #n. Call this before every tap to get fresh coordinates,
            and again after acting to verify the result. Self-drawn screens (games, some chat apps) may expose
            little or nothing here - fall back to device_screenshot.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("max_nodes", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum number of nodes to return (default 300, max 800)")
                    })
                }
            )
        },
        execute = { args ->
            val maxNodes = args.jsonObject["max_nodes"]?.jsonPrimitive?.intOrNull
                ?.coerceIn(20, 800) ?: 300
            withDeviceService { service ->
                val nodes = withContext(Dispatchers.Default) {
                    service.deviceSnapshotUi(maxNodes = maxNodes)
                }
                val foreground = service.deviceForegroundPackage()
                val header = buildString {
                    append("foreground=")
                    append(foreground?.let { "${context.deviceAppLabel(it)} ($it)" } ?: "unknown")
                    append(" nodes=").append(nodes.size)
                    if (nodes.size >= maxNodes) append(" (truncated)")
                }
                val tree = nodes.formatForModel()
                val body = if (tree.isEmpty()) {
                    "$header\n(no accessible nodes: this screen is likely self-drawn; use device_screenshot instead)"
                } else {
                    "$header\n$tree"
                }
                listOf(UIMessagePart.Text(body))
            }
        },
    )

    private fun buildDeviceTapTool(): Tool = Tool(
        name = "device_tap",
        description = """
            Tap or long-press a point on the screen. Get the point from device_ui (@(x,y) coordinates).
            A long press (long_press=true) is how you open context menus, drag items or select text.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("x", buildJsonObject {
                        put("type", "integer")
                        put("description", "Screen X coordinate (pixels)")
                    })
                    put("y", buildJsonObject {
                        put("type", "integer")
                        put("description", "Screen Y coordinate (pixels)")
                    })
                    put("long_press", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Hold instead of tapping (default false)")
                    })
                    put("duration_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "Custom press duration in ms (long_press uses 650ms by default)")
                    })
                },
                required = listOf("x", "y"),
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val x = params["x"]?.jsonPrimitive?.intOrNull ?: error("x is required")
            val y = params["y"]?.jsonPrimitive?.intOrNull ?: error("y is required")
            val longPress = params["long_press"]?.jsonPrimitive?.booleanOrNull ?: false
            val duration = params["duration_ms"]?.jsonPrimitive?.intOrNull?.toLong()
                ?: if (longPress) 650L else 50L
            withDeviceService { service ->
                val ok = service.deviceTap(x.toFloat(), y.toFloat(), duration)
                listOf(
                    UIMessagePart.Text(
                        deviceActionResult(
                            ok = ok,
                            message = if (ok) {
                                "${if (longPress) "long-pressed" else "tapped"} ($x,$y) for ${duration}ms"
                            } else {
                                "gesture was rejected or cancelled at ($x,$y)"
                            },
                        )
                    )
                )
            }
        },
    )

    private fun buildDeviceSwipeTool(): Tool = Tool(
        name = "device_swipe",
        description = """
            Swipe or drag from one point to another, e.g. scroll a list, dismiss a notification card,
            pull to refresh, or move a slider. Swiping up scrolls content down.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("start_x", buildJsonObject { put("type", "integer") })
                    put("start_y", buildJsonObject { put("type", "integer") })
                    put("end_x", buildJsonObject { put("type", "integer") })
                    put("end_y", buildJsonObject { put("type", "integer") })
                    put("duration_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "Duration of the swipe in ms (default 300)")
                    })
                },
                required = listOf("start_x", "start_y", "end_x", "end_y"),
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val startX = params["start_x"]?.jsonPrimitive?.intOrNull ?: error("start_x is required")
            val startY = params["start_y"]?.jsonPrimitive?.intOrNull ?: error("start_y is required")
            val endX = params["end_x"]?.jsonPrimitive?.intOrNull ?: error("end_x is required")
            val endY = params["end_y"]?.jsonPrimitive?.intOrNull ?: error("end_y is required")
            val duration = (params["duration_ms"]?.jsonPrimitive?.intOrNull ?: 300).toLong()
            withDeviceService { service ->
                val ok = service.deviceSwipe(
                    startX.toFloat(), startY.toFloat(), endX.toFloat(), endY.toFloat(), duration
                )
                listOf(
                    UIMessagePart.Text(
                        deviceActionResult(
                            ok = ok,
                            message = if (ok) {
                                "swiped ($startX,$startY) -> ($endX,$endY) in ${duration}ms"
                            } else {
                                "swipe was rejected or cancelled"
                            },
                        )
                    )
                )
            }
        },
    )

    private fun buildDeviceTypeTool(): Tool = Tool(
        name = "device_type",
        description = """
            Type text into the currently focused input field. Tap the field with device_tap first.
            mode=replace (default) overwrites the whole field, mode=append keeps existing content.
            Typing does not press Enter - use device_key with key=back to close the keyboard.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("description", "Text to type")
                    })
                    put("mode", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("replace")
                            add("append")
                        })
                        put("description", "replace (default) or append")
                    })
                    put("x", buildJsonObject {
                        put("type", "integer")
                        put("description", "Optional: tap this point first to focus the field (pixels) - needed when no field is focused")
                    })
                    put("y", buildJsonObject {
                        put("type", "integer")
                        put("description", "Y of the focus point (pixels)")
                    })
                    put("expect_pkg", buildJsonObject {
                        put("type", "string")
                        put("description", "Refuse to act unless this package is in the foreground")
                    })
                },
                required = listOf("text"),
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val text = params["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
            val append = params["mode"]?.jsonPrimitive?.contentOrNull == "append"
            val x = params["x"]?.jsonPrimitive?.intOrNull
            val y = params["y"]?.jsonPrimitive?.intOrNull
            val expectPkg = params["expect_pkg"]?.jsonPrimitive?.contentOrNull
            withDeviceService { service ->
                if (!expectPkg.isNullOrBlank() && !service.deviceForegroundMatches(expectPkg)) {
                    return@withDeviceService listOf(
                        UIMessagePart.Text(
                            deviceErrorPayload(
                                "WRONG_FOREGROUND",
                                "foreground is ${service.deviceForegroundPackage() ?: "unknown"}, " +
                                    "not $expectPkg; refused to act",
                            )
                        )
                    )
                }
                if (x != null && y != null) {
                    service.deviceTap(x.toFloat(), y.toFloat(), 80L)
                    kotlinx.coroutines.delay(300L)
                }
                val outcome = service.deviceTypeText(text, append)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", outcome.ok)
                            put("message", outcome.message)
                            outcome.applied?.let { put("field_text", it) }
                        }.toString()
                    )
                )
            }
        },
    )

    private fun buildDeviceKeyTool(): Tool = Tool(
        name = "device_key",
        description = """
            Press a system key/action: back, home, recents, notifications (open the notification shade),
            quick_settings, or lock (lock the screen).
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("key", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            DeviceGlobalKey.entries.forEach { add(it.label) }
                        })
                        put("description", "Which system key to press")
                    })
                },
                required = listOf("key"),
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val raw = args.jsonObject["key"]?.jsonPrimitive?.contentOrNull ?: error("key is required")
            val key = DeviceGlobalKey.from(raw)
                ?: error("unknown key: $raw, must be one of ${DeviceGlobalKey.entries.joinToString { it.label }}")
            withDeviceService { service ->
                val ok = service.deviceGlobalKey(key)
                listOf(
                    UIMessagePart.Text(
                        deviceActionResult(
                            ok = ok,
                            message = if (ok) "pressed ${key.label}" else "system refused ${key.label}",
                        )
                    )
                )
            }
        },
    )

    private fun buildDeviceAppsTool(): Tool = Tool(
        name = "device_apps",
        description = """
            List installed apps that can be launched, or open one. For launch, pass the package name or the
            app label (e.g. "WeChat" or "com.tencent.mm"). Returns the matched app.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("list")
                            add("launch")
                        })
                        put("description", "list or launch")
                    })
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Package name or app label (required for launch, optional filter for list)")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max apps for list (default 60)")
                    })
                },
                required = listOf("action"),
            )
        },
        needsApproval = { args ->
            val launching = args.jsonObject["action"]?.jsonPrimitive?.contentOrNull == "launch"
            launching && approval(DEVICE_APPROVAL_DEFAULT).invoke(args)
        },
        execute = { args ->
            val params = args.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val query = params["query"]?.jsonPrimitive?.contentOrNull
            when (action) {
                "list" -> {
                    val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 300)
                    val apps = withContext(Dispatchers.Default) {
                        context.deviceListApps(limit = 500)
                            .filter { query.isNullOrBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
                            .take(limit)
                    }
                    val text = buildString {
                        append("apps=").append(apps.size)
                        append('\n')
                        apps.forEach { append(it.label).append(" — ").append(it.packageName).append('\n') }
                    }.trimEnd()
                    listOf(UIMessagePart.Text(text))
                }

                "launch" -> {
                    require(!query.isNullOrBlank()) { "query is required when action=launch" }
                    val app = withContext(Dispatchers.Default) { context.deviceLaunchApp(query) }
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("ok", app != null)
                                if (app != null) {
                                    put("launched", app.label)
                                    put("package", app.packageName)
                                } else {
                                    put("error", "app not found: $query")
                                }
                            }.toString()
                        )
                    )
                }

                else -> error("unknown action: $action")
            }
        },
    )

    private fun buildDeviceScreenshotTool(): Tool = Tool(
        name = "device_screenshot",
        description = """
            Take a screenshot of the current screen (requires Android 11+). Use it when device_ui returns
            little or nothing, or when you need to see an image/video/self-drawn UI. The image is scaled for
            the model; exact pixel coordinates may differ slightly from device_ui coordinates.
        """.trimIndent().replace("\n", " "),
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            withDeviceService { service ->
                if (!DeviceControlState.status.value.screenshotSupported) {
                    return@withDeviceService listOf(
                        UIMessagePart.Text(
                            deviceErrorPayload(
                                "SCREENSHOT_UNSUPPORTED",
                                "screenshot needs Android 11+ and a connected accessibility service",
                            )
                        )
                    )
                }
                val bitmap = withContext(Dispatchers.Default) { service.deviceScreenshotBitmap() }
                    ?: return@withDeviceService listOf(
                        UIMessagePart.Text(
                            deviceErrorPayload(
                                "SCREENSHOT_FAILED",
                                "the system refused the capture (secure screen or too many requests); try again later",
                            )
                        )
                    )
                val jpeg = withContext(Dispatchers.Default) { bitmap.toScaledJpegBytes() }
                val base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", true)
                            put("width", bitmap.width)
                            put("height", bitmap.height)
                            put("bytes", jpeg.size)
                        }.toString()
                    ),
                    UIMessagePart.Image("data:image/jpeg;base64,$base64"),
                )
            }
        },
    )

    private fun buildDeviceFindTool(): Tool = Tool(
        name = "device_find",
        description = """
            Find UI nodes on the current screen by text, content description or view id (case-insensitive
            substring). Returns the same line format as device_ui, including @(x,y) tap points, so you can
            locate a control and then tap it. Use it instead of re-reading the whole tree when you only need
            one control, and use it to check whether something is on screen at all.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Text/description to search for (view id when by_id=true)")
                    })
                    put("by_id", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Search view ids instead of text (default false)")
                    })
                    put("max_results", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum matches to return (default 10, max 50)")
                    })
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val query = params["query"]?.jsonPrimitive?.contentOrNull ?: error("query is required")
            val byId = params["by_id"]?.jsonPrimitive?.booleanOrNull ?: false
            val maxResults = (params["max_results"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 50)
            withDeviceService { service ->
                val nodes = withContext(Dispatchers.Default) {
                    service.deviceFindNodes(query, byId = byId, maxResults = maxResults)
                }
                val body = if (nodes.isEmpty()) {
                    if (byId) "no node with view id containing \"$query\"" else "no node containing \"$query\""
                } else {
                    "matches=${nodes.size}\n${nodes.formatForModel()}"
                }
                listOf(UIMessagePart.Text(body))
            }
        },
    )

    private fun buildDeviceWaitTool(): Tool = Tool(
        name = "device_wait",
        description = """
            Wait for the screen instead of polling in a loop. mode=text waits until some text appears (or is
            gone with gone=true), mode=stable waits until the UI stops changing. Always use it after a tap that
            opens a page or a dialog, before reading device_ui again.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("mode", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("text")
                            add("stable")
                        })
                        put("description", "text (default) or stable")
                    })
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Text/description to wait for (required when mode=text)")
                    })
                    put("desc", buildJsonObject {
                        put("type", "string")
                        put("description", "Wait for this content description instead of query (mode=text only)")
                    })
                    put("exact", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Require exact text equality instead of contains (mode=text only)")
                    })
                    put("gone", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Wait until the text disappears instead (mode=text only)")
                    })
                    put("by_id", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Match view ids instead of text (mode=text only)")
                    })
                    put("timeout_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "Give up after this many ms (default 10000, max 60000)")
                    })
                    put("stable_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "mode=stable: how long the UI must stay unchanged (default 800ms)")
                    })
                }
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val mode = params["mode"]?.jsonPrimitive?.contentOrNull ?: "text"
            val timeout = (params["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 10_000).toLong()
                .coerceIn(500L, 60_000L)
            withDeviceService { service ->
                val (ok, message) = when (mode) {
                    "stable" -> {
                        val stableMs = (params["stable_ms"]?.jsonPrimitive?.intOrNull ?: 800).toLong()
                            .coerceIn(200L, 10_000L)
                        val stable = withContext(Dispatchers.Default) {
                            service.deviceWaitStable(stableMs, timeout)
                        }
                        stable to if (stable) {
                            "the screen is stable now"
                        } else {
                            "the screen kept changing for ${timeout}ms"
                        }
                    }

                    else -> {
                        val query = params["query"]?.jsonPrimitive?.contentOrNull
                        val desc = params["desc"]?.jsonPrimitive?.contentOrNull
                        if (query.isNullOrBlank() && desc.isNullOrBlank()) {
                            error("query (or desc) is required when mode=text")
                        }
                        val gone = params["gone"]?.jsonPrimitive?.booleanOrNull ?: false
                        val byId = params["by_id"]?.jsonPrimitive?.booleanOrNull ?: false
                        val exact = params["exact"]?.jsonPrimitive?.booleanOrNull ?: false
                        val matcher = DeviceTextMatcher(
                            text = if (byId) null else query,
                            desc = desc,
                            viewId = if (byId) query ?: desc else null,
                            exact = exact,
                        )
                        val done = withContext(Dispatchers.Default) {
                            service.deviceWaitForMatch(matcher, timeout, gone = gone, ocrFallback = true)
                        }
                        val label = matcher.describe()
                        done to when {
                            done && gone -> "$label is gone"
                            done -> "$label appeared"
                            gone -> "$label is still on screen after ${timeout}ms"
                            else -> "$label did not appear within ${timeout}ms"
                        }
                    }
                }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", ok)
                            put("message", message)
                        }.toString()
                    )
                )
            }
        },
    )

    private fun buildDeviceScrollTool(): Tool = Tool(
        name = "device_scroll",
        description = """
            Scroll the screen with a swipe. direction says where you want to look: down means see further down
            the content. Pass target_text to keep swiping until that text shows up. Prefer this over hand-made
            swipes for scrollable lists, and always re-read device_ui after scrolling.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("direction", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            DeviceScrollDirection.entries.forEach { add(it.label) }
                        })
                        put("description", "up/down/left/right - the direction you want to look")
                    })
                    put("amount", buildJsonObject {
                        put("type", "number")
                        put("description", "How far each swipe moves, 0.1-0.9 of the content area (default 0.6)")
                    })
                    put("max_swipes", buildJsonObject {
                        put("type", "integer")
                        put("description", "At most this many swipes (default 1; with target_text try 5-8)")
                    })
                    put("target_text", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional: stop early once this text/description is on screen")
                    })
                    put("expect_pkg", buildJsonObject {
                        put("type", "string")
                        put("description", "Refuse to act unless this package is in the foreground")
                    })
                    put("duration_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "Swipe duration in ms (default 400)")
                    })
                },
                required = listOf("direction"),
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val raw = params["direction"]?.jsonPrimitive?.contentOrNull ?: error("direction is required")
            val direction = DeviceScrollDirection.entries
                .firstOrNull { it.label == raw.trim().lowercase() }
                ?: error("unknown direction: $raw")
            val amount = params["amount"]?.jsonPrimitive?.floatOrNull ?: 0.6f
            val maxSwipes = (params["max_swipes"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, 20)
            val targetText = params["target_text"]?.jsonPrimitive?.contentOrNull
            val expectPkg = params["expect_pkg"]?.jsonPrimitive?.contentOrNull
            val duration = (params["duration_ms"]?.jsonPrimitive?.intOrNull ?: 400).toLong().coerceIn(50L, 5_000L)
            withDeviceService { service ->
                if (!expectPkg.isNullOrBlank() && !service.deviceForegroundMatches(expectPkg)) {
                    return@withDeviceService listOf(
                        UIMessagePart.Text(
                            deviceErrorPayload(
                                "WRONG_FOREGROUND",
                                "foreground is ${service.deviceForegroundPackage() ?: "unknown"}, " +
                                    "not $expectPkg; refused to act",
                            )
                        )
                    )
                }
                val outcome = withContext(Dispatchers.Default) {
                    service.deviceScroll(direction, amount, maxSwipes, targetText, duration)
                }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", outcome.ok)
                            put("swipes", outcome.swipes)
                            outcome.foundTarget?.let { found -> put("found_target", found) }
                            put("message", outcome.message)
                        }.toString()
                    )
                )
            }
        },
    )

    private fun buildDevicePingTool(): Tool = Tool(
        name = "device_ping",
        description = """
            Ping the device-control channel: returns hello plus the current accessibility level.
            A cheap way to confirm the channel is alive before a longer sequence.
        """.trimIndent().replace("\n", " "),
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            val status = DeviceControlState.refresh(context)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", true)
                        put("message", "hello from Air-Ko device control")
                        put("level", status.level.name.lowercase())
                    }.toString()
                )
            )
        },
    )

    private fun buildDeviceOcrTool(): Tool = Tool(
        name = "device_ocr",
        description = """
            Read text with offline OCR (Chinese + Latin; the model is bundled in the app and never uses
            the network). By default it screenshots the device and reads the screen - the fallback for
            screens with no readable view tree (some chat apps, games, canvases). Pass src="path" to read
            an existing image file instead (absolute, or relative to the app files directory; no
            screenshot needed). The returned @(x,y) are tap-ready pixel centers for device_tap.
            Optionally pass region="x,y,w,h" as 0-1 ratios (of the screen, or of the image when src is set).
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("region", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional 0-1 ratios \"x,y,w,h\", e.g. \"0,0.3,1,0.4\" for the middle band")
                    })
                    put("max_lines", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max lines to return (default 200)")
                    })
                    put("src", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional image path to read instead of screenshotting (absolute, or relative to the app files dir)")
                    })
                }
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val regionRaw = params["region"]?.jsonPrimitive?.contentOrNull
            val maxLines = (params["max_lines"]?.jsonPrimitive?.intOrNull ?: 200).coerceIn(1, 500)
            val srcRaw = params["src"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val metrics = context.resources.displayMetrics
            if (srcRaw.isNotEmpty()) {
                val file = resolveImageFile(context, srcRaw)
                val bitmap = file?.let {
                    runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull()
                }
                when {
                    file == null -> listOf(
                        UIMessagePart.Text(
                            deviceErrorPayload(
                                "SRC_NOT_FOUND",
                                "no image at \"$srcRaw\" (use an absolute path, or a path relative to the app files directory)"
                            )
                        )
                    )
                    bitmap == null -> listOf(
                        UIMessagePart.Text(
                            deviceErrorPayload("SRC_DECODE_FAILED", "\"$srcRaw\" is not a decodable image")
                        )
                    )
                    else -> {
                        val region = if (regionRaw.isNullOrBlank()) {
                            null
                        } else {
                            parseNormalizedRegion(regionRaw, bitmap.width, bitmap.height)
                        }
                        if (!regionRaw.isNullOrBlank() && region == null) {
                            bitmap.recycle()
                            listOf(
                                UIMessagePart.Text(
                                    deviceErrorPayload("BAD_REGION", "region must be \"x,y,w,h\" with 0-1 ratios")
                                )
                            )
                        } else {
                            val lines = withContext(Dispatchers.Default) {
                                DeviceOcrEngine.recognize(bitmap, region)
                            }
                            bitmap.recycle()
                            listOf(UIMessagePart.Text(ocrReport(lines, maxLines, "image:${file.name}")))
                        }
                    }
                }
            } else {
                val region = parseNormalizedRegion(regionRaw, metrics.widthPixels, metrics.heightPixels)
                if (!regionRaw.isNullOrBlank() && region == null) {
                    listOf(
                        UIMessagePart.Text(
                            deviceErrorPayload("BAD_REGION", "region must be \"x,y,w,h\" with 0-1 ratios")
                        )
                    )
                } else withDeviceService { service ->
                    val lines = withContext(Dispatchers.Default) { service.deviceOcrOnScreen(region) }
                    if (lines == null) {
                        return@withDeviceService listOf(
                            UIMessagePart.Text(
                                deviceErrorPayload(
                                    "OCR_UNAVAILABLE",
                                    "screenshot failed (needs Android 11+ and an unlocked, visible screen); " +
                                        "try device_screenshot to see what the device sees"
                                )
                            )
                        )
                    }
                    listOf(UIMessagePart.Text(ocrReport(lines, maxLines, "screen")))
                }
            }
        },
    )

    /** OCR 回执：来源 + 行数 + 文本 + 可点的中心坐标。 */
    private fun ocrReport(lines: List<DeviceOcrLine>, maxLines: Int, source: String): String {
        val shown = lines.take(maxLines)
        return buildString {
            append("source=").append(source)
            append("\nlines=").append(lines.size)
            if (lines.size > shown.size) append(" (truncated to ").append(shown.size).append(')')
            append("\n--- text ---\n")
            if (shown.isEmpty()) {
                append("(no text found; the screen may be blank, locked, or a pure image)\n")
            } else {
                shown.forEach { append(it.text).append('\n') }
            }
            append("--- tap targets ---\n")
            shown.forEach {
                append('"').append(it.text.replace('\n', ' ')).append("\" @(")
                    .append(it.centerX).append(',').append(it.centerY).append(")\n")
            }
        }.trimEnd()
    }

    /** src 解析：绝对路径直接用；相对路径依次在 files/cache/external-files 目录下找。 */
    private fun resolveImageFile(context: Context, raw: String): File? {
        val direct = File(raw)
        if (direct.isAbsolute) return direct.takeIf { it.isFile }
        val candidates = listOf(
            File(context.filesDir, raw),
            File(context.cacheDir, raw),
            context.getExternalFilesDir(null)?.let { File(it, raw) },
        )
        return candidates.filterNotNull().firstOrNull { it.isFile }
    }

    private fun buildDeviceWindowsTool(): Tool = Tool(
        name = "device_windows",
        description = """
            List the windows accessibility can currently see (type, package, bounds, focus, fullscreen).
            Use it to check split-screen / floating windows: device_ui only reads the focused window, so to
            act on another window tap it once first (or use device_tap_text's OCR fallback).
        """.trimIndent().replace("\n", " "),
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            withDeviceService { service ->
                val windows = service.deviceListWindows()
                val text = buildString {
                    append("windows=").append(windows.size).append('\n')
                    windows.forEachIndexed { index, window ->
                        append('#').append(index).append(' ').append(window.type)
                        window.packageName?.let { append(" pkg=").append(it) }
                        if (window.focused) append(" [focused]")
                        if (window.active) append(" [active]")
                        if (window.fullScreen) append(" [fullscreen]")
                        window.bounds?.let {
                            append(" @(").append(it.left).append(',').append(it.top)
                                .append('-').append(it.right).append(',').append(it.bottom).append(')')
                        }
                        window.title?.takeIf { it.isNotBlank() }?.let {
                            append(" \"").append(it.take(40)).append('"')
                        }
                        append('\n')
                    }
                    val appWindows = windows.count { it.type == "application" }
                    val split = appWindows > 1 || windows.any { it.type == "application" && !it.fullScreen }
                    append("split_or_multi_window=").append(split)
                }.trimEnd()
                listOf(UIMessagePart.Text(text))
            }
        },
    )

    private fun buildDeviceLongPressTool(): Tool = Tool(
        name = "device_long_press",
        description = """
            Long-press a point (pixel coordinates from device_ui). Opens context menus, selects text or
            grabs drag handles. Same effect as device_tap with long_press=true.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("x", buildJsonObject {
                        put("type", "integer")
                        put("description", "Screen X coordinate (pixels)")
                    })
                    put("y", buildJsonObject {
                        put("type", "integer")
                        put("description", "Screen Y coordinate (pixels)")
                    })
                    put("ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "Hold duration in ms (default 800, max 10000)")
                    })
                },
                required = listOf("x", "y"),
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val x = params["x"]?.jsonPrimitive?.intOrNull ?: error("x is required")
            val y = params["y"]?.jsonPrimitive?.intOrNull ?: error("y is required")
            val ms = (params["ms"]?.jsonPrimitive?.intOrNull ?: 800).toLong().coerceIn(400L, 10_000L)
            withDeviceService { service ->
                val ok = service.deviceTap(x.toFloat(), y.toFloat(), ms)
                listOf(
                    UIMessagePart.Text(
                        deviceActionResult(
                            ok = ok,
                            message = if (ok) "long-pressed ($x,$y) for ${ms}ms" else "gesture was rejected at ($x,$y)",
                        )
                    )
                )
            }
        },
    )

    private fun buildDeviceTapTextTool(): Tool = Tool(
        name = "device_tap_text",
        description = """
            Find a control by text/content-description/id and tap it: climbs to the nearest clickable
            ancestor and performs a node-level click (more reliable than coordinates). If the view tree is
            unreadable (some chat apps, games) it falls back to OCR on a screenshot automatically. Reports
            honestly when not found - it never taps blind. Pass expect="..." to verify the tap (waits for
            that text to appear). Pass expect_pkg to refuse acting on the wrong app.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("description", "Visible text to match (contains by default)")
                    })
                    put("desc", buildJsonObject {
                        put("type", "string")
                        put("description", "Match by content description instead")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Match by view id (the short part, e.g. send_btn, is enough)")
                    })
                    put("index", buildJsonObject {
                        put("type", "integer")
                        put("description", "Which match to tap when several match (default 0 = first)")
                    })
                    put("exact", buildJsonObject {
                        put("type", "boolean")
                        put("description", "true = text must be exactly equal (default false = contains)")
                    })
                    put("timeout_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "Keep looking this long before giving up (default 1500)")
                    })
                    put("duration_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "Press duration in ms (default 80)")
                    })
                    put("expect", buildJsonObject {
                        put("type", "string")
                        put("description", "After tapping, wait for this text to appear as proof of success")
                    })
                    put("expect_pkg", buildJsonObject {
                        put("type", "string")
                        put("description", "Refuse to act unless this package is in the foreground")
                    })
                    put("ocr", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Allow the OCR fallback when the view tree has nothing (default true)")
                    })
                    put("wait_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "How long to wait for expect after tapping (default 900)")
                    })
                }
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val matcher = matcherFromParams(params)
            if (matcher.isEmpty) {
                listOf(
                    UIMessagePart.Text(deviceErrorPayload("NO_QUERY", "need at least one of text/desc/id"))
                )
            } else run {
            val index = (params["index"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 20)
            val timeout = (params["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 1_500).toLong().coerceIn(0L, 30_000L)
            val duration = (params["duration_ms"]?.jsonPrimitive?.intOrNull ?: 80).toLong().coerceIn(20L, 10_000L)
            val expect = params["expect"]?.jsonPrimitive?.contentOrNull
            val expectPkg = params["expect_pkg"]?.jsonPrimitive?.contentOrNull
            val ocr = params["ocr"]?.jsonPrimitive?.booleanOrNull ?: true
            val waitMs = (params["wait_ms"]?.jsonPrimitive?.intOrNull ?: 900).toLong().coerceIn(100L, 30_000L)
            withDeviceService { service ->
                val outcome = withContext(Dispatchers.Default) {
                    service.deviceTapText(
                        context, matcher, index, timeout, duration, ocr, expect, expectPkg, waitMs,
                    )
                }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", outcome.ok)
                            outcome.foundBy?.let { put("found_by", it) }
                            outcome.tappedBy?.let { put("tapped_by", it) }
                            outcome.matchedText?.let { put("matched", it) }
                            put("message", outcome.message)
                        }.toString()
                    )
                )
            }
            }
        },
    )

    private fun buildDeviceScrollToTextTool(): Tool = Tool(
        name = "device_scroll_to_text",
        description = """
            Scroll screen by screen while looking for a target (view tree first, OCR fallback). Returns
            which screen it was found on, optionally taps it (tap=true) and verifies with expect. Reports
            honestly when it hit the end or the time limit - it never taps when not found.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("description", "Text to look for (contains by default)")
                    })
                    put("desc", buildJsonObject {
                        put("type", "string")
                        put("description", "Match by content description instead")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Match by view id instead")
                    })
                    put("exact", buildJsonObject {
                        put("type", "boolean")
                        put("description", "true = exact text match (default false = contains)")
                    })
                    put("index", buildJsonObject {
                        put("type", "integer")
                        put("description", "Which match counts (default 0 = first)")
                    })
                    put("direction", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            DeviceScrollDirection.entries.forEach { add(it.label) }
                        })
                        put("description", "down (default) or up - direction to look")
                    })
                    put("max_scroll", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max screens to scroll (default 10, max 40)")
                    })
                    put("timeout_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "Overall time budget (default 12000)")
                    })
                    put("tap", buildJsonObject {
                        put("type", "boolean")
                        put("description", "true = tap the target once found (default false)")
                    })
                    put("expect", buildJsonObject {
                        put("type", "string")
                        put("description", "When tapping: wait for this text as proof")
                    })
                    put("expect_pkg", buildJsonObject {
                        put("type", "string")
                        put("description", "Refuse to act unless this package is in the foreground")
                    })
                    put("ocr", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Allow the OCR fallback (default true)")
                    })
                }
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val matcher = matcherFromParams(params)
            if (matcher.isEmpty) {
                listOf(
                    UIMessagePart.Text(deviceErrorPayload("NO_QUERY", "need at least one of text/desc/id"))
                )
            } else run {
            val rawDirection = params["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
            val direction = DeviceScrollDirection.entries.firstOrNull { it.label == rawDirection.trim().lowercase() }
                ?: error("unknown direction: $rawDirection")
            val index = (params["index"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 20)
            val maxScroll = (params["max_scroll"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 40)
            val timeout = (params["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 12_000).toLong().coerceIn(500L, 120_000L)
            val tap = params["tap"]?.jsonPrimitive?.booleanOrNull ?: false
            val expect = params["expect"]?.jsonPrimitive?.contentOrNull
            val expectPkg = params["expect_pkg"]?.jsonPrimitive?.contentOrNull
            val ocr = params["ocr"]?.jsonPrimitive?.booleanOrNull ?: true
            withDeviceService { service ->
                val outcome = withContext(Dispatchers.Default) {
                    service.deviceScrollToText(
                        context, matcher, direction, maxScroll, timeout, tap, index, ocr, expect, expectPkg,
                    )
                }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", outcome.ok)
                            put("screens_scrolled", outcome.screens)
                            outcome.foundBy?.let { put("found_by", it) }
                            put("tapped", outcome.tapped)
                            put("message", outcome.message)
                        }.toString()
                    )
                )
            }
            }
        },
    )

    private fun buildDevicePasteTool(): Tool = Tool(
        name = "device_paste",
        description = """
            Paste text via the clipboard: writes the clipboard, focuses the input field and pastes
            (channels tried in order: node-level ACTION_PASTE, long-press paste menu, direct write as the
            last resort - the outcome says which one worked). Fixes apps that reject accessibility typing.
            The previous clipboard content usually cannot be restored (Android blocks background reads).
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("description", "Text to paste")
                    })
                    put("x", buildJsonObject {
                        put("type", "integer")
                        put("description", "Optional: tap this point first to focus the field (pixels)")
                    })
                    put("y", buildJsonObject {
                        put("type", "integer")
                        put("description", "Y of the focus point (pixels)")
                    })
                    put("restore", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Restore the old clipboard afterwards when readable (default true)")
                    })
                    put("expect_pkg", buildJsonObject {
                        put("type", "string")
                        put("description", "Refuse to act unless this package is in the foreground")
                    })
                },
                required = listOf("text"),
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val text = params["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
            val x = params["x"]?.jsonPrimitive?.intOrNull
            val y = params["y"]?.jsonPrimitive?.intOrNull
            val restore = params["restore"]?.jsonPrimitive?.booleanOrNull ?: true
            val expectPkg = params["expect_pkg"]?.jsonPrimitive?.contentOrNull
            withDeviceService { service ->
                if (!expectPkg.isNullOrBlank() && !service.deviceForegroundMatches(expectPkg)) {
                    return@withDeviceService listOf(
                        UIMessagePart.Text(
                            deviceErrorPayload(
                                "WRONG_FOREGROUND",
                                "foreground is ${service.deviceForegroundPackage() ?: "unknown"}, " +
                                    "not $expectPkg; refused to act",
                            )
                        )
                    )
                }
                val outcome = withContext(Dispatchers.Default) {
                    service.devicePasteText(context, text, restore, x, y)
                }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", outcome.ok)
                            outcome.how?.let { put("how", it) }
                            outcome.applied?.let { put("field_text", it) }
                            put("message", outcome.message)
                        }.toString()
                    )
                )
            }
        },
    )

    private fun buildDeviceNotifyTool(): Tool = Tool(
        name = "device_notify",
        description = """
            Post, update or clear a notification on the phone - the phone-side way to get the owner's
            attention while they are not looking at the chat. The same id updates the same notification.
            Modes (checked in this order, only one applies): report=... writes one line into the persistent
            device-control notification; clear=1 clears the notification with this id; island="SCENE" posts
            an origin-island-style notification (a vendor extension on some phones, a normal notification
            everywhere else); media=1 posts a media-style notification (audio card) with playing defaulting
            to 1; promoted=1 posts a progress notification (live style where the system supports it, a
            plain progress bar otherwise); anything else posts a normal notification. ongoing defaults to 1
            (cannot be swiped away).
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "Notification title (default Air-Ko)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "Notification body text")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Notification id (default 1); the same id updates in place")
                    })
                    put("ongoing", buildJsonObject {
                        put("type", "integer")
                        put("description", "1 = cannot be swiped away (default 1)")
                    })
                    put("clear", buildJsonObject {
                        put("type", "integer")
                        put("description", "1 = clear the notification with this id instead of posting")
                    })
                    put("island", buildJsonObject {
                        put("type", "string")
                        put("description", "Origin-island scene name (e.g. HEALTH_REGISTER); posts island-style")
                    })
                    put("op", buildJsonObject {
                        put("type", "integer")
                        put("description", "With island: 0 create (default), 1 update, 2 end")
                    })
                    put("media", buildJsonObject {
                        put("type", "integer")
                        put("description", "1 = media-style notification (can ride the audio widget)")
                    })
                    put("playing", buildJsonObject {
                        put("type", "integer")
                        put("description", "With media=1: 1 playing (default), 0 paused/ended")
                    })
                    put("promoted", buildJsonObject {
                        put("type", "integer")
                        put("description", "1 = progress/live-style notification")
                    })
                    put("progress", buildJsonObject {
                        put("type", "integer")
                        put("description", "With promoted=1: 0-100 (default 60)")
                    })
                    put("report", buildJsonObject {
                        put("type", "string")
                        put("description", "Write this line into the persistent device-control notification")
                    })
                }
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val title = params["title"]?.jsonPrimitive?.contentOrNull ?: "Air-Ko"
            val content = params["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val id = params["id"].intOr(DeviceControlNotifier.DEF_ID)
            val ongoing = params["ongoing"].intOr(1) != 0
            val clear = params["clear"].intOr(0) == 1
            val island = params["island"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val media = params["media"].intOr(0) == 1
            val playing = params["playing"].intOr(1) != 0
            val promoted = params["promoted"].intOr(0) == 1
            val progress = params["progress"].intOr(60)
            val report = params["report"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val op = params["op"].intOr(0)
            val message = when {
                report != null -> DeviceControlNotifier.setReport(context, report)
                clear -> DeviceControlNotifier.cancel(context, id)
                island != null -> DeviceControlNotifier.island(context, id, op, island, title, content, ongoing)
                media -> DeviceControlNotifier.media(context, id, title, content, playing)
                promoted -> DeviceControlNotifier.promoted(context, id, title, content, progress)
                else -> DeviceControlNotifier.send(context, id, title, content, ongoing)
            }
            listOf(
                UIMessagePart.Text(
                    deviceActionResult(!message.startsWith("error"), message)
                )
            )
        },
    )

    private fun buildDeviceBuzzTool(): Tool = Tool(
        name = "device_buzz",
        description = """
            Vibrate the phone to nudge the owner without sound (no notification, no screen wake).
            duration_ms defaults to 300 (clamped to 10-10000); dry=1 only checks the channel
            without vibrating.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("duration_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "Vibration length in ms (default 300, max 10000)")
                    })
                    put("dry", buildJsonObject {
                        put("type", "integer")
                        put("description", "1 = do not vibrate, just report what would happen")
                    })
                }
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val duration = params["duration_ms"].intOr(300).coerceIn(10, 10_000)
            val dry = params["dry"].flag()
            val message = if (dry) {
                "dry: would buzz ${duration}ms"
            } else {
                deviceBuzz(context, duration.toLong())
            }
            listOf(UIMessagePart.Text(deviceActionResult(!message.startsWith("error"), message)))
        },
    )

    private fun buildDeviceDownloadTool(): Tool = Tool(
        name = "device_download",
        description = """
            Download a file with the phone itself (http/https). Files up to 8MB are saved before this call
            returns; larger (or unknown-size) downloads continue in the background with a progress
            notification - poll them with device_download_status. Only two destinations are allowed: the
            app download dir (leave dir empty) or the system Downloads dir (dir="Download", needs the
            all-files access permission). Optional sha256 verification, optional unzip=1 (extracts a .zip
            into a folder next to it), force=1 to overwrite an existing file or exceed the 2GB cap.
            headers accepts a JSON object or "Key: value" lines.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("url", buildJsonObject {
                        put("type", "string")
                        put("description", "http/https URL to download")
                    })
                    put("dir", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional: \"\" (app download dir, default) or \"Download\" (system Downloads)")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional file name (default: from the URL)")
                    })
                    put("headers", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional request headers: JSON object or \"Key: value\" lines")
                    })
                    put("sha256", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional expected sha256; mismatch = error and the bad file is deleted")
                    })
                    put("unzip", buildJsonObject {
                        put("type", "integer")
                        put("description", "1 = unzip a .zip into a sibling folder")
                    })
                    put("force", buildJsonObject {
                        put("type", "integer")
                        put("description", "1 = overwrite existing file / allow over 2GB")
                    })
                },
                required = listOf("url"),
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val url = params["url"]?.jsonPrimitive?.contentOrNull ?: error("url is required")
            when (val outcome = DeviceDownloader.download(
                context = context,
                url = url,
                dirSpec = params["dir"]?.jsonPrimitive?.contentOrNull,
                name = params["name"]?.jsonPrimitive?.contentOrNull,
                headersRaw = params["headers"]?.jsonPrimitive?.contentOrNull,
                expectedSha256 = params["sha256"]?.jsonPrimitive?.contentOrNull,
                unzip = params["unzip"].flag(),
                force = params["force"].flag(),
            )) {
                is DeviceDownloader.Outcome.Saved -> listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", true)
                            put("path", outcome.path)
                            put("bytes", outcome.bytes)
                            put("sha256", outcome.sha256)
                            put("sha256_verified", outcome.verified)
                            outcome.unzippedTo?.let { put("unzipped_to", it) }
                            if (outcome.unzippedTo != null) put("unzipped_count", outcome.unzippedCount)
                        }.toString()
                    )
                )

                is DeviceDownloader.Outcome.Started -> listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", true)
                            put("id", outcome.id)
                            put("name", outcome.name)
                            put("message", outcome.note)
                        }.toString()
                    )
                )

                is DeviceDownloader.Outcome.Failed -> listOf(
                    UIMessagePart.Text(deviceErrorPayload("DOWNLOAD_FAILED", outcome.reason))
                )
            }
        },
    )

    private fun buildDeviceDownloadStatusTool(): Tool = Tool(
        name = "device_download_status",
        description = """
            Poll background downloads started by device_download: per-task state, bytes, speed, saved path
            and sha256. Pass id=... for one task, or omit it to list this session's tasks (only the latest
            20 are kept; the list lives in memory and is forgotten when the app process restarts).
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Task id returned by device_download")
                    })
                }
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val id = params["id"]?.jsonPrimitive?.contentOrNull
            listOf(UIMessagePart.Text(DeviceDownloader.statusText(id)))
        },
    )

    // ---------------------------------------------------------------------------------------------
    // Shizuku（shell / ADB 身份）：安装、卸载、强制停止、系统设置、input 兜底、系统截屏
    // ---------------------------------------------------------------------------------------------

    /** Shizuku 不可用时的统一回执（区分「没在跑 / 没授权 / 版本太旧」）。 */
    private fun shizukuUnavailablePayload(): UIMessagePart.Text {
        val info = ShizukuSupport.info()
        return UIMessagePart.Text(
            when (info.state) {
                ShizukuState.NotRunning -> deviceErrorPayload(
                    "SHIZUKU_NOT_RUNNING",
                    "Shizuku is not running. Install Shizuku (https://shizuku.rikka.app/download/), start it " +
                        "(Android 11+ can start it on-device with wireless debugging), then authorize Air-Ko " +
                        "in Settings - Device control - Shizuku.",
                )
                ShizukuState.PermissionNeeded -> deviceErrorPayload(
                    "SHIZUKU_PERMISSION_NEEDED",
                    "Shizuku is running but Air-Ko is not authorized yet. Open Settings - Device control - " +
                        "Shizuku and tap \"Request permission\".",
                )
                ShizukuState.Unsupported -> deviceErrorPayload(
                    "SHIZUKU_UNSUPPORTED",
                    "The installed Shizuku is too old (pre-v11); please update Shizuku.",
                )
                ShizukuState.Ready -> deviceErrorPayload("SHIZUKU_ERROR", "Shizuku call failed")
            }
        )
    }

    /** 命令回执：exit + stdout/stderr，超长截断（模型不需要全部日志）。 */
    private fun shizukuResultPayload(result: ShizukuExecResult, maxChars: Int = 20_000): String {
        val text = result.describe()
        return if (text.length <= maxChars) text else text.take(maxChars) + "\n…(truncated)"
    }

    private fun buildDeviceShellTool(): Tool = Tool(
        name = "device_shell",
        description = """
            Run a shell command as the ADB (shell) user through Shizuku - the escape hatch for things
            accessibility cannot do: pm / am / settings / svc / input / dumpsys and anything else the shell
            user may run. Requires Shizuku to be running and authorized (device_status reports this). Prefer
            the dedicated device_* tools for taps, typing and screenshots; this runs on the phone itself,
            not inside the Linux workspace (use the workspace shell tools for that).
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Shell command line, executed via sh -c")
                    })
                    put("timeout_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "Give up after this many ms (default 30000, max 300000)")
                    })
                },
                required = listOf("command"),
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val command = params["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val timeout = (params["timeout_ms"]?.jsonPrimitive?.intOrNull ?: 30_000).toLong()
                .coerceIn(1_000L, 300_000L)
            if (command.isEmpty()) {
                listOf(UIMessagePart.Text(deviceErrorPayload("BAD_ARGS", "command is required")))
            } else if (!ShizukuSupport.info().ready) {
                listOf(shizukuUnavailablePayload())
            } else {
                listOf(UIMessagePart.Text(shizukuResultPayload(ShizukuSupport.exec(command, timeout))))
            }
        },
    )

    private fun buildDeviceInstallTool(): Tool = Tool(
        name = "device_install",
        description = """
            Install an APK through Shizuku (pm install as the shell user). Pass a local APK path: absolute,
            or relative to the app files / cache / app download directories (device_download saves there).
            The APK is streamed to /data/local/tmp and installed; no package-installer UI is involved.
            grant_all=true also grants all runtime permissions (works on most ROMs via shell).
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Local APK path (absolute, or relative to app files/cache/download dirs)")
                    })
                    put("reinstall", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Replace an existing install (default true)")
                    })
                    put("grant_all", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Also grant all runtime permissions (default false)")
                    })
                },
                required = listOf("path"),
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val pathRaw = params["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val reinstall = params["reinstall"]?.jsonPrimitive?.booleanOrNull ?: true
            val grantAll = params["grant_all"]?.jsonPrimitive?.booleanOrNull ?: false
            val file = if (pathRaw.isEmpty()) null else resolveLocalFile(context, pathRaw)
            if (file == null || !file.isFile) {
                listOf(
                    UIMessagePart.Text(
                        deviceErrorPayload(
                            "APK_NOT_FOUND",
                            "no APK at \"$pathRaw\" (absolute path, or relative to the app files/cache/download dirs)",
                        )
                    )
                )
            } else if (!ShizukuSupport.info().ready) {
                listOf(shizukuUnavailablePayload())
            } else {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { file.readBytes() }.getOrNull()
                }
                if (bytes == null) {
                    listOf(UIMessagePart.Text(deviceErrorPayload("APK_READ_FAILED", "cannot read ${file.absolutePath}")))
                } else {
                    val remote = "/data/local/tmp/airko_install_${System.currentTimeMillis()}.apk"
                    val push = ShizukuSupport.writeRemoteFile(remote, bytes)
                    if (!push.ok) {
                        listOf(UIMessagePart.Text(shizukuResultPayload(push)))
                    } else {
                        val flags = buildString {
                            if (reinstall) append(" -r")
                            if (grantAll) append(" -g")
                        }
                        val install = ShizukuSupport.exec(
                            "pm install$flags \"$remote\" && rm -f \"$remote\"",
                            240_000L,
                        )
                        listOf(UIMessagePart.Text(shizukuResultPayload(install)))
                    }
                }
            }
        },
    )

    private fun buildDeviceUninstallTool(): Tool = Tool(
        name = "device_uninstall",
        description = """
            Uninstall a package through Shizuku (pm uninstall). keep_data=true keeps the app data and
            cache (pm uninstall -k). Use device_apps to find the package name first.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("package", buildJsonObject {
                        put("type", "string")
                        put("description", "Package name, e.g. com.example.app")
                    })
                    put("keep_data", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Keep app data and cache (default false)")
                    })
                },
                required = listOf("package"),
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val packageName = params["package"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val keepData = params["keep_data"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!isPackageName(packageName)) {
                listOf(UIMessagePart.Text(deviceErrorPayload("BAD_PACKAGE", "\"$packageName\" is not a package name")))
            } else if (!ShizukuSupport.info().ready) {
                listOf(shizukuUnavailablePayload())
            } else {
                val flag = if (keepData) " -k" else ""
                listOf(
                    UIMessagePart.Text(
                        shizukuResultPayload(ShizukuSupport.exec("pm uninstall$flag \"$packageName\"", 120_000L))
                    )
                )
            }
        },
    )

    private fun buildDeviceForceStopTool(): Tool = Tool(
        name = "device_force_stop",
        description = """
            Force-stop an app through Shizuku (am force-stop). Useful before driving an app from a clean
            start, or to kill a stuck app. Use device_apps to find the package name.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("package", buildJsonObject {
                        put("type", "string")
                        put("description", "Package name, e.g. com.example.app")
                    })
                },
                required = listOf("package"),
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val packageName = params["package"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (!isPackageName(packageName)) {
                listOf(UIMessagePart.Text(deviceErrorPayload("BAD_PACKAGE", "\"$packageName\" is not a package name")))
            } else if (!ShizukuSupport.info().ready) {
                listOf(shizukuUnavailablePayload())
            } else {
                listOf(
                    UIMessagePart.Text(
                        shizukuResultPayload(ShizukuSupport.exec("am force-stop \"$packageName\"", 30_000L))
                    )
                )
            }
        },
    )

    private fun buildDeviceSettingsTool(): Tool = Tool(
        name = "device_settings",
        description = """
            Read or write Android settings through Shizuku (the settings command): namespace system /
            secure / global, action get / list / put / delete. Handy for automation knobs such as
            window_animation_scale, screen_off_timeout or default_input_method. Only values the shell user
            is allowed to touch will be accepted by the system.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("namespace", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("system"); add("secure"); add("global") })
                        put("description", "system (default), secure or global")
                    })
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("get"); add("list"); add("put"); add("delete") })
                        put("description", "get (default), list, put or delete")
                    })
                    put("key", buildJsonObject {
                        put("type", "string")
                        put("description", "Setting key, e.g. screen_off_timeout (required for get/put/delete)")
                    })
                    put("value", buildJsonObject {
                        put("type", "string")
                        put("description", "New value (required for put)")
                    })
                },
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val namespace = params["namespace"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "system"
            val action = params["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "get"
            val key = params["key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val value = params["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val command = when {
                namespace !in setOf("system", "secure", "global") -> null
                action == "list" -> "settings list $namespace"
                action == "get" && key.isEmpty() -> null
                action == "get" -> "settings get $namespace \"$key\""
                action == "put" && (key.isEmpty() || value.isEmpty()) -> null
                action == "put" -> "settings put $namespace \"$key\" \"$value\""
                action == "delete" && key.isEmpty() -> null
                action == "delete" -> "settings delete $namespace \"$key\""
                else -> null
            }
            if (command == null) {
                listOf(
                    UIMessagePart.Text(
                        deviceErrorPayload(
                            "BAD_ARGS",
                            "namespace must be system/secure/global; action get/put/delete needs key (put also needs value)",
                        )
                    )
                )
            } else if (!ShizukuSupport.info().ready) {
                listOf(shizukuUnavailablePayload())
            } else {
                listOf(UIMessagePart.Text(shizukuResultPayload(ShizukuSupport.exec(command, 30_000L))))
            }
        },
    )

    private fun buildDeviceInputTool(): Tool = Tool(
        name = "device_input",
        description = """
            Inject input events through Shizuku (the input command) - the fallback when the accessibility
            service is unavailable: kind=tap (x,y), swipe (x,y,x2,y2[,duration_ms]), text (text) or keyevent
            (keycode: a number like 4 or a name like KEYCODE_BACK). Coordinates are in screen pixels, same as
            device_tap. Prefer device_tap/device_swipe/device_type while accessibility works - they are
            faster and report the real outcome.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("kind", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("tap"); add("swipe"); add("text"); add("keyevent") })
                        put("description", "tap, swipe, text or keyevent")
                    })
                    put("x", buildJsonObject { put("type", "integer"); put("description", "Start X (tap/swipe)") })
                    put("y", buildJsonObject { put("type", "integer"); put("description", "Start Y (tap/swipe)") })
                    put("x2", buildJsonObject { put("type", "integer"); put("description", "End X (swipe)") })
                    put("y2", buildJsonObject { put("type", "integer"); put("description", "End Y (swipe)") })
                    put("duration_ms", buildJsonObject { put("type", "integer"); put("description", "Swipe duration in ms (default 300)") })
                    put("text", buildJsonObject { put("type", "string"); put("description", "Text to type (kind=text)") })
                    put("keycode", buildJsonObject { put("type", "string"); put("description", "Key code or name (kind=keyevent)") })
                },
                required = listOf("kind"),
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val kind = params["kind"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            val x = params["x"]?.jsonPrimitive?.intOrNull
            val y = params["y"]?.jsonPrimitive?.intOrNull
            val x2 = params["x2"]?.jsonPrimitive?.intOrNull
            val y2 = params["y2"]?.jsonPrimitive?.intOrNull
            val duration = (params["duration_ms"]?.jsonPrimitive?.intOrNull ?: 300).coerceIn(1, 20_000)
            val text = params["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val keycode = params["keycode"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val command = when (kind) {
                "tap" -> if (x != null && y != null) "input tap $x $y" else null
                "swipe" -> if (x != null && y != null && x2 != null && y2 != null) {
                    "input swipe $x $y $x2 $y2 $duration"
                } else {
                    null
                }
                "text" -> if (text.isNotEmpty()) "input text '${text.replace("'", "'\\''")}'" else null
                "keyevent" -> if (keycode.matches(Regex("[A-Za-z0-9_]+"))) "input keyevent $keycode" else null
                else -> null
            }
            if (command == null) {
                listOf(
                    UIMessagePart.Text(
                        deviceErrorPayload("BAD_ARGS", "check kind and its parameters (tap: x,y; swipe: x,y,x2,y2; text; keyevent)")
                    )
                )
            } else if (!ShizukuSupport.info().ready) {
                listOf(shizukuUnavailablePayload())
            } else {
                listOf(UIMessagePart.Text(shizukuResultPayload(ShizukuSupport.exec(command, 30_000L))))
            }
        },
    )

    private fun buildDeviceScreencapTool(): Tool = Tool(
        name = "device_screencap",
        description = """
            Take a screenshot through Shizuku (screencap as the shell user) - works on any Android version
            and does not need the accessibility service. Use it when device_screenshot says the system does
            not support accessibility screenshots. The image is scaled for the model the same way.
        """.trimIndent().replace("\n", " "),
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = {
            if (!ShizukuSupport.info().ready) {
                listOf(shizukuUnavailablePayload())
            } else {
                val remote = "/data/local/tmp/airko_shot_${System.currentTimeMillis()}.png"
                val command = "screencap -p \"$remote\" && base64 \"$remote\"; rm -f \"$remote\""
                val result = ShizukuSupport.exec(command, 60_000L)
                val base64 = result.stdout.filterNot { it.isWhitespace() }
                if (!result.ok || base64.isEmpty()) {
                    listOf(UIMessagePart.Text(shizukuResultPayload(result)))
                } else {
                    val png = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
                    val bitmap = png?.let {
                        withContext(Dispatchers.Default) {
                            android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
                        }
                    }
                    if (bitmap == null) {
                        listOf(UIMessagePart.Text(deviceErrorPayload("DECODE_FAILED", "cannot decode the captured PNG")))
                    } else {
                        val jpeg = withContext(Dispatchers.Default) { bitmap.toScaledJpegBytes() }
                        val encoded = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                        listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("ok", true)
                                    put("width", bitmap.width)
                                    put("height", bitmap.height)
                                    put("bytes", jpeg.size)
                                    put("source", "shizuku screencap")
                                }.toString()
                            ),
                            UIMessagePart.Image("data:image/jpeg;base64,$encoded"),
                        )
                    }
                }
            }
        },
    )

    /** 包名合法性（防止把任意字符串拼进 shell）。 */
    private fun isPackageName(value: String): Boolean =
        value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))

    /** 本机文件解析：绝对路径直接用；相对路径依次在 files/cache/external-files/应用下载目录下找。 */
    private fun resolveLocalFile(context: Context, raw: String): File? {
        val direct = File(raw)
        if (direct.isAbsolute) return direct.takeIf { it.isFile }
        val candidates = listOf(
            File(context.filesDir, raw),
            File(context.cacheDir, raw),
            context.getExternalFilesDir(null)?.let { File(it, raw) },
            File(DeviceDownloader.appDownloadDir(context), raw),
        )
        return candidates.filterNotNull().firstOrNull { it.isFile }
    }

    private fun buildDeviceHelpTool(): Tool = Tool(
        name = "device_help",
        description = """
            List every device-control tool with a one-line summary, or pass tool="device_tap" (the
            "device_" prefix is optional) for one tool's full description. Safe to call even when the
            accessibility channel is down, so it is a good first call on an unfamiliar device.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("tool", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional tool name; omit it to list all tools")
                    })
                }
            )
        },
        execute = { args ->
            val requested = args.jsonObject["tool"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            listOf(UIMessagePart.Text(deviceHelpText(requested)))
        },
    )

    /** device_help 的正文：清单（一行一条）或单个工具的完整说明。 */
    private fun deviceHelpText(requested: String?): String {
        val tools = getTools()
        if (requested == null) {
            return buildString {
                append("Air-Ko device control - ")
                append(tools.size)
                append(" tools (the Shizuku-based shell is intentionally not included):\n")
                tools.forEach { tool ->
                    append("- ")
                    append(tool.name)
                    append(": ")
                    append(tool.oneLineSummary())
                    append('\n')
                }
                append("Call device_help with tool=\"<name>\" (the device_ prefix is optional) for the full description.")
            }
        }
        val wanted = requested.trim()
        val tool = tools.firstOrNull { it.name == wanted }
            ?: tools.firstOrNull { it.name == "device_$wanted" }
            ?: tools.firstOrNull { it.name.removePrefix("device_") == wanted }
        return tool?.let { "${it.name}\n${it.description}" }
            ?: "error: unknown tool \"$wanted\"; call device_help without arguments to list every tool"
    }

    private fun buildDeviceActTestTool(): Tool = Tool(
        name = "device_acttest",
        description = """
            Run an end-to-end action self-test on Air-Ko's own tiny test window (takes about 5 seconds and
            briefly takes over the screen): tap, long-press, type, swipe and back - then report the
            window's own proof (taps counted, the text it received, whether it closed). Use this to check
            that device control really works on this phone instead of experimenting on the user's apps.
        """.trimIndent().replace("\n", " "),
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = {
            withDeviceService { service ->
                val result = withContext(Dispatchers.Default) { service.runActSelfTest(context) }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", result.ok)
                            if (!result.windowShown) {
                                put("error", "SELFTEST_NO_WINDOW")
                                put("hint", result.message)
                            } else {
                                put("tap_count", result.tapCount)
                                put("typed", result.typed)
                                put("typed_expected", result.typedExpected)
                                put("window_closed", result.windowClosed)
                                put("steps", buildJsonArray { result.steps.forEach { add(it) } })
                                put("message", result.message)
                            }
                        }.toString()
                    )
                )
            }
        },
    )

    private fun buildDeviceDoChainTool(): Tool = Tool(
        name = "device_do_chain",
        description = """
            Run several device actions in one call (saves round trips). steps is a JSON array; each step
            is {"act":"tap_text","text":"Send","expect":"Sent","timeout_ms":3000}. act is one of
            tap/tap_text/input/paste/longpress/swipe/key/scroll/wait_text/wait_stable/ui/ocr/shot; every
            other field (except act/expect/timeout_ms) is passed to that action as its parameter. expect
            (optional) is the step's post-condition: the tool waits for that text to appear (view tree
            first, screen OCR as fallback) and gives up after timeout_ms (default 5000; also used as the
            waiting budget of a wait_text/wait_stable step). stop_on_error=1 (default) stops at the
            failing step and returns what is on screen right now; 0 keeps going and records everything in
            detail. observe=1 appends the final screen summary (foreground package + visible texts).
            Only chain screens whose layout you have confirmed with device_ui first.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("steps", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "JSON array of steps, e.g. " +
                                "[{\"act\":\"tap_text\",\"text\":\"Send\",\"expect\":\"Sent\"}]",
                        )
                    })
                    put("stop_on_error", buildJsonObject {
                        put("type", "integer")
                        put("description", "1 (default) stop at the first failing step; 0 keep going")
                    })
                    put("observe", buildJsonObject {
                        put("type", "integer")
                        put("description", "1 = include the final screen summary")
                    })
                },
                required = listOf("steps"),
            )
        },
        needsApproval = approval(DEVICE_APPROVAL_DEFAULT),
        execute = { args ->
            val params = args.jsonObject
            val stepsRaw = params["steps"]
            val stepsArray: JsonArray? = when {
                stepsRaw == null -> null
                stepsRaw is JsonArray -> stepsRaw
                else -> runCatching {
                    Json.parseToJsonElement(stepsRaw.jsonPrimitive.content) as? JsonArray
                }.getOrNull()
            }
            if (stepsArray == null || stepsArray.isEmpty()) {
                listOf(
                    UIMessagePart.Text(
                        deviceErrorPayload(
                            "CHAIN_NO_STEPS",
                            "steps must be a non-empty JSON array (or a JSON-encoded string of one)",
                        )
                    )
                )
            } else {
                val stopOnError = params["stop_on_error"].intOr(1) != 0
                val observe = params["observe"].intOr(0) != 0
                withDeviceService { service ->
                    val payload = withContext(Dispatchers.Default) {
                        service.runDeviceChain(context, stepsArray, stopOnError, observe)
                    }
                    listOf(UIMessagePart.Text(payload))
                }
            }
        },
    )

    /** 统一处理「无障碍没连上」：给出结构化错误与修复指引，而不是抛异常。 */
    private suspend fun withDeviceService(
        block: suspend (DeviceControlService) -> List<UIMessagePart>,
    ): List<UIMessagePart> {
        val status = DeviceControlState.refresh(context)
        val service = DeviceControlState.service
        if (service == null) {
            return listOf(
                UIMessagePart.Text(
                    deviceErrorPayload(
                        error = if (status.authorized) "ACCESSIBILITY_DISCONNECTED" else "ACCESSIBILITY_DISABLED",
                        hint = if (status.authorized) {
                            "the service is authorized but not connected (killed or revoked by the system); " +
                                "ask the user to reopen Air-Ko's device control in system accessibility settings"
                        } else {
                            "device control needs the accessibility service; ask the user to enable " +
                                "'Air-Ko device control' in system settings > accessibility"
                        },
                    )
                )
            )
        }
        return block(service)
    }
}

/** 动作类工具的统一回执：模型需要「做了什么 + 成没成」，不是裸 JSON。 */
private fun deviceActionResult(ok: Boolean, message: String): String = buildJsonObject {
    put("ok", ok)
    put("message", message)
}.toString()

private fun deviceErrorPayload(error: String, hint: String): String = buildJsonObject {
    put("ok", false)
    put("error", error)
    put("hint", hint)
}.toString()

/** 从工具参数里取「按文字/描述/ID 找」的定位条件（tap_text / scroll_to_text 共用）。 */
private fun matcherFromParams(params: JsonObject): DeviceTextMatcher = DeviceTextMatcher(
    text = params["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
    desc = params["desc"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
    viewId = params["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
    exact = params["exact"]?.jsonPrimitive?.booleanOrNull ?: false,
)

/** 参数字段可能是数字/字符串：宽容解析成 Int（Reticle 的参数风格是数字）。 */
private fun JsonElement?.intOr(default: Int): Int {
    val primitive = this?.jsonPrimitive ?: return default
    return primitive.intOrNull
        ?: primitive.contentOrNull?.trim()?.toIntOrNull()
        ?: default
}

/** 宽容解析 0/1/true/false 开关。 */
private fun JsonElement?.flag(default: Boolean = false): Boolean {
    val primitive = this?.jsonPrimitive ?: return default
    return primitive.booleanOrNull
        ?: primitive.intOrNull?.let { it != 0 }
        ?: primitive.contentOrNull?.let { it == "1" || it.equals("true", ignoreCase = true) }
        ?: default
}

/** 解析 0~1 比例的 "x,y,w,h" 区域为像素矩形；非法返回 null（参数区分「没给」与「给错」）。 */
private fun parseNormalizedRegion(raw: String?, width: Int, height: Int): Rect? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.split(',').mapNotNull { it.trim().toFloatOrNull() }
    if (parts.size != 4) return null
    val (x, y, w, h) = parts
    val left = (x.coerceIn(0f, 1f) * width).toInt()
    val top = (y.coerceIn(0f, 1f) * height).toInt()
    val right = ((x + w).coerceIn(0f, 1f) * width).toInt()
    val bottom = ((y + h).coerceIn(0f, 1f) * height).toInt()
    if (right - left < 2 || bottom - top < 2) return null
    return Rect(left, top, right, bottom)
}

/** 参数字段可能是数字/字符串：宽容解析成 Float（坐标用）。 */
private fun JsonElement?.floatOr(default: Float): Float {
    val primitive = this?.jsonPrimitive ?: return default
    return primitive.floatOrNull ?: primitive.contentOrNull?.trim()?.toFloatOrNull() ?: default
}

private fun okText(ok: Boolean): String = if (ok) "ok" else "FAILED"

/** help 清单用：描述取第一句（完整描述用 tool="..." 查）。 */
private fun Tool.oneLineSummary(): String {
    val dot = description.indexOf(". ")
    return if (dot in 1 until description.length) description.take(dot + 1) else description
}

// ---------------------------------------------------------------------------------------------
// device_do_chain：一次提交多步；每步各带后置条件，不成立就停在那步并报屏幕现状
// ---------------------------------------------------------------------------------------------

/** 只允许「动作类」进链（不许从链里跑 notify／download／acttest，防越权与递归）。 */
private val CHAIN_ALLOWED_ACTS = setOf(
    "tap", "tap_text", "input", "paste", "longpress", "swipe", "key",
    "scroll", "wait_text", "wait_stable", "ui", "ocr", "shot",
)

private suspend fun DeviceControlService.runDeviceChain(
    context: Context,
    steps: JsonArray,
    stopOnError: Boolean,
    observe: Boolean,
): String {
    val detail = mutableListOf<JsonObject>()
    val startedAt = System.currentTimeMillis()
    var done = 0
    for ((index, element) in steps.withIndex()) {
        val step = element as? JsonObject
            ?: return chainStop(
                context, detail, index + 1, "(empty step)",
                "the step is not a JSON object", done, startedAt,
            )
        val act = step["act"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (act !in CHAIN_ALLOWED_ACTS) {
            return chainStop(
                context, detail, index + 1, act.ifEmpty { "(missing act)" },
                "act \"$act\" is not allowed inside a chain", done, startedAt,
            )
        }
        val expect = step["expect"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val timeoutMs = step["timeout_ms"].intOr(5_000).toLong().coerceIn(200L, 120_000L)
        val stepStartedAt = System.currentTimeMillis()
        val (actOk, resSummary) = runCatching {
            executeChainStep(context, act, step, timeoutMs)
        }.getOrElse { false to "exception: ${it.message ?: it.javaClass.simpleName}" }
        val stepMs = System.currentTimeMillis() - stepStartedAt
        var expectHit = true
        var expectMs = 0L
        if (expect != null) {
            val expectStartedAt = System.currentTimeMillis()
            expectHit = deviceWaitForMatch(DeviceTextMatcher(text = expect), timeoutMs)
            expectMs = System.currentTimeMillis() - expectStartedAt
        }
        detail += buildJsonObject {
            put("i", index + 1)
            put("act", act)
            put("ms", stepMs)
            put("ok", actOk)
            put("expect_hit", expectHit)
            put("expect_ms", expectMs)
            put("res", resSummary.take(200))
        }
        done = index + 1
        if (actOk && expectHit) continue
        if (stopOnError) {
            val why = if (!actOk) {
                "step ${index + 1} ($act) failed"
            } else {
                "step ${index + 1}: \"$expect\" did not appear within ${timeoutMs}ms"
            }
            return chainStop(context, detail, index + 1, act, why, index, startedAt)
        }
    }
    return buildJsonObject {
        put("ok", true)
        put("steps_done", done)
        put("total", steps.size)
        put("ms", System.currentTimeMillis() - startedAt)
        put("detail", buildJsonArray { detail.forEach { add(it) } })
        if (observe) put("screen", screenBrief(context))
    }.toString()
}

private suspend fun DeviceControlService.chainStop(
    context: Context,
    detail: List<JsonObject>,
    index: Int,
    act: String,
    why: String,
    done: Int,
    startedAt: Long,
): String = buildJsonObject {
    put("ok", false)
    put("steps_done", done)
    put("stop_at", index)
    put("stop_act", act.take(40))
    put("why", why)
    put("detail", buildJsonArray { detail.forEach { add(it) } })
    put("screen", screenBrief(context))
    put("ms", System.currentTimeMillis() - startedAt)
}.toString()

/** 执行链里的一步；返回（动作是否成功, 供 detail 记录的短回执）。 */
private suspend fun DeviceControlService.executeChainStep(
    context: Context,
    act: String,
    step: JsonObject,
    timeoutMs: Long,
): Pair<Boolean, String> = when (act) {
    "tap" -> {
        val x = step["x"].floatOr(Float.NaN)
        val y = step["y"].floatOr(Float.NaN)
        if (x.isNaN() || y.isNaN()) {
            false to "tap needs x and y"
        } else {
            val ok = deviceTap(x, y, step["ms"].intOr(50).toLong())
            ok to "tap(${x.toInt()},${y.toInt()}) ${okText(ok)}"
        }
    }

    "longpress" -> {
        val x = step["x"].floatOr(Float.NaN)
        val y = step["y"].floatOr(Float.NaN)
        if (x.isNaN() || y.isNaN()) {
            false to "longpress needs x and y"
        } else {
            val ok = deviceTap(x, y, step["ms"].intOr(900).toLong().coerceIn(500L, 10_000L))
            ok to "long_press(${x.toInt()},${y.toInt()}) ${okText(ok)}"
        }
    }

    "swipe" -> {
        val x1 = step["x1"].floatOr(Float.NaN)
        val y1 = step["y1"].floatOr(Float.NaN)
        val x2 = step["x2"].floatOr(Float.NaN)
        val y2 = step["y2"].floatOr(Float.NaN)
        if (x1.isNaN() || y1.isNaN() || x2.isNaN() || y2.isNaN()) {
            false to "swipe needs x1 y1 x2 y2"
        } else {
            val ok = deviceSwipe(x1, y1, x2, y2, step["ms"].intOr(300).toLong())
            ok to "swipe ${okText(ok)}"
        }
    }

    "input" -> {
        val text = step["text"]?.jsonPrimitive?.contentOrNull
        if (text.isNullOrEmpty()) {
            false to "input needs text"
        } else {
            val outcome = deviceTypeText(text, append = step["append"].flag())
            outcome.ok to outcome.message.ifBlank { "input ${okText(outcome.ok)}" }
        }
    }

    "paste" -> {
        val text = step["text"]?.jsonPrimitive?.contentOrNull
        if (text.isNullOrEmpty()) {
            false to "paste needs text"
        } else {
            val outcome = devicePasteText(
                context = context,
                text = text,
                restoreClipboard = step["restore"].flag(true),
                x = step["x"]?.jsonPrimitive?.intOrNull,
                y = step["y"]?.jsonPrimitive?.intOrNull,
            )
            outcome.ok to outcome.message
        }
    }

    "key" -> {
        val raw = step["key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val key = when (raw.lowercase()) {
            "返回" -> DeviceGlobalKey.Back
            "主页" -> DeviceGlobalKey.Home
            "最近任务" -> DeviceGlobalKey.Recents
            else -> DeviceGlobalKey.from(raw)
        }
        if (key == null) {
            false to "unknown key \"$raw\" (use back/home/recents/notifications/quick_settings/lock)"
        } else {
            val ok = deviceGlobalKey(key)
            ok to "key $raw ${okText(ok)}"
        }
    }

    "scroll" -> {
        val raw = step["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
        val direction = DeviceScrollDirection.entries.firstOrNull { it.label == raw.trim().lowercase() }
        if (direction == null) {
            false to "unknown direction \"$raw\" (use down/up/left/right)"
        } else {
            val amount = step["amount"].floatOr(0.6f).coerceIn(0.05f, 0.95f)
            val durationMs = step["duration_ms"].intOr(400).toLong()
            val outcome = deviceScroll(
                direction = direction,
                amount = amount,
                maxSwipes = 1,
                targetText = null,
                durationMs = durationMs,
            )
            outcome.ok to outcome.message
        }
    }

    "wait_text" -> {
        val matcher = matcherFromParams(step)
        if (matcher.isEmpty) {
            false to "wait_text needs at least one of text/desc/id"
        } else {
            val gone = step["gone"].flag()
            val ok = deviceWaitForMatch(matcher, timeoutMs, gone)
            ok to "wait_text ${matcher.describe()} ${okText(ok)}"
        }
    }

    "wait_stable" -> {
        val stableMs = step["stable_ms"].intOr(800).toLong().coerceIn(100L, 30_000L)
        val ok = deviceWaitStable(stableMs, timeoutMs)
        ok to "wait_stable ${stableMs}ms ${okText(ok)}"
    }

    "tap_text" -> {
        val matcher = matcherFromParams(step)
        if (matcher.isEmpty) {
            false to "tap_text needs at least one of text/desc/id"
        } else {
            val outcome = deviceTapText(
                context = context,
                matcher = matcher,
                index = step["index"].intOr(0),
                timeoutMs = 1_500L,
                durationMs = 80L,
                ocrFallback = step["ocr"].flag(true),
            )
            outcome.ok to outcome.message
        }
    }

    "ui" -> {
        val maxNodes = step["max_nodes"].intOr(step["max"].intOr(300)).coerceIn(20, 800)
        val nodes = deviceSnapshotUi(maxNodes = maxNodes)
        true to if (nodes.isEmpty()) {
            "ui: no accessible nodes"
        } else {
            "ui: ${nodes.size} nodes; ${nodes.formatForModel().take(200)}"
        }
    }

    "ocr" -> {
        val metrics = resources.displayMetrics
        val regionRaw = step["region"]?.jsonPrimitive?.contentOrNull
        val region = parseNormalizedRegion(regionRaw, metrics.widthPixels, metrics.heightPixels)
        if (!regionRaw.isNullOrBlank() && region == null) {
            false to "ocr: bad region \"$regionRaw\" (want 0-1 ratios \"x,y,w,h\")"
        } else {
            val lines = deviceOcrOnScreen(region)
            if (lines == null) {
                false to "ocr: screenshot unavailable (needs Android 11+ and a connected service)"
            } else {
                true to "ocr: ${lines.size} lines; " + lines.take(10).joinToString(" | ") { it.text.take(30) }
            }
        }
    }

    "shot" -> {
        val bitmap = deviceScreenshotBitmap()
        if (bitmap == null) {
            false to "shot: screenshot unavailable (needs Android 11+)"
        } else {
            val width = bitmap.width
            val height = bitmap.height
            runCatching { bitmap.recycle() }
            true to "shot: ${width}x${height} (image omitted inside chains; call device_screenshot for the picture)"
        }
    }

    else -> false to "act \"$act\" is not allowed inside a chain"
}

/** 停下/结束时回给模型看的屏幕现状：前台包名 + 树上的可见文字（树读不到就 OCR 前若干行）。 */
private suspend fun DeviceControlService.screenBrief(context: Context): JsonObject {
    val pkg = deviceForegroundPackage().orEmpty()
    val texts = mutableListOf<String>()
    val root = runCatching { rootInActiveWindow }.getOrNull()
    if (root != null) {
        collectScreenTexts(root, 0, texts, 12)
    }
    val via: String
    if (texts.isEmpty()) {
        val lines = runCatching { deviceOcrOnScreen() }.getOrNull()
        if (lines != null) {
            via = "ocr"
            lines.take(12).forEach { texts += it.text.take(40) }
        } else {
            via = "none"
        }
    } else {
        via = "tree"
    }
    return buildJsonObject {
        put("pkg", pkg)
        put("via", via)
        put("texts", buildJsonArray { texts.forEach { add(it) } })
    }
}

private fun collectScreenTexts(
    node: AccessibilityNodeInfo?,
    depth: Int,
    out: MutableList<String>,
    max: Int,
) {
    if (node == null || depth > 24 || out.size >= max) return
    runCatching {
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (text != null) out += text.take(40)
    }
    val count = runCatching { node.childCount }.getOrDefault(0)
    for (i in 0 until count) {
        if (out.size >= max) break
        collectScreenTexts(runCatching { node.getChild(i) }.getOrNull(), depth + 1, out, max)
    }
}
