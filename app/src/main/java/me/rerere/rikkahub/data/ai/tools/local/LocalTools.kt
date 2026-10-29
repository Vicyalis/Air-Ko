package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.tts.provider.TTSManager

/**
 * 总开关关闭时需要确认的本地工具：会改动设备/剪贴板、读取隐私数据或执行代码。
 * get_time_info 与 chart_display 只读或仅影响界面，不需要打断用户。
 */
private val LOCAL_TOOLS_REQUIRING_APPROVAL = setOf(
    "eval_javascript",
    "clipboard_tool",
    "text_to_speech",
    "get_screen_time",
    "calendar_query",
    "calendar_create",
)

private const val ASK_USER_TOOL_NAME = "ask_user"
private const val DEVICE_TOOL_PREFIX = "device_"

/**
 * ask_user 的「审批」界面就是提问界面，不能被总开关绕过；
 * 设备控制工具有自己的 deviceAgentAutoApprove / 单工具覆盖，也不走本地工具总开关。
 */
private fun Tool.applyLocalToolApproval(autoApprove: Boolean): Tool = when {
    name == ASK_USER_TOOL_NAME -> this
    name.startsWith(DEVICE_TOOL_PREFIX) -> this
    name in LOCAL_TOOLS_REQUIRING_APPROVAL -> copy(needsApproval = { !autoApprove })
    else -> this
}

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    val chartDisplayTool by lazy { buildChartDisplayTool() }

    private val deviceControlTools by lazy { DeviceControlTools(context, settingsStore) }

    fun getTools(
        options: List<LocalToolOption>,
        autoApprove: Boolean = false,
    ): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.ChartDisplay)) {
            tools.add(chartDisplayTool)
        }
        if (options.contains(LocalToolOption.DeviceControl)) {
            tools.addAll(deviceControlTools.getTools())
        }
        return tools.map { it.applyLocalToolApproval(autoApprove) }
    }
}
