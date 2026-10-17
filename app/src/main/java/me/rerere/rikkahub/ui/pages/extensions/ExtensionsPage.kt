package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.rikkahub.R
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.ChartLineData01
import me.rerere.hugeicons.stroke.ClipboardPaste
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.JavaScript
import me.rerere.hugeicons.stroke.MessageQuestion
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * 扩展页。
 *
 * 技能和工作区已经提到设置一级，这里只留通用扩展（快捷消息/提示词）和内置插件：
 * 内置插件就是助手可用的本地工具模块，直接开关当前助手的配置，等价于助手详情里的同名开关。
 */
@Composable
fun ExtensionsPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val assistant = settings.getCurrentAssistant()

    fun setLocalTool(option: LocalToolOption, enabled: Boolean) {
        vm.updateSettings { current ->
            current.copy(
                assistants = current.assistants.map { item ->
                    if (item.id != current.assistantId) {
                        item
                    } else {
                        item.copy(
                            localTools = if (enabled) {
                                item.localTools + option
                            } else {
                                item.localTools - option
                            }
                        )
                    }
                }
            )
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.extensions_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                val containerColor = MaterialTheme.colorScheme.primaryContainer
                val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                CardGroup(
                    title = { Text(stringResource(R.string.extensions_page_section_extensions)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.QuickMessages) },
                        leadingContent = { CardGroupIcon(HugeIcons.Zap, containerColor = containerColor, contentColor = contentColor) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_quick_messages)) },
                        supportingContent = { Text(stringResource(R.string.extensions_page_quick_messages_desc)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Prompts) },
                        leadingContent = { CardGroupIcon(HugeIcons.Book03, containerColor = containerColor, contentColor = contentColor) },
                        headlineContent = { Text(stringResource(R.string.extensions_page_prompts)) },
                        supportingContent = { Text(stringResource(R.string.extensions_page_prompts_desc)) },
                    )
                }
            }

            item {
                val checkedContainerColor = MaterialTheme.colorScheme.primaryContainer
                val checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                val uncheckedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                val uncheckedContentColor = MaterialTheme.colorScheme.onSurfaceVariant

                fun CardGroupScope.pluginItem(
                    option: LocalToolOption,
                    icon: androidx.compose.ui.graphics.vector.ImageVector,
                    title: Int,
                    description: Int,
                ) {
                    val checked = assistant.localTools.contains(option)
                    switchItem(
                        checked = checked,
                        onCheckedChange = { setLocalTool(option, it) },
                        leadingContent = {
                            CardGroupIcon(
                                icon = icon,
                                containerColor = if (checked) checkedContainerColor else uncheckedContainerColor,
                                contentColor = if (checked) checkedContentColor else uncheckedContentColor,
                            )
                        },
                        headlineContent = { Text(stringResource(title)) },
                        supportingContent = { Text(stringResource(description)) },
                    )
                }

                CardGroup(
                    title = { Text(stringResource(R.string.extensions_page_builtin_plugins)) },
                ) {
                    pluginItem(
                        option = LocalToolOption.TimeInfo,
                        icon = HugeIcons.Clock01,
                        title = R.string.assistant_page_local_tools_time_info_title,
                        description = R.string.assistant_page_local_tools_time_info_desc,
                    )
                    pluginItem(
                        option = LocalToolOption.JavascriptEngine,
                        icon = HugeIcons.JavaScript,
                        title = R.string.assistant_page_local_tools_javascript_engine_title,
                        description = R.string.assistant_page_local_tools_javascript_engine_desc,
                    )
                    pluginItem(
                        option = LocalToolOption.Clipboard,
                        icon = HugeIcons.ClipboardPaste,
                        title = R.string.assistant_page_local_tools_clipboard_title,
                        description = R.string.assistant_page_local_tools_clipboard_desc,
                    )
                    pluginItem(
                        option = LocalToolOption.Tts,
                        icon = HugeIcons.VolumeHigh,
                        title = R.string.assistant_page_local_tools_tts_title,
                        description = R.string.assistant_page_local_tools_tts_desc,
                    )
                    pluginItem(
                        option = LocalToolOption.AskUser,
                        icon = HugeIcons.MessageQuestion,
                        title = R.string.assistant_page_local_tools_ask_user_title,
                        description = R.string.assistant_page_local_tools_ask_user_desc,
                    )
                    pluginItem(
                        option = LocalToolOption.ScreenTime,
                        icon = HugeIcons.SmartPhone01,
                        title = R.string.assistant_page_local_tools_screen_time_title,
                        description = R.string.assistant_page_local_tools_screen_time_desc,
                    )
                    pluginItem(
                        option = LocalToolOption.Calendar,
                        icon = HugeIcons.Calendar03,
                        title = R.string.assistant_page_local_tools_calendar_title,
                        description = R.string.assistant_page_local_tools_calendar_desc,
                    )
                    pluginItem(
                        option = LocalToolOption.ChartDisplay,
                        icon = HugeIcons.ChartLineData01,
                        title = R.string.assistant_page_local_tools_chart_display_title,
                        description = R.string.assistant_page_local_tools_chart_display_desc,
                    )
                    pluginItem(
                        option = LocalToolOption.DeviceControl,
                        icon = HugeIcons.CursorPointer01,
                        title = R.string.assistant_page_local_tools_device_control_title,
                        description = R.string.assistant_page_local_tools_device_control_desc,
                    )
                }
            }
        }
    }
}
