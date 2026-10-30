package me.rerere.rikkahub.data.recommend

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 一个可配置的推荐源。
 *
 * - [builtin]：随 App 预置的候选源，只能启用/禁用、不能删除（删了用户就再也找不回来）。
 * - [local]：内容打包在 App 里的本地源（资源文件），既不能删也不能禁 —— 它是断网时的兜底。
 * - [format]：源的 index 格式。非 [RecommendSourceFormat.NATIVE] 的源会在拉取后先转换成
 *   本协议（见 RecommendSourceAdapters），因此可以直接对接已有的公开目录（Anthropic Skills、
 *   MCP 官方注册表），不需要上游配合。
 */
@Serializable
data class RecommendSource(
    val id: String,
    val name: String = "",
    val url: String,
    val builtin: Boolean = false,
    val local: Boolean = false,
    val enabled: Boolean = true,
    val format: RecommendSourceFormat = RecommendSourceFormat.NATIVE,
    /**
     * 该源只在这些商店类型下加载；空列表表示所有类型。
     *
     * DSH 目录动辄几 MB，打开技能/MCP 商店时不应该顺带下载它；因此预置源按类型隔离，
     * 只有打开 DSH 插件页才会拉取 [RecommendSourceFormat.DSH_CATALOG] 的源。
     */
    val targetTypes: List<RecommendType> = emptyList(),
)

/** 源的 index 格式。 */
@Serializable
enum class RecommendSourceFormat {
    /** 本协议（recommend-index.json） */
    NATIVE,

    /** Claude 插件市场（`.claude-plugin/marketplace.json`）：拆成 skill 条目 */
    CLAUDE_MARKETPLACE,

    /** MCP 官方注册表（registry.modelcontextprotocol.io）：拆成 mcp 条目 */
    MCP_REGISTRY,

    /** DSH 插件市场（dshopen.org / dshmarket 的 format=1 index.json）：拆成 plugin 条目 */
    DSH_CATALOG,
}

/**
 * 源列表的纯规则（增删改、排序、启用禁用）。
 *
 * 抽成不依赖 Android 的对象是为了能直接在 JVM 单测里验证，UI 与存储都走这里。
 */
object RecommendSourceRules {
    /** 本地源固定 id，UI 用它去取本地化名称 */
    const val LOCAL_SOURCE_ID = "builtin"

    const val DSH_OPEN_SOURCE_ID = "dsh-open-market"
    const val DSH_MARKET_SOURCE_ID = "dsh-market"

    /** 本地源的地址是内部标记，不是真实网络地址 */
    const val LOCAL_SOURCE_URL = "asset:builtin_index.json"

    /**
     * 曾经预置过、但上游仓库并不存在的远端源（打开必然 404），从配置里清掉。
     *
     * 早期版本把「约定位置」当成候选源列了出来，用户一启用就是 HTTP 404；现在只保留
     * 随包内置的本地源，想连自己的商店地址就在源管理页自行添加。
     */
    val DEPRECATED_SOURCE_IDS = setOf(
        "anthropics-skills",
        "mcp-servers",
        "awesome-chatgpt-prompts",
    )

    /**
     * 预置源：
     * 1. 随包内置的本地源（断网可用，不会 404）；
     * 2. Anthropic Skills 插件市场（实测可用，拉取后转成 skill 条目）；
     * 3. MCP 官方注册表（实测可用，拉取后转成 mcp 条目）。
     *
     * 早期版本预置过三个上游并不存在的 `recommend-index.json` 地址（必然 404），已删除并在
     * [normalize] 里清理；用户仍可在源管理页添加自己的地址。
     */
    fun defaultSources(): List<RecommendSource> = listOf(
        RecommendSource(
            id = LOCAL_SOURCE_ID,
            name = "",
            url = LOCAL_SOURCE_URL,
            builtin = true,
            local = true,
            enabled = true,
            format = RecommendSourceFormat.NATIVE,
        ),
        RecommendSource(
            id = "anthropic-skills",
            name = "Anthropic Skills",
            url = "https://raw.githubusercontent.com/anthropics/skills/main/.claude-plugin/marketplace.json",
            builtin = true,
            enabled = true,
            format = RecommendSourceFormat.CLAUDE_MARKETPLACE,
            targetTypes = listOf(RecommendType.SKILL),
        ),
        RecommendSource(
            id = "mcp-registry",
            name = "MCP Registry",
            url = "https://registry.modelcontextprotocol.io/v0/servers?limit=100",
            builtin = true,
            enabled = true,
            format = RecommendSourceFormat.MCP_REGISTRY,
            targetTypes = listOf(RecommendType.MCP),
        ),
        RecommendSource(
            id = DSH_OPEN_SOURCE_ID,
            name = "DSH Open Market",
            url = "https://dshopen.org/index.json",
            builtin = true,
            enabled = true,
            format = RecommendSourceFormat.DSH_CATALOG,
            targetTypes = listOf(RecommendType.PLUGIN),
        ),
        RecommendSource(
            id = DSH_MARKET_SOURCE_ID,
            name = "DSH Market",
            url = "https://cdn.jsdelivr.net/npm/dsh-plugin-catalog/index.json",
            builtin = true,
            enabled = true,
            format = RecommendSourceFormat.DSH_CATALOG,
            targetTypes = listOf(RecommendType.PLUGIN),
        ),
    )

    fun newSourceId(): String = "src-" + Uuid.random().toString().take(8)

    /** 与 index 里的地址校验同一套规则：只允许 http/https 且带主机名 */
    fun isValidUrl(url: String): Boolean = isHttpUrl(url)

    /** 新增自定义源：地址非法、重名、地址重复时都返回失败，由 UI 提示。 */
    fun add(
        current: List<RecommendSource>,
        name: String,
        url: String,
    ): Result<List<RecommendSource>> {
        val trimmedUrl = url.trim()
        if (!isValidUrl(trimmedUrl)) {
            return Result.failure(IllegalArgumentException("invalid_url"))
        }
        val trimmedName = name.trim().ifEmpty { trimmedUrl.substringAfter("://").substringBefore('/') }
        if (current.any { it.url.equals(trimmedUrl, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("duplicate_url"))
        }
        val source = RecommendSource(
            id = newSourceId(),
            name = trimmedName,
            url = trimmedUrl,
            builtin = false,
            enabled = true,
        )
        return Result.success(current + source)
    }

    /** 编辑源的名称/地址。内置源的 id 不变，只改用户可见的字段。 */
    fun update(
        current: List<RecommendSource>,
        id: String,
        name: String,
        url: String,
    ): Result<List<RecommendSource>> {
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return Result.failure(IllegalArgumentException("source_not_found"))
        val trimmedUrl = url.trim()
        if (!isValidUrl(trimmedUrl)) {
            return Result.failure(IllegalArgumentException("invalid_url"))
        }
        if (current.any { it.id != id && it.url.equals(trimmedUrl, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("duplicate_url"))
        }
        val updated = current[index].copy(
            name = name.trim().ifEmpty { current[index].name },
            url = trimmedUrl,
        )
        return Result.success(current.toMutableList().apply { set(index, updated) })
    }

    /** 删除自定义源；预置源只忽略、不删除。 */
    fun remove(current: List<RecommendSource>, id: String): List<RecommendSource> =
        current.filterNot { it.id == id && !it.builtin }

    /** 启用/禁用。本地源始终启用，禁用请求被忽略。 */
    fun setEnabled(
        current: List<RecommendSource>,
        id: String,
        enabled: Boolean,
    ): List<RecommendSource> = current.map { source ->
        if (source.id == id && !source.local) source.copy(enabled = enabled) else source
    }

    /** 移动一项到新位置，越界的下标会被夹到合法范围（拖拽排序用）。 */
    fun move(current: List<RecommendSource>, from: Int, to: Int): List<RecommendSource> {
        if (from !in current.indices) return current
        val target = to.coerceIn(0, current.size - 1)
        if (from == target) return current
        val mutable = current.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(target, item)
        return mutable
    }

    /** 上移/下移（没有拖拽能力时的按钮排序）。 */
    fun moveBy(current: List<RecommendSource>, id: String, offset: Int): List<RecommendSource> {
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return current
        return move(current, index, index + offset)
    }

    /** 兼容旧配置文件：补齐 id、去重、清掉已下线的 404 预置源、补齐当前预置源、保底一个本地源。 */
    fun normalize(current: List<RecommendSource>): List<RecommendSource> {
        val defaultsById = defaultSources().associateBy { it.id }
        val cleaned = current
            .filter { it.url.isNotBlank() }
            .filterNot { it.builtin && it.id in DEPRECATED_SOURCE_IDS }
            .distinctBy { it.url.lowercase() }
            .map { source ->
                if (source.local || source.id == LOCAL_SOURCE_ID) {
                    source.copy(
                        local = true,
                        builtin = true,
                        enabled = true,
                        id = LOCAL_SOURCE_ID,
                        format = RecommendSourceFormat.NATIVE,
                    )
                } else {
                    val default = defaultsById[source.id]
                    source.copy(
                        id = source.id.ifBlank { newSourceId() },
                        builtin = source.builtin || default != null,
                        // 老版本的内置源没有 target_types；升级后按类型隔离，避免打开插件页时
                        // 顺带下载技能/MCP 目录，反之亦然。
                        targetTypes = if (
                            source.builtin && default != null && source.targetTypes.isEmpty()
                        ) {
                            default.targetTypes
                        } else {
                            source.targetTypes
                        },
                    )
                }
            }
        return ensureDefaults(cleaned)
    }

    /**
     * 补齐当前版本预置的源：缺失的加进来（本地源放最前，其余预置源按声明顺序紧随其后），
     * 已有的保留用户改过的开关与顺序，不覆盖用户可能已经改过的地址。
     */
    private fun ensureDefaults(current: List<RecommendSource>): List<RecommendSource> {
        val result = current.toMutableList()
        val localIndex = result.indexOfFirst { it.local }
        var insertAt = if (localIndex < 0) 0 else localIndex + 1

        defaultSources().forEach { default ->
            if (default.local) {
                if (localIndex < 0) {
                    result.add(0, default)
                    insertAt = 1
                }
            } else if (result.none { it.id == default.id }) {
                val position = insertAt.coerceIn(0, result.size)
                result.add(position, default)
                insertAt = position + 1
            }
        }
        return result
    }
}
