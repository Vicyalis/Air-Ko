package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    // 隐藏入口：长按标题进 Debug 页（界面上没有任何提示）
                    Text(
                        text = stringResource(R.string.settings),
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { navController.navigate(Screen.Debug) },
                        ),
                    )
                },
                navigationIcon = {
                    BackButton()
                },
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
            if (settings.isNotConfigured()) {
                item {
                    ProviderConfigWarningCard(
                        onClick = { navController.navigate(Screen.SettingProvider) }
                    )
                }
            }

            item("generalSettings") {
                var colorMode by rememberColorMode()
                val containerColor = MaterialTheme.colorScheme.primaryContainer
                val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_general_settings)) },
                ) {
                    formItem {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CardGroupIcon(colorMode.icon(), containerColor = containerColor, contentColor = contentColor)
                            Text(stringResource(R.string.setting_page_color_mode))
                        }
                        ConnectedChoiceButtons(
                            options = ColorMode.entries,
                            selected = colorMode,
                            onSelect = {
                                colorMode = it
                                navController.navigate(Screen.Setting) {
                                    popUpTo(Screen.Setting) {
                                        inclusive = true
                                    }
                                }
                            },
                        ) { mode, selected ->
                            ConnectedChoiceLabel(
                                text = when (mode) {
                                    ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                                    ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                    ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                                },
                                selected = selected,
                                icon = mode.icon(),
                            )
                        }
                    }
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferences) },
                        leadingContent = { CardGroupIcon(HugeIcons.Settings03, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_preferences_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Assistant) },
                        leadingContent = { CardGroupIcon(HugeIcons.LookTop, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_assistant_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_assistant)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Harness) },
                        leadingContent = { CardGroupIcon(HugeIcons.Settings03, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.harness_page_desc)) },
                        headlineContent = { Text(stringResource(R.string.harness_page_title)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.QuickMessages) },
                        leadingContent = { CardGroupIcon(HugeIcons.MessageAdd01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.extensions_page_quick_messages_desc)) },
                        headlineContent = { Text(stringResource(R.string.assistant_page_quick_messages)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Prompts) },
                        leadingContent = { CardGroupIcon(HugeIcons.Book03, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.extensions_page_prompts_desc)) },
                        headlineContent = { Text(stringResource(R.string.extensions_page_prompts)) },
                    )
                    // 技能和工作区已从扩展页提到设置一级，和扩展并列
                    item(
                        onClick = { navController.navigate(Screen.Skills) },
                        leadingContent = { CardGroupIcon(HugeIcons.Puzzle, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.extensions_page_agent_skills_desc)) },
                        headlineContent = { Text(stringResource(R.string.extensions_page_agent_skills)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Workspaces) },
                        leadingContent = { CardGroupIcon(HugeIcons.Folder01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.extensions_page_workspace_desc)) },
                        headlineContent = { Text(stringResource(R.string.extensions_page_workspace)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingMcp) },
                        leadingContent = { CardGroupIcon(HugeIcons.McpServer, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_mcp_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_mcp)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingDeviceControl) },
                        leadingContent = { CardGroupIcon(HugeIcons.SmartPhone01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_device_control_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control)) },
                    )
                }
            }

            item("modelServices") {
                val containerColor = MaterialTheme.colorScheme.tertiaryContainer
                val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_model_and_services)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingModels) },
                        leadingContent = { CardGroupIcon(HugeIcons.AiMagic, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_default_model_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_default_model)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingProvider) },
                        leadingContent = { CardGroupIcon(HugeIcons.Brain02, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_providers_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_providers)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSearch) },
                        leadingContent = { CardGroupIcon(HugeIcons.GlobalSearch, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_search_service_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_search_service)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSpeech) },
                        leadingContent = { CardGroupIcon(HugeIcons.Megaphone01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_tts_service_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_tts_service)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingMedia) },
                        leadingContent = { CardGroupIcon(HugeIcons.Image02, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_media_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_media)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingMcp) },
                        leadingContent = { CardGroupIcon(HugeIcons.McpServer, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_mcp_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_mcp)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingWeb) },
                        leadingContent = { CardGroupIcon(HugeIcons.ServerStack01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server)) },
                    )
                }
            }

            item("dataSettings") {
                val containerColor = MaterialTheme.colorScheme.secondaryContainer
                val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_data_settings)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.Backup) },
                        leadingContent = { CardGroupIcon(HugeIcons.Database02, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_data_backup_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_data_backup)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingLinuxStorage) },
                        leadingContent = { CardGroupIcon(HugeIcons.Folder01, containerColor = containerColor, contentColor = contentColor) },
                        supportingContent = { Text(stringResource(R.string.setting_page_linux_storage_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_linux_storage)) },
                    )
                }
            }

        }
    }
}

private fun ColorMode.icon(): ImageVector = when (this) {
    ColorMode.SYSTEM -> HugeIcons.SmartPhone01
    ColorMode.LIGHT -> HugeIcons.Sun01
    ColorMode.DARK -> HugeIcons.Moon02
}

@Composable
private fun ProviderConfigWarningCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.error, MaterialShapes.Cookie9Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HugeIcons.Alert01,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onError,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_page_config_api_title),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    Text(
                        text = stringResource(R.string.setting_page_config_api_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(
                onClick = onClick,
                modifier = Modifier.align(Alignment.End),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}
