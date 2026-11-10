package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.HarnessSetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * Harness 调参页。
 *
 * 总开关默认关闭：关闭时完全使用内置常量；开启后使用下面的自定义值。
 */
@Composable
fun HarnessSettingPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val harness = settings.harness

    fun update(block: (HarnessSetting) -> HarnessSetting) {
        vm.updateSettings { it.copy(harness = block(it.harness)) }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.harness_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup {
                    switchItem(
                        checked = harness.customEnabled,
                        onCheckedChange = { checked ->
                            update { it.copy(customEnabled = checked) }
                        },
                        headlineContent = { Text(stringResource(R.string.harness_custom_title)) },
                        supportingContent = { Text(stringResource(R.string.harness_custom_desc)) },
                    )
                }
            }

            if (harness.customEnabled) {
                item {
                    CardGroup {
                        formItem(
                            headlineContent = { Text(stringResource(R.string.harness_max_steps)) },
                            supportingContent = { Text(stringResource(R.string.harness_max_steps_desc)) },
                            trailingContent = { CardGroupValue("${harness.maxSteps}") },
                        ) {
                            Slider(
                                value = harness.maxSteps.toFloat(),
                                onValueChange = { value ->
                                    update { it.copy(maxSteps = value.toInt()) }
                                },
                                valueRange = 16f..512f,
                                steps = 30,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        formItem(
                            headlineContent = { Text(stringResource(R.string.harness_tool_output)) },
                            supportingContent = { Text(stringResource(R.string.harness_tool_output_desc)) },
                            trailingContent = { CardGroupValue("${harness.toolOutputKb} KB") },
                        ) {
                            Slider(
                                value = harness.toolOutputKb.toFloat(),
                                onValueChange = { value ->
                                    update { it.copy(toolOutputKb = value.toInt()) }
                                },
                                valueRange = 4f..256f,
                                steps = 62,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        formItem(
                            headlineContent = { Text(stringResource(R.string.harness_retries)) },
                            supportingContent = { Text(stringResource(R.string.harness_retries_desc)) },
                            trailingContent = { CardGroupValue("${harness.networkRetries}") },
                        ) {
                            Slider(
                                value = harness.networkRetries.toFloat(),
                                onValueChange = { value ->
                                    update { it.copy(networkRetries = value.toInt()) }
                                },
                                valueRange = 0f..5f,
                                steps = 4,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
