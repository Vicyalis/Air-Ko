package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.db.dao.ModelTokenStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageCostTest {

    // 只认识 gpt-4o-mini：输入 0.6 / 输出 2.4（美元每百万）
    private val priceFor: (String) -> ModelPrice? = { model ->
        if (model == "gpt-4o-mini") ModelPrice(input = 0.6, output = 2.4) else null
    }

    private val stats = listOf(
        ModelTokenStats(
            conversationId = "conv-1",
            modelKey = "gpt-4o-mini",
            promptTokens = 1_000_000,
            completionTokens = 0,
            cachedTokens = 0,
            messageCount = 2,
        ),
        ModelTokenStats(
            conversationId = "conv-1",
            modelKey = "unknown-model",
            promptTokens = 500,
            completionTokens = 500,
            cachedTokens = 0,
            messageCount = 1,
        ),
        ModelTokenStats(
            conversationId = "conv-2",
            modelKey = "gpt-4o-mini",
            promptTokens = 0,
            completionTokens = 1_000_000,
            cachedTokens = 0,
            messageCount = 1,
        ),
    )

    @Test
    fun costsAreAggregatedPerModelAcrossConversations() {
        val costs = stats.toModelCosts(priceFor)
        assertEquals(2, costs.size)

        val priced = costs.first()
        assertEquals("gpt-4o-mini", priced.modelKey)
        // 输入 1M * 0.6 + 输出 1M * 2.4 = 3.0，跨会话累加
        assertEquals(3.0, priced.cost!!.toDouble(), 1e-9)
        // 定点金额在聚合后仍然是精确的十进制
        assertEquals("3", priced.cost.toString())
        assertEquals(3, priced.messageCount)
        assertEquals(2_000_000, priced.totalTokens)
        assertTrue(priced.isPriced)

        val unpriced = costs.last()
        assertNull(unpriced.cost)
        assertFalse(unpriced.isPriced)
        assertEquals(1, unpriced.messageCount)
    }

    @Test
    fun cacheHitsAndSavingsAreAggregated() {
        val withCache = listOf(
            ModelTokenStats(
                conversationId = "conv-cache",
                modelKey = "cheap-model",
                promptTokens = 1_000_000,
                completionTokens = 0,
                cachedTokens = 400_000,
                messageCount = 1,
            )
        )
        val price: (String) -> ModelPrice? = {
            ModelPrice(input = 1.0, output = 2.0, cached = 0.25)
        }
        val cost = withCache.toModelCosts(price, overridden = { false }).single()
        // 输入 0.6M * 1.0 + 缓存 0.4M * 0.25 = 0.6 + 0.1
        assertEquals(0.7, cost.cost!!.toDouble(), 1e-9)
        // 省下 0.4M * (1.0 - 0.25) = 0.3
        assertEquals("0.3", cost.saved.toString())
        // 命中率 40%
        assertEquals(0.4, cost.cacheHitRate, 1e-9)
    }

    @Test
    fun costsAreSplitPerConversation() {
        val costs = stats.toConversationCosts(priceFor)

        // conv-1：只有 gpt-4o-mini 那部分算钱，另一个模型记为「未知」
        val conv1 = costs.getValue("conv-1")
        assertEquals(0.6, conv1.costs.getValue("USD").toDouble(), 1e-9)
        assertEquals(1, conv1.unpricedMessages)

        // conv-2：只有输出
        assertEquals(2.4, costs.getValue("conv-2").costs.getValue("USD").toDouble(), 1e-9)
        assertEquals(0, costs.getValue("conv-2").unpricedMessages)
    }

    @Test
    fun conversationWithoutAnyKnownPriceCostsZeroButReportsUnpriced() {
        val onlyUnknown = listOf(
            ModelTokenStats("conv-9", "unknown-model", 1_000, 1_000, 0, 3)
        )
        val cost = onlyUnknown.toConversationCosts(priceFor).getValue("conv-9")
        assertTrue(cost.costs.isEmpty())
        assertEquals(3, cost.unpricedMessages)
        assertNull(formatCosts(cost.costs))
    }

    @Test
    fun differentCurrenciesAreNeverSummedTogether() {
        val mixed = listOf(
            ModelTokenStats("conv-1", "usd-model", 1_000_000, 0, 0, 1),
            ModelTokenStats("conv-1", "cny-model", 1_000_000, 0, 0, 1),
        )
        val price: (String) -> ModelPrice? = { model ->
            when (model) {
                "usd-model" -> ModelPrice(input = 1.0, output = 0.0, currency = "USD")
                "cny-model" -> ModelPrice(input = 2.0, output = 0.0, currency = "CNY")
                else -> null
            }
        }
        val costs = mixed.toConversationCosts(price).getValue("conv-1")
        assertEquals(setOf("USD", "CNY"), costs.costs.keys)
        assertEquals("$1.00 + ¥2.00", formatCosts(costs.costs))
    }

    @Test
    fun formatCostsShowsSingleCurrencyWithSymbol() {
        assertEquals("$1.50", formatCosts(mapOf("USD" to Money.fromDouble(1.5))))
    }
}
