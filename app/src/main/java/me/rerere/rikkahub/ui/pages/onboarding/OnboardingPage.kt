package me.rerere.rikkahub.ui.pages.onboarding

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.CheckmarkCircle01
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.RECOMMENDED_PROVIDERS
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.device.DeviceControlState
import me.rerere.rikkahub.device.DeviceSelfTestResult
import me.rerere.rikkahub.device.runActSelfTest
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.PermissionState
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.pages.setting.components.ProviderConfigure
import me.rerere.rikkahub.utils.UiState
import androidx.compose.runtime.DisposableEffect
import me.rerere.rikkahub.device.ShizukuState
import me.rerere.rikkahub.device.ShizukuSupport
import me.rerere.rikkahub.ui.components.device.shizukuStateText
import me.rerere.rikkahub.utils.openUrl
import rikka.shizuku.Shizuku
import me.rerere.rikkahub.utils.openAccessibilitySettings
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/** 引导的步骤，顺序即 [OnboardingStep.entries] 的顺序。 */
private enum class OnboardingStep {
    Welcome,
    Provider,
    Permission,
    DeviceControl,
    Shizuku,
    Done,
}

/**
 * 首次启动的引导流程。
 *
 * 配置模型服务是整个流程的核心：应用不预置任何可用的 API Key，
 * 不配置的话进到主界面也无法对话。其余步骤都可以跳过。
 *
 * @param onFinish 引导结束（完成或跳过）后进入主界面
 */
@Composable
fun OnboardingPage(onFinish: () -> Unit) {
    val vm: SettingVM = koinViewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(OnboardingStep.Welcome) }

    // 完成页也要看权限状态，这里建一份供两步共用
    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            OnboardingHeader(
                step = step,
                onSkip = onFinish,
            )
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    // 往后走时新页面从右侧进来，往回走时反向
                    val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                    (slideInHorizontally { direction * it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -direction * it / 3 } + fadeOut())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { current ->
                when (current) {
                    OnboardingStep.Welcome -> WelcomeStep(
                        onStart = { step = OnboardingStep.Provider },
                    )

                    OnboardingStep.Provider -> ProviderStep(
                        settings = settings,
                        updateSettings = vm::updateSettings,
                        onNext = { step = OnboardingStep.Permission },
                    )

                    OnboardingStep.Permission -> PermissionStep(
                        permissionState = permissionState,
                        onNext = { step = OnboardingStep.DeviceControl },
                    )

                    OnboardingStep.DeviceControl -> DeviceControlStep(
                        onNext = { step = OnboardingStep.Shizuku },
                    )

                    OnboardingStep.Shizuku -> ShizukuStep(
                        onNext = { step = OnboardingStep.Done },
                    )

                    OnboardingStep.Done -> DoneStep(
                        settings = settings,
                        notificationGranted = permissionState.allRequiredPermissionsGranted,
                        onFinish = onFinish,
                    )
                }
            }
        }
    }
}

// 顶部：步骤圆点 + 跳过
@Composable
private fun OnboardingHeader(
    step: OnboardingStep,
    onSkip: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OnboardingStep.entries.forEach { entry ->
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (entry.ordinal <= step.ordinal) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = CircleShape,
                        )
                )
            }
        }
        TextButton(onClick = onSkip) {
            Text(stringResource(R.string.onboarding_skip))
        }
    }
}

/**
 * 居中展示的步骤内容：屏幕放得下时垂直居中；放不下时（小屏或大字体）整页可滚动，
 * 避免图标与文字被上下裁切后看起来「错位」。
 */
@Composable
private fun StepContent(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

@Composable
private fun WelcomeStep(onStart: () -> Unit) {
    StepContent(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = HugeIcons.Sparkles,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_welcome_title),
                style = MaterialTheme.typography.headlineLargeEmphasized,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.onboarding_welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_start))
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = stringResource(R.string.onboarding_welcome_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProviderStep(
    settings: Settings,
    updateSettings: ((Settings) -> Settings) -> Unit,
    onNext: () -> Unit,
) {
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    // 设置还没读进内存时先等一下，否则会闪一下空列表
    if (settings.providers.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val editing = editingId?.let { id -> settings.providers.find { it.id.toString() == id } }

    if (editing == null) {
        ProviderPicker(
            providers = settings.providers,
            onPick = { editingId = it.id.toString() },
            onCreateCustom = {
                val custom = ProviderSetting.OpenAI(
                    id = Uuid.random(),
                    name = "",
                    apiKey = "",
                    enabled = true,
                    builtIn = false,
                )
                updateSettings { it.copy(providers = it.providers + custom) }
                editingId = custom.id.toString()
            },
            onSkip = onNext,
        )
    } else {
        ProviderEditor(
            provider = editing,
            updateSettings = updateSettings,
            onBack = { editingId = null },
            onNext = onNext,
        )
    }
}

// 选择服务商
@Composable
private fun ProviderPicker(
    providers: List<ProviderSetting>,
    onPick: (ProviderSetting) -> Unit,
    onCreateCustom: () -> Unit,
    onSkip: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }

    // 推荐列表的 id 和内置项一致，这里取设置里那一份，避免配出两个同名服务商
    val recommended = remember(providers) {
        RECOMMENDED_PROVIDERS.mapNotNull { candidate ->
            providers.find { it.id == candidate.id }
        }
    }
    val recommendedIds = remember(recommended) { recommended.map { it.id }.toSet() }
    val others = remember(providers, recommendedIds) {
        providers.filter { it.id !in recommendedIds }
    }
    val matched = remember(others, query) {
        if (query.isBlank()) others
        else others.filter { it.name.contains(query, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_provider_title),
                style = MaterialTheme.typography.headlineSmallEmphasized,
            )
            Text(
                text = stringResource(R.string.onboarding_provider_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = HugeIcons.Search01,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    placeholder = { Text(stringResource(R.string.onboarding_provider_search)) },
                )
            }

            if (query.isBlank() && recommended.isNotEmpty()) {
                item {
                    CardGroup(title = { Text(stringResource(R.string.onboarding_provider_recommended)) }) {
                        recommended.forEach { provider ->
                            providerPickerItem(provider, onPick)
                        }
                    }
                }
            }

            if (matched.isNotEmpty()) {
                item {
                    CardGroup(title = { Text(stringResource(R.string.onboarding_provider_all)) }) {
                        matched.forEach { provider ->
                            providerPickerItem(provider, onPick)
                        }
                    }
                }
            }

            if (query.isBlank()) {
                item {
                    CardGroup {
                        item(
                            onClick = onCreateCustom,
                            leadingContent = {
                                CardGroupIcon(icon = HugeIcons.ServerStack01)
                            },
                            headlineContent = {
                                Text(stringResource(R.string.onboarding_provider_custom))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.onboarding_provider_custom_desc))
                            },
                            trailingContent = { ChevronIcon() },
                        )
                    }
                }
            }
        }

        BottomActions {
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.onboarding_provider_skip))
            }
        }
    }
}

@Composable
private fun ChevronIcon() {
    Icon(
        imageVector = HugeIcons.ArrowRight01,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun CardGroupScope.providerPickerItem(
    provider: ProviderSetting,
    onPick: (ProviderSetting) -> Unit,
) {
    val supporting: (@Composable () -> Unit)? = if (provider.models.isEmpty()) {
        null
    } else {
        { Text(stringResource(R.string.onboarding_provider_configured, provider.models.size)) }
    }
    item(
        onClick = { onPick(provider) },
        leadingContent = { CardGroupIcon(icon = HugeIcons.ServerStack01) },
        headlineContent = { Text(provider.name) },
        supportingContent = supporting,
        trailingContent = { ChevronIcon() },
    )
}

// 填 Key、拉模型
@Composable
private fun ProviderEditor(
    provider: ProviderSetting,
    updateSettings: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    val customName = stringResource(R.string.onboarding_provider_custom)

    var fetchState by remember(provider.id) { mutableStateOf<UiState<List<Model>>>(UiState.Idle) }
    var selected by remember(provider.id) { mutableStateOf(emptySet<String>()) }
    var modelQuery by remember(provider.id) { mutableStateOf("") }
    var nameDraft by remember(provider.id) { mutableStateOf(provider.name) }

    val onEdit: (ProviderSetting) -> Unit = { updated ->
        updateSettings { settings ->
            settings.copy(
                providers = settings.providers.map {
                    if (it.id == updated.id) updated else it
                }
            )
        }
    }

    fun fetchModels() {
        fetchState = UiState.Loading
        scope.launch {
            fetchState = try {
                UiState.Success(
                    providerManager.getProviderByType(provider)
                        .listModels(provider)
                        .sortedBy { it.modelId }
                )
            } catch (error: Throwable) {
                UiState.Error(error)
            }
        }
    }

    // 把勾选的模型写回设置；还没有可用聊天模型时顺手指定一个，省得进主界面还要再选
    fun applySelection() {
        val picked = (fetchState as? UiState.Success)?.data
            .orEmpty()
            .filter { it.modelId in selected }
            .map { it.withRegistryInfo() }
        updateSettings { settings ->
            val target = settings.providers.find { it.id == provider.id }
            if (target == null) {
                settings
            } else {
                var updated = target.copyProvider(
                    enabled = true,
                    name = nameDraft.ifBlank { customName },
                )
                picked.forEach { model ->
                    if (updated.models.none { it.modelId == model.modelId }) {
                        updated = updated.addModel(model)
                    }
                }
                settings.copy(
                    providers = settings.providers.map {
                        if (it.id == target.id) updated else it
                    },
                    chatModelId = if (settings.findModelById(settings.chatModelId) == null) {
                        updated.models.firstOrNull { it.type == ModelType.CHAT }?.id
                            ?: settings.chatModelId
                    } else {
                        settings.chatModelId
                    },
                )
            }
        }
    }

    val fetched = (fetchState as? UiState.Success)?.data.orEmpty()
    val visibleModels = remember(fetched, modelQuery) {
        if (modelQuery.isBlank()) fetched
        else fetched.filter { it.modelId.contains(modelQuery, ignoreCase = true) }
    }
    val canContinue = provider.models.isNotEmpty() || selected.isNotEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = provider.name.ifBlank { customName },
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                    )
                    Text(
                        text = stringResource(R.string.onboarding_provider_editor_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 自定义服务商没有预设名字，让用户在这里填
            if (!provider.builtIn) {
                item {
                    CardGroup(title = { Text(stringResource(R.string.onboarding_provider_name)) }) {
                        formItem {
                            OutlinedTextField(
                                value = nameDraft,
                                onValueChange = { nameDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text(customName) },
                            )
                        }
                    }
                }
            }

            item {
                ProviderConfigure(provider = provider, onEdit = onEdit)
            }

            item {
                FetchModelsCard(
                    fetchState = fetchState,
                    configuredCount = provider.models.size,
                    onFetch = ::fetchModels,
                )
            }

            if (fetched.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(
                                R.string.onboarding_provider_models_found,
                                fetched.size
                            ),
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                        Text(
                            text = stringResource(R.string.onboarding_provider_select_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                        OutlinedTextField(
                            value = modelQuery,
                            onValueChange = { modelQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    imageVector = HugeIcons.Search01,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            placeholder = {
                                Text(stringResource(R.string.onboarding_provider_model_search))
                            },
                        )
                    }
                }

                item {
                    SelectAllRow(
                        allSelected = visibleModels.isNotEmpty() &&
                            visibleModels.all { it.modelId in selected },
                        selectedCount = selected.size,
                        onToggleAll = {
                            val ids = visibleModels.map { it.modelId }
                            selected = if (ids.all { it in selected }) {
                                selected - ids.toSet()
                            } else {
                                selected + ids
                            }
                        },
                    )
                }

                items(visibleModels, key = { it.modelId }) { model ->
                    ModelRow(
                        modelId = model.modelId,
                        checked = model.modelId in selected,
                        alreadyAdded = provider.models.any { it.modelId == model.modelId },
                        onToggle = {
                            selected = if (model.modelId in selected) {
                                selected - model.modelId
                            } else {
                                selected + model.modelId
                            }
                        },
                    )
                }
            }
        }

        BottomActions {
            if (!canContinue) {
                Icon(
                    imageVector = HugeIcons.Alert02,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.onboarding_provider_empty_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            OutlinedButton(onClick = onBack) {
                Icon(
                    imageVector = HugeIcons.ArrowLeft01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.onboarding_back))
            }
            Button(
                onClick = {
                    applySelection()
                    onNext()
                },
                enabled = canContinue,
            ) {
                Text(stringResource(R.string.onboarding_next))
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// 拉取模型列表：说明当前状态 + 按钮
@Composable
private fun FetchModelsCard(
    fetchState: UiState<List<Model>>,
    configuredCount: Int,
    onFetch: () -> Unit,
) {
    val status = when (fetchState) {
        is UiState.Loading -> stringResource(R.string.onboarding_provider_fetching)
        is UiState.Error -> stringResource(
            R.string.onboarding_provider_fetch_failed,
            fetchState.error.message ?: fetchState.error::class.java.simpleName,
        )

        is UiState.Success -> if (fetchState.data.isEmpty()) {
            stringResource(R.string.onboarding_provider_models_none)
        } else {
            stringResource(R.string.onboarding_provider_models_found, fetchState.data.size)
        }

        is UiState.Idle -> if (configuredCount > 0) {
            stringResource(R.string.onboarding_provider_configured, configuredCount)
        } else {
            stringResource(R.string.onboarding_provider_fetch_hint)
        }
    }

    CardGroup {
        formItem(
            headlineContent = { Text(stringResource(R.string.onboarding_provider_models)) },
            supportingContent = {
                Text(
                    text = status,
                    color = if (fetchState is UiState.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
        ) {
            Button(
                onClick = onFetch,
                enabled = fetchState !is UiState.Loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (fetchState is UiState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (configuredCount > 0) R.string.onboarding_provider_refetch_models
                        else R.string.onboarding_provider_fetch_models
                    )
                )
            }
        }
    }
}

@Composable
private fun SelectAllRow(
    allSelected: Boolean,
    selectedCount: Int,
    onToggleAll: () -> Unit,
) {
    Surface(
        onClick = onToggleAll,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = allSelected, onCheckedChange = { onToggleAll() })
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.onboarding_provider_select_all),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.onboarding_provider_selected, selectedCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ModelRow(
    modelId: String,
    checked: Boolean,
    alreadyAdded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        shape = MaterialTheme.shapes.large,
        color = if (checked) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = modelId,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (alreadyAdded) {
                Text(
                    text = stringResource(R.string.onboarding_provider_added),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// 通知权限
/**
 * 设备控制（reticle 式的「让 AI 看屏幕并操作手机」）引导步。
 *
 * 无障碍服务只能由用户在系统设置里手动开启，所以这里只做「去开启 / 以后再说」，
 * 从系统设置返回时（ON_RESUME）会重算状态，连上了按钮就变成「下一步」。
 */
@Composable
private fun DeviceControlStep(
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    val status by DeviceControlState.status.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        DeviceControlState.refresh(context)
    }
    val ready = status.connected
    val scope = rememberCoroutineScope()
    var selfTestRunning by remember { mutableStateOf(false) }
    var selfTestResult by remember { mutableStateOf<DeviceSelfTestResult?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        StepContent(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (ready) {
                        HugeIcons.CheckmarkCircle01
                    } else {
                        HugeIcons.CursorPointer01
                    },
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                text = stringResource(R.string.onboarding_device_control_title),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.onboarding_device_control_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        if (ready) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val service = DeviceControlState.service
                        if (service == null || selfTestRunning) return@OutlinedButton
                        selfTestRunning = true
                        scope.launch {
                            val result = withContext(Dispatchers.Default) {
                                service.runActSelfTest(context)
                            }
                            selfTestResult = result
                            selfTestRunning = false
                        }
                    },
                    enabled = !selfTestRunning,
                ) {
                    Text(
                        stringResource(
                            if (selfTestRunning) {
                                R.string.setting_page_device_control_selftest_running
                            } else {
                                R.string.onboarding_device_control_selftest
                            }
                        )
                    )
                }
                selfTestResult?.let { result ->
                    Text(
                        text = when {
                            !result.windowShown -> stringResource(R.string.setting_page_device_control_selftest_blocked)
                            result.ok -> stringResource(R.string.onboarding_device_control_selftest_ok)
                            else -> stringResource(
                                R.string.setting_page_device_control_selftest_failed,
                                result.steps.joinToString(" · "),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = if (result.ok) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }

        BottomActions {
            Button(
                onClick = {
                    if (ready) onNext() else context.openAccessibilitySettings()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(
                        if (ready) R.string.onboarding_next
                        else R.string.device_control_status_open_settings
                    )
                )
            }
            TextButton(onClick = onNext) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }
    }
}

@Composable
private fun ShizukuStep(
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    var info by remember { mutableStateOf(ShizukuSupport.info()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        info = ShizukuSupport.info()
    }
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, _ ->
            info = ShizukuSupport.info()
        }
        ShizukuSupport.addPermissionListener(listener)
        onDispose { ShizukuSupport.removePermissionListener(listener) }
    }
    val ready = info.ready

    Column(modifier = Modifier.fillMaxSize()) {
        StepContent(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (ready) HugeIcons.CheckmarkCircle01 else HugeIcons.ServerStack01,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                text = stringResource(R.string.onboarding_shizuku_title),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.onboarding_shizuku_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = shizukuStateText(context, info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        BottomActions {
            Button(
                onClick = {
                    when (info.state) {
                        ShizukuState.Ready -> onNext()
                        ShizukuState.PermissionNeeded -> ShizukuSupport.requestPermission()
                        else -> openShizukuPage(context)
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(
                        when (info.state) {
                            ShizukuState.Ready -> R.string.onboarding_next
                            ShizukuState.PermissionNeeded -> R.string.onboarding_shizuku_grant
                            else -> R.string.onboarding_shizuku_install
                        }
                    )
                )
            }
            TextButton(onClick = onNext) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }
    }
}

/** 打开 Shizuku 应用；没装就跳到下载页。 */
private fun openShizukuPage(context: android.content.Context) {
    val launched = runCatching {
        context.startActivity(context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api"))
    }.isSuccess
    if (!launched) {
        runCatching { context.openUrl("https://shizuku.rikka.app/download/") }
    }
}

@Composable
private fun PermissionStep(
    permissionState: PermissionState,
    onNext: () -> Unit,
) {
    val granted = permissionState.allRequiredPermissionsGranted
    Column(modifier = Modifier.fillMaxSize()) {
        StepContent(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (granted) {
                        HugeIcons.CheckmarkCircle01
                    } else {
                        HugeIcons.Notification01
                    },
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                text = stringResource(R.string.onboarding_permission_title),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.onboarding_permission_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        BottomActions {
            Button(
                onClick = {
                    if (granted) onNext() else permissionState.requestPermissions()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(
                        if (granted) R.string.onboarding_next
                        else R.string.onboarding_permission_request
                    )
                )
            }
            TextButton(onClick = onNext) {
                Text(stringResource(R.string.onboarding_permission_later))
            }
        }
    }
}

// 完成
@Composable
private fun DoneStep(
    settings: Settings,
    notificationGranted: Boolean,
    onFinish: () -> Unit,
) {
    val configured = settings.providers.filter { it.models.isNotEmpty() }
    Column(modifier = Modifier.fillMaxSize()) {
        StepContent(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.Rocket01,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = stringResource(R.string.onboarding_done_title),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                textAlign = TextAlign.Center,
            )

            CardGroup(modifier = Modifier.fillMaxWidth()) {
                item(
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.ServerStack01,
                            containerColor = if (configured.isEmpty()) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.onboarding_done_provider)) },
                    supportingContent = {
                        Text(
                            if (configured.isEmpty()) {
                                stringResource(R.string.onboarding_done_provider_empty)
                            } else {
                                configured.joinToString("、") { it.name }
                            }
                        )
                    },
                )
                item(
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.Notification01,
                            containerColor = if (notificationGranted) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                    },
                    headlineContent = {
                        Text(stringResource(R.string.onboarding_done_notification))
                    },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (notificationGranted) R.string.onboarding_done_granted
                                else R.string.onboarding_done_not_granted
                            )
                        )
                    },
                )
            }

            Text(
                text = stringResource(R.string.onboarding_done_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        BottomActions {
            Button(
                onClick = onFinish,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.onboarding_done_start))
            }
        }
    }
}

// 各步骤底部统一的按钮容器
@Composable
private fun BottomActions(content: @Composable RowScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** 补上模型库里登记的模态和能力，手动添加模型时也是这么做的。 */
private fun Model.withRegistryInfo(): Model = copy(
    inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId),
    outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId),
    abilities = ModelRegistry.MODEL_ABILITIES.getData(modelId),
)
