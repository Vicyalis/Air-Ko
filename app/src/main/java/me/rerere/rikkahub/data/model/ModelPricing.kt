package me.rerere.rikkahub.data.model

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.core.TokenUsage

/**
 * 用户声明的模型价格（覆盖内置价格表）。
 *
 * 各家会调价、还有地区/套餐/中转差价，内置表只能给量级；用户可以按实际账单
 * 声明价格，统计页会优先采用。[match] 的写法与内置表一样支持 `*` 通配。
 */
@Serializable
data class PriceOverride(
    val match: String,
    val input: Double,
    val output: Double,
    /** 命中缓存的输入价格；为空时按 [input] 计费 */
    val cached: Double? = null,
    /** ISO 货币代码（USD/CNY/…），价格以该货币计 */
    val currency: String = "USD",
)

/** 单个模型的价格（每百万 token）；[currency] 为 ISO 代码，缺省美元 */
@Serializable
data class ModelPrice(
    val input: Double,
    val output: Double,
    /** 命中缓存的输入价格；为空时按 [input] 计费 */
    val cached: Double? = null,
    val currency: String = "USD",
) {
    /** 实际生效的缓存价：没有单独定价就等同输入价 */
    val cachedRate: Double get() = cached ?: input

    /** 缓存有优惠才算得出节省（缓存价低于输入价） */
    val hasCacheDiscount: Boolean get() = cachedRate < input
}

/** 价格表里的一行。[match] 支持 `*` 通配 */
@Serializable
internal data class PricingEntry(
    val match: String,
    val input: Double,
    val output: Double,
    val cached: Double? = null,
    val currency: String = "USD",
)

@Serializable
private data class PricingFile(
    val version: Int = 1,
    val models: List<PricingEntry> = emptyList(),
)

/**
 * 一次用量的定价结果（对齐 Reasonix 的 `pricing.Quote` 里用户关心的那部分）：
 * 金额、缓存省下的钱、货币。
 */
data class PriceQuote(
    val cost: Money,
    /** 相对「全部按输入价计费」省下的钱；没有缓存优惠或没有命中时为 0 */
    val saved: Money,
    val currency: String,
)

/**
 * 内置模型价格表（`assets/model_pricing.json`）+ 用户覆盖。
 *
 * 内置表是**参考价**：各家会调价、也存在地区/套餐差异，所以只用来给用户一个量级感，
 * 统计页必须注明是估算。表里只放能确认的型号；查不到价格时返回 null，
 * 统计页会如实标出「未知价格」的消息数，而不是假装免费。
 *
 * 用户覆盖（[PriceOverride]，随设置持久化）永远优先于内置表，用来修正中转站、
 * 官方人民币定价这类差异。
 */
object ModelPricing {
    private const val TAG = "ModelPricing"
    private const val ASSET = "model_pricing.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var entries: List<PricingEntry> = emptyList()

    @Volatile
    private var overrides: List<PricingEntry> = emptyList()

    @Volatile
    private var loaded = false

    /** 幂等加载；失败只记日志，价格功能降级为「未知」，不影响聊天 */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val parsed = runCatching {
                val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
                json.decodeFromString<PricingFile>(text)
            }.getOrElse {
                Log.w(TAG, "模型价格表加载失败: ${it.message}")
                PricingFile()
            }
            entries = parsed.models
            loaded = true
        }
    }

    /** 设置变化时推送进来；再次调用会整体替换 */
    fun setOverrides(list: List<PriceOverride>) {
        overrides = list.map {
            PricingEntry(
                match = it.match,
                input = it.input,
                output = it.output,
                cached = it.cached,
                currency = Currency.normalize(it.currency),
            )
        }
    }

    /** 查模型价格：用户覆盖优先，其次内置表；找不到返回 null（表示「未知」而不是「免费」） */
    fun priceFor(model: String?): ModelPrice? =
        overrides.priceFor(model) ?: entries.priceFor(model)

    /** 该模型是否命中用户覆盖（UI 打「自定义」标用） */
    fun isOverridden(model: String?): Boolean = overrides.priceFor(model) != null

    /** 估算一次请求的花费；没有用量或没有价格时返回 null */
    fun quote(model: String?, usage: TokenUsage?): PriceQuote? {
        if (usage == null) return null
        val price = priceFor(model) ?: return null
        return price.quote(
            promptTokens = usage.promptTokens.toLong(),
            completionTokens = usage.completionTokens.toLong(),
            cachedTokens = usage.cachedTokens.toLong(),
        )
    }
}

/** 在价格表里按模型名找价格：通配命中的模式越长越优先（`gpt-4o-mini*` 比 `gpt-4o*` 精确） */
internal fun List<PricingEntry>.priceFor(model: String?): ModelPrice? {
    val candidates = normalizeCandidates(model) ?: return null
    return filter { entry -> candidates.any { wildcardMatch(entry.match, it) } }
        .maxByOrNull { it.match.length }
        ?.let {
            ModelPrice(
                input = it.input,
                output = it.output,
                cached = it.cached,
                currency = Currency.normalize(it.currency),
            )
        }
}

/**
 * 生成用于匹配的候选名：原名、去掉厂商前缀（`openai/…`、`models/…`、`:free`）、
 * 去掉日期/版本后缀（`-2024-08-06`、`-20241022`、`-001`、`-latest`）。
 */
internal fun normalizeCandidates(model: String?): List<String>? {
    val raw = model?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val bases = LinkedHashSet<String>()
    bases += raw
    raw.substringAfterLast('/').takeIf { it.isNotEmpty() && it != raw }?.let { bases += it }
    raw.substringBefore(':').takeIf { it.isNotEmpty() && it != raw }?.let { bases += it }

    val candidates = LinkedHashSet<String>()
    for (base in bases) {
        var current = base
        candidates += current
        // 最多剥两层：claude-3-5-sonnet-20241022-v2 这种叠了两段
        repeat(2) {
            val stripped = VERSION_SUFFIX.replace(current, "")
            if (stripped != current && stripped.isNotEmpty()) {
                candidates += stripped
                current = stripped
            }
        }
    }
    return candidates.toList()
}

private val VERSION_SUFFIX = Regex(
    "[-_@](\\d{4}[-_]?\\d{2}[-_]?\\d{2}|\\d{8}|\\d{4}|\\d{3}|latest|stable|preview|exp)$"
)

/** 只支持 `*` 通配，够用且不会写出难以预期的正则 */
internal fun wildcardMatch(pattern: String, value: String): Boolean {
    val p = pattern.trim().lowercase()
    if ('*' !in p) return p == value
    val first = p.indexOf('*')
    val last = p.lastIndexOf('*')
    val prefix = p.substring(0, first)
    val suffix = p.substring(last + 1)
    if (!value.startsWith(prefix) || !value.endsWith(suffix)) return false
    if (value.length < prefix.length + suffix.length) return false
    if (first == last) return true
    val middle = p.substring(first + 1, last)
    return middle.split('*').filter { it.isNotEmpty() }.all { value.contains(it) }
}

/**
 * 按价格算一次用量的钱（移植 Reasonix `OriginalCostAmount` 的计价口径）。
 *
 * promptTokens 已经包含 cachedTokens（各家都把命中缓存的输入算进 prompt 里），
 * 所以缓存那部分要按缓存价单独算，不能重复按输入价计费；miss 部分由差额推出。
 * 结果用 [Money] 定点返回，聚合不再经过浮点。
 */
fun ModelPrice.quote(promptTokens: Long, completionTokens: Long, cachedTokens: Long): PriceQuote {
    val prompt = promptTokens.coerceAtLeast(0)
    val cached = cachedTokens.coerceAtLeast(0).coerceAtMost(prompt)
    val miss = prompt - cached
    val completion = completionTokens.coerceAtLeast(0)

    val cost = Money.perMillion(input, miss) +
        Money.perMillion(cachedRate, cached) +
        Money.perMillion(output, completion)
    val saved = if (hasCacheDiscount) Money.perMillion(input - cachedRate, cached) else Money.ZERO

    return PriceQuote(
        cost = cost,
        saved = saved,
        currency = Currency.normalize(currency),
    )
}
