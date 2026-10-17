package me.rerere.rikkahub.ui.pages.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Currency
import me.rerere.rikkahub.data.model.PriceOverride
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import java.math.BigDecimal

/**
 * 模型价格：用户按实际账单声明价格（覆盖内置参考表）。
 *
 * 统计页只提供量级感，各家调价/中转差价/地区定价都会让内置表失真；
 * 这里声明的价格优先参与计价，统计里打了「自定义」标的模型就是走了这里的价格。
 */
@Composable
fun ModelPricePage() {
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val overrides = settings.modelPriceOverrides
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // 正在编辑的覆盖：index 为 null 表示新增；draft 是对话框里的初始值
    var editing by remember { mutableStateOf<Pair<Int?, PriceOverride>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_price_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.fillMaxWidth(),
                    title = { Text(stringResource(R.string.model_price_page_section_overrides)) },
                ) {
                    if (overrides.isEmpty()) {
                        item(
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.model_price_page_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                    overrides.forEachIndexed { index, override ->
                        item(
                            onClick = { editing = index to override },
                            headlineContent = { Text(override.match) },
                            supportingContent = { Text(priceSummary(override)) },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            settingsStore.update { current ->
                                                current.copy(
                                                    modelPriceOverrides = current.modelPriceOverrides
                                                        .filterIndexed { i, _ -> i != index },
                                                )
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Delete01,
                                        contentDescription = stringResource(R.string.model_price_page_delete),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    }
                    item(
                        onClick = {
                            editing = null to PriceOverride(match = "", input = 0.0, output = 0.0)
                        },
                        leadingContent = { Icon(HugeIcons.Add01, contentDescription = null) },
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.model_price_page_add),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.model_price_page_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    editing?.let { (index, draft) ->
        PriceOverrideDialog(
            initial = draft,
            isNew = index == null,
            onDismiss = { editing = null },
            onConfirm = { value ->
                scope.launch {
                    settingsStore.update { current ->
                        val list = current.modelPriceOverrides.toMutableList()
                        if (index == null) {
                            list.add(value)
                        } else {
                            list[index] = value
                        }
                        current.copy(modelPriceOverrides = list)
                    }
                }
                editing = null
            },
        )
    }
}

/** 一行摘要：`$0.15 / $0.6 / $0.075 · USD`（缓存价没有就不显示） */
private fun priceSummary(override: PriceOverride): String {
    val symbol = Currency.symbol(override.currency)
    val parts = buildList {
        add("$symbol${trimNumber(override.input)}")
        add("$symbol${trimNumber(override.output)}")
        override.cached?.let { add("$symbol${trimNumber(it)}") }
    }
    return parts.joinToString(" / ") + " · " + Currency.normalize(override.currency)
}

private fun trimNumber(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

@Composable
private fun PriceOverrideDialog(
    initial: PriceOverride,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (PriceOverride) -> Unit,
) {
    var match by remember { mutableStateOf(initial.match) }
    var input by remember { mutableStateOf(if (isNew) "" else trimNumber(initial.input)) }
    var output by remember { mutableStateOf(if (isNew) "" else trimNumber(initial.output)) }
    var cached by remember { mutableStateOf(initial.cached?.let { trimNumber(it) } ?: "") }
    var currency by remember { mutableStateOf(Currency.normalize(initial.currency)) }
    var attempted by remember { mutableStateOf(false) }

    val inputValue = input.trim().toDoubleOrNull()
    val outputValue = output.trim().toDoubleOrNull()
    val cachedValue = cached.trim().let { if (it.isEmpty()) null else it.toDoubleOrNull() }
    val matchError = attempted && match.isBlank()
    val inputError = attempted && (inputValue == null || inputValue < 0)
    val outputError = attempted && (outputValue == null || outputValue < 0)
    val cachedError = attempted && cached.isNotBlank() && (cachedValue == null || cachedValue < 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isNew) R.string.model_price_page_add_title else R.string.model_price_page_edit_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = match,
                    onValueChange = { match = it },
                    label = { Text(stringResource(R.string.model_price_page_match)) },
                    singleLine = true,
                    isError = matchError,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text(stringResource(R.string.model_price_page_input)) },
                        singleLine = true,
                        isError = inputError,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = output,
                        onValueChange = { output = it },
                        label = { Text(stringResource(R.string.model_price_page_output)) },
                        singleLine = true,
                        isError = outputError,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = cached,
                    onValueChange = { cached = it },
                    label = { Text(stringResource(R.string.model_price_page_cached)) },
                    singleLine = true,
                    isError = cachedError,
                )
                OutlinedTextField(
                    value = currency,
                    onValueChange = { currency = it },
                    label = { Text(stringResource(R.string.model_price_page_currency)) },
                    placeholder = { Text(stringResource(R.string.model_price_page_currency_hint)) },
                    singleLine = true,
                )
                val errorText = when {
                    matchError -> stringResource(R.string.model_price_page_match_required)
                    inputError || outputError || cachedError -> stringResource(R.string.model_price_page_invalid_number)
                    else -> null
                }
                Text(
                    text = errorText ?: stringResource(R.string.model_price_page_per_million),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (errorText != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    attempted = true
                    val inputOk = inputValue != null && inputValue >= 0
                    val outputOk = outputValue != null && outputValue >= 0
                    val cachedOk = cached.isBlank() || (cachedValue != null && cachedValue >= 0)
                    if (match.isNotBlank() && inputOk && outputOk && cachedOk) {
                        onConfirm(
                            PriceOverride(
                                match = match.trim(),
                                input = inputValue,
                                output = outputValue,
                                cached = cachedValue,
                                currency = currency.trim().ifEmpty { "USD" },
                            )
                        )
                    }
                },
            ) {
                Text(stringResource(R.string.model_price_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.model_price_page_cancel))
            }
        },
    )
}
