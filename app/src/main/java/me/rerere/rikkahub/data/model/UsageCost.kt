package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.db.dao.ModelTokenStats

/** 按模型聚合的花费；[cost] 为 null 表示价格表里没有这个模型（未知，而不是免费） */
data class ModelCost(
    val modelKey: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    val messageCount: Int,
    val cost: Money?,
    /** 缓存命中省下的钱（相对全按输入价计费）；未计价时为 0 */
    val saved: Money = Money.ZERO,
    val currency: String = "USD",
    /** 价格来自用户覆盖而不是内置表（UI 打标用） */
    val overridden: Boolean = false,
) {
    /** 输入 + 输出；缓存部分已包含在输入里，不重复计 */
    val totalTokens: Long get() = promptTokens + completionTokens

    /** 缓存命中率：命中 token 占输入 token 的比例 */
    val cacheHitRate: Double
        get() = if (promptTokens > 0) cachedTokens.toDouble() / promptTokens else 0.0

    val isPriced: Boolean get() = cost != null
}

/**
 * 单个会话的花费；金额按币种分桶（一般只有一个币种），
 * [unpricedMessages] 是没有价格、没算进金额的消息数。
 */
data class ConversationCost(
    val costs: Map<String, Money>,
    val unpricedMessages: Int,
)

/**
 * 花费统计的覆盖率（对齐 Reasonix 的 CostCoverage 语义）：
 * 只有「所有算过 token 的消息都算过价」才算 [COMPLETE]，
 * 有一条没价就永远停在 [PARTIAL]，不能悄悄变成「全部已计价」。
 */
enum class CostCoverage { COMPLETE, PARTIAL, NONE }

private data class TokenSum(
    val prompt: Long,
    val completion: Long,
    val cached: Long,
    val messages: Int,
)

private fun List<ModelTokenStats>.sumTokens(): TokenSum = TokenSum(
    prompt = sumOf { it.promptTokens },
    completion = sumOf { it.completionTokens },
    cached = sumOf { it.cachedTokens },
    messages = sumOf { it.messageCount },
)

/**
 * 把「会话 + 模型」维度的原始统计聚合成按模型的费用，已计价的排前面。
 *
 * 同一会话里可能用过多个模型、价格不同（甚至币种不同），所以任何成本聚合
 * 都必须先按模型分组再算钱；金额用 [Money] 定点相加，最后由调用方按币种分桶。
 * [priceFor] / [overridden] 默认为内置价格表 + 用户覆盖，测试时可注入。
 */
fun List<ModelTokenStats>.toModelCosts(
    priceFor: (String) -> ModelPrice? = ModelPricing::priceFor,
    overridden: (String) -> Boolean = ModelPricing::isOverridden,
): List<ModelCost> =
    groupBy { it.modelKey }
        .map { (modelKey, rows) ->
            val sum = rows.sumTokens()
            val quote = priceFor(modelKey)?.quote(sum.prompt, sum.completion, sum.cached)
            ModelCost(
                modelKey = modelKey,
                promptTokens = sum.prompt,
                completionTokens = sum.completion,
                cachedTokens = sum.cached,
                messageCount = sum.messages,
                cost = quote?.cost,
                saved = quote?.saved ?: Money.ZERO,
                currency = quote?.currency ?: "USD",
                overridden = quote != null && overridden(modelKey),
            )
        }
        .sortedWith(
            compareByDescending<ModelCost> { it.isPriced }
                .thenByDescending { it.cost ?: Money.ZERO }
                .thenByDescending { it.totalTokens }
        )

/** 聚合成每个会话的花费，键是会话 id 字符串（与 UI 里 `conversation.id.toString()` 对齐） */
fun List<ModelTokenStats>.toConversationCosts(
    priceFor: (String) -> ModelPrice? = ModelPricing::priceFor,
): Map<String, ConversationCost> =
    groupBy { it.conversationId }
        .mapValues { (_, rows) ->
            val costs = mutableMapOf<String, Money>()
            var unpricedMessages = 0
            rows.groupBy { it.modelKey }.forEach { (modelKey, modelRows) ->
                val sum = modelRows.sumTokens()
                val quote = priceFor(modelKey)?.quote(sum.prompt, sum.completion, sum.cached)
                if (quote == null) {
                    unpricedMessages += sum.messages
                } else {
                    costs[quote.currency] = (costs[quote.currency] ?: Money.ZERO) + quote.cost
                }
            }
            ConversationCost(costs = costs, unpricedMessages = unpricedMessages)
        }

/** 把分桶金额拼成一行显示（多币种并列、绝不换算相加）；没有金额返回 null */
fun formatCosts(costs: Map<String, Money>): String? {
    if (costs.isEmpty()) return null
    if (costs.size == 1) {
        val (currency, money) = costs.entries.first()
        return formatMoney(money, currency)
    }
    return costs.entries.joinToString(" + ") { (currency, money) -> formatMoney(money, currency) }
}
