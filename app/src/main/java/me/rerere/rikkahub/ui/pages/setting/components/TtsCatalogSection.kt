package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.TtsCatalog
import me.rerere.tts.provider.TtsCatalogException
import me.rerere.tts.provider.TtsVoiceOption
import me.rerere.tts.provider.currentModelValue
import me.rerere.tts.provider.currentVoiceValue
import me.rerere.tts.provider.fetchCatalog
import me.rerere.tts.provider.hasCatalog
import me.rerere.tts.provider.withCatalogSelection

/**
 * 「服务端列表」：填好 API Key 后一键拉取该服务支持的模型 / 音色，点选即写回配置。
 *
 * 拉取失败只在原地提示、不改动任何配置；没有列表接口的服务端禁用按钮并说明原因，
 * 而不是给一份看起来能用的假列表。
 */
@Composable
fun TtsCatalogSection(
    setting: TTSProviderSetting,
    onValueChange: (TTSProviderSetting) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var catalog by remember { mutableStateOf<TtsCatalog?>(null) }

    // 换服务端后上一次的列表就没意义了，清掉，免得把 A 的模型套到 B 上
    LaunchedEffect(setting::class) {
        catalog = null
        error = null
    }

    CardGroup {
        formItem(
            headlineContent = { Text(stringResource(R.string.wp10_catalog_title)) },
            supportingContent = {
                Text(
                    stringResource(
                        if (setting.hasCatalog()) R.string.wp10_catalog_description
                        else R.string.wp10_catalog_unsupported
                    )
                )
            },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            loading = true
                            error = null
                            try {
                                catalog = setting.fetchCatalog()
                            } catch (e: TtsCatalogException) {
                                error = e.message
                            } catch (e: Exception) {
                                error = e.message ?: e.javaClass.simpleName
                            } finally {
                                loading = false
                            }
                        }
                    },
                    enabled = setting.hasCatalog() && !loading,
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.wp10_catalog_loading))
                    } else {
                        Text(stringResource(R.string.wp10_catalog_fetch))
                    }
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    catalog?.let { result ->
        TtsCatalogSheet(
            setting = setting,
            catalog = result,
            onDismiss = { catalog = null },
            onValueChange = onValueChange,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsCatalogSheet(
    setting: TTSProviderSetting,
    catalog: TtsCatalog,
    onDismiss: () -> Unit,
    onValueChange: (TTSProviderSetting) -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val hasModels = !catalog.models.isNullOrEmpty()
    val hasVoices = !catalog.voices.isNullOrEmpty()
    val showTabs = hasModels && hasVoices
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    // 只有一边有内容时不显示无意义的分页，直接展示那一边
    val currentTab = if (showTabs) tab else if (hasModels) 0 else 1

    val rows = remember(catalog, currentTab, query) {
        val source = if (currentTab == 0) {
            catalog.models.orEmpty().map { TtsVoiceOption(it) }
        } else {
            catalog.voices.orEmpty()
        }
        val filtered = if (query.isBlank()) {
            source
        } else {
            source.filter {
                it.label.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true)
            }
        }
        buildSheetRows(filtered)
    }
    val selected = if (currentTab == 0) setting.currentModelValue else setting.currentVoiceValue

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.wp10_catalog_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!catalog.live) {
                Text(
                    text = stringResource(R.string.wp10_catalog_static_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showTabs) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = currentTab == 0,
                        onClick = {
                            tab = 0
                            query = ""
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text(stringResource(R.string.wp10_catalog_tab_model))
                    }
                    SegmentedButton(
                        selected = currentTab == 1,
                        onClick = {
                            tab = 1
                            query = ""
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text(stringResource(R.string.wp10_catalog_tab_voice))
                    }
                }
            }
            if (rows.size > 15) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.wp10_catalog_search)) },
                    singleLine = true,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(
                    items = rows,
                    key = { row ->
                        when (row) {
                            is CatalogRow.Header -> "h:${row.text}"
                            is CatalogRow.Option -> "o:${row.voice.id}"
                        }
                    },
                ) { row ->
                    when (row) {
                        is CatalogRow.Header -> Text(
                            text = row.text,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )

                        is CatalogRow.Option -> ListItem(
                            headlineContent = { Text(row.voice.label) },
                            supportingContent = if (row.voice.label != row.voice.id) {
                                { Text(row.voice.id) }
                            } else null,
                            trailingContent = {
                                RadioButton(selected = row.voice.id == selected, onClick = null)
                            },
                            modifier = Modifier.clickable {
                                onValueChange(
                                    if (currentTab == 0) {
                                        setting.withCatalogSelection(model = row.voice.id)
                                    } else {
                                        setting.withCatalogSelection(voice = row.voice.id)
                                    }
                                )
                            },
                        )
                    }
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.wp10_catalog_close))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private sealed interface CatalogRow {
    data class Header(val text: String) : CatalogRow
    data class Option(val voice: TtsVoiceOption) : CatalogRow
}

/** 按 group 生成分组标题；重复 id 只保留第一次出现的（服务端可能重复返回） */
private fun buildSheetRows(voices: List<TtsVoiceOption>): List<CatalogRow> {
    val rows = mutableListOf<CatalogRow>()
    val seen = mutableSetOf<String>()
    var lastGroup: String? = null
    for (voice in voices) {
        if (!seen.add(voice.id)) continue
        val group = voice.group
        if (group != null && group != lastGroup) {
            rows += CatalogRow.Header(group)
            lastGroup = group
        }
        rows += CatalogRow.Option(voice)
    }
    return rows
}
