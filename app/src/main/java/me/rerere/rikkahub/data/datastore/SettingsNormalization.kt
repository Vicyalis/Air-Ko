package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting

/** 读盘后和每次修改后都要过一遍，保证内存里的设置始终是规整的。 */
internal fun Settings.normalized(): Settings =
    withBuiltInDefaults().withoutInvalidReferences().withValuesInRange()

/** 读取各类型供应商的 API Key（ProviderSetting 基类没有这个字段）。 */
private fun ProviderSetting.apiKeyOrBlank(): String = when (this) {
    is ProviderSetting.OpenAI -> apiKey
    is ProviderSetting.Google -> apiKey
    is ProviderSetting.Claude -> apiKey
}

/**
 * 早期版本预置过、Air-Ko 已移除的供应商 id（按 id 清理，避免误删用户自建的供应商）。
 * 只在该供应商「没有 API Key 也没有模型」时删除。
 */
private val REMOVED_PRESET_PROVIDER_IDS = setOf(
    "1b1395ed-b702-4aeb-8bc1-b681c4456953", // AiHubMix
    "2a05506f-3a59-450a-a493-33a82bc85a81", // APIMart
    "386e0f29-8228-4512-affe-8fd8add82d88", // Vercel AI Gateway
    "f76cae46-069a-4334-ab8e-224e4979e58c", // 阿里云百炼
    "3dfd6f9b-f9d9-417f-80c1-ff8d77184191", // 火山引擎
    "3bc40dc1-b11a-46fa-863b-6306971223be", // 智谱AI开放平台
    "f4f8870e-82d3-495b-9b64-d58e508b3b2c", // 阶跃星辰
    "da93779f-3956-48cc-82ef-67bb482eaaf7", // 302.AI
    "ef5d149b-8e34-404b-818c-6ec242e5c3c5", // 腾讯Hunyuan
    "ff3cde7e-0f65-43d7-8fb2-6475c99f5990", // xAI
    "aecf04fd-cb5c-4582-aed2-e8bf393923fd", // 随想AI网关
    "afbc54ad-807e-4455-9594-7d7a546356ad", // MaruCode
)

// 补齐缺失的内置提供商/助手/TTS，内置提供商的描述信息始终以代码里的为准
internal fun Settings.withBuiltInDefaults(): Settings {
    var providers = this.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
    DEFAULT_PROVIDERS.forEach { defaultProvider ->
        if (providers.none { it.id == defaultProvider.id }) {
            providers.add(defaultProvider.copyProvider())
        }
    }
    // Air-Ko 只保留 8 家预置供应商：清掉旧版本存下来的、用户从未配置过的其它内置预设。
    // 注意 builtIn 是 @Transient（反序列化后恒为 false），只能按「已移除的预置 id」精确匹配；
    // 填过 API Key 或自己加过模型的保留，避免误删用户数据。
    providers = providers.filterNot { provider ->
        provider.id.toString() in REMOVED_PRESET_PROVIDER_IDS &&
            provider.apiKeyOrBlank().isBlank() &&
            provider.models.isEmpty()
    }.toMutableList()
    providers = providers.map { provider ->
        val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
        if (defaultProvider != null) {
            provider.copyProvider(
                builtIn = defaultProvider.builtIn,
                description = defaultProvider.description,
                shortDescription = defaultProvider.shortDescription,
            )
        } else provider
    }.toMutableList()
    val assistants = this.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
    DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
        if (assistants.none { it.id == defaultAssistant.id }) {
            assistants.add(defaultAssistant.copy())
        }
    }
    val ttsProviders = this.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
    DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
        if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
            ttsProviders.add(defaultTTSProvider.copyProvider())
        }
    }
    return copy(
        providers = providers,
        assistants = assistants,
        ttsProviders = ttsProviders,
    )
}

// 去重并清理无效引用
internal fun Settings.withoutInvalidReferences(): Settings {
    val settings = this
    val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
    val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
    val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
    val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
    val validModelIds by lazy { settings.providers.flatMap { it.models }.mapTo(HashSet()) { it.id } }
    val asrProviders = settings.asrProviders.distinctBy { it.id }
    return settings.copy(
        providers = settings.providers.distinctBy { it.id }.map { provider ->
            when (provider) {
                is ProviderSetting.OpenAI -> provider.copy(
                    models = provider.models.distinctBy { model -> model.id }
                )

                is ProviderSetting.Google -> provider.copy(
                    models = provider.models.distinctBy { model -> model.id }
                )

                is ProviderSetting.Claude -> provider.copy(
                    models = provider.models.distinctBy { model -> model.id }
                )
            }
        },
        assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
            assistant.copy(
                // 过滤掉不存在的 MCP 服务器 ID
                mcpServers = assistant.mcpServers.filter { serverId ->
                    serverId in validMcpServerIds
                }.toSet(),
                // 过滤掉不存在的模式注入 ID
                modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                    id in validModeInjectionIds
                }.toSet(),
                // 过滤掉不存在的 Lorebook ID
                lorebookIds = assistant.lorebookIds.filter { id ->
                    id in validLorebookIds
                }.toSet(),
                // 过滤掉不存在的快捷消息 ID
                quickMessageIds = assistant.quickMessageIds.filter { id ->
                    id in validQuickMessageIds
                }.toSet()
            )
        },
        ttsProviders = settings.ttsProviders.distinctBy { it.id },
        asrProviders = asrProviders,
        selectedASRProviderId = settings.selectedASRProviderId
            ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
            ?: asrProviders.firstOrNull()?.id,
        favoriteModels = settings.favoriteModels.filter { it in validModelIds },
        modeInjections = settings.modeInjections.distinctBy { it.id },
        lorebooks = settings.lorebooks.distinctBy { it.id },
        quickMessages = settings.quickMessages.distinctBy { it.id },
    )
}

// 把取值收回合法范围，比如删掉搜索服务后选中的下标可能越界
private fun Settings.withValuesInRange(): Settings = copy(
    searchServiceSelected = searchServiceSelected.coerceIn(0, (searchServices.size - 1).coerceAtLeast(0)),
    defaultTTSPlaybackSpeed = defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f),
    // 热池最多 4 个会话，空闲回收 1 分钟起
    terminalPrewarmSessions = terminalPrewarmSessions.coerceIn(0, 4),
    terminalPrewarmIdleMinutes = terminalPrewarmIdleMinutes.coerceIn(1, 60),
    // 侧边栏背景图太透或全不透明都不好看，压在可读区间里
    displaySetting = displaySetting.copy(
        sidebarBackgroundImageOpacity = displaySetting.sidebarBackgroundImageOpacity.coerceIn(0.05f, 1f),
    ),
)
