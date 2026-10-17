package me.rerere.rikkahub.data.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPricingTest {

    private val entries = listOf(
        PricingEntry("gpt-4o-mini*", 0.15, 0.6, 0.075),
        PricingEntry("gpt-4o*", 2.5, 10.0, 1.25),
        PricingEntry("claude-3-5-sonnet*", 3.0, 15.0, 0.3),
        PricingEntry("gemini-2.5-flash*", 0.3, 2.5, 0.075),
    )

    @After
    fun clearOverrides() {
        // ModelPricing 是单例，测试里塞的覆盖要清掉，避免影响其他用例
        ModelPricing.setOverrides(emptyList())
    }

    @Test
    fun moreSpecificPatternWins() {
        assertEquals(0.15, entries.priceFor("gpt-4o-mini")!!.input, 1e-9)
        assertEquals(2.5, entries.priceFor("gpt-4o")!!.input, 1e-9)
    }

    @Test
    fun vendorPrefixAndDateSuffixAreNormalized() {
        // OpenRouter 风格前缀
        assertEquals(0.15, entries.priceFor("openai/gpt-4o-mini")!!.input, 1e-9)
        // Anthropic 的日期后缀
        assertEquals(3.0, entries.priceFor("claude-3-5-sonnet-20241022")!!.input, 1e-9)
        // OpenAI 的日期后缀
        assertEquals(2.5, entries.priceFor("gpt-4o-2024-08-06")!!.input, 1e-9)
        // Gemini 的 models/ 前缀 + 版本号
        assertEquals(0.3, entries.priceFor("models/gemini-2.5-flash-002")!!.input, 1e-9)
        // OpenRouter 的 :free 变体也应当能落到基础模型上
        assertEquals(0.15, entries.priceFor("openai/gpt-4o-mini:free")!!.input, 1e-9)
    }

    @Test
    fun unknownModelHasNoPrice() {
        assertNull(entries.priceFor("totally-unknown-model"))
        assertNull(entries.priceFor(""))
        assertNull(entries.priceFor(null))
        assertNull(emptyList<PricingEntry>().priceFor("gpt-4o"))
    }

    @Test
    fun quoteSeparatesCachedInput() {
        val price = ModelPrice(input = 1.0, output = 2.0, cached = 0.1)
        // 1M 输入（其中 0.5M 命中缓存）+ 0.5M 输出 = 0.5*1 + 0.5*0.1 + 0.5*2
        val quote = price.quote(
            promptTokens = 1_000_000,
            completionTokens = 500_000,
            cachedTokens = 500_000,
        )
        assertEquals(1.55, quote.cost.toDouble(), 1e-9)
        // 定点金额打印出来也要是干净的十进制
        assertEquals("1.55", quote.cost.toString())
        // 省下：0.5M * (1.0 - 0.1) = 0.45
        assertEquals("0.45", quote.saved.toString())
    }

    @Test
    fun cachedWithoutCachedPriceUsesInputPrice() {
        val price = ModelPrice(input = 1.0, output = 2.0)
        val quote = price.quote(
            promptTokens = 1_000_000,
            completionTokens = 0,
            cachedTokens = 1_000_000,
        )
        assertEquals(1.0, quote.cost.toDouble(), 1e-9)
        assertEquals(Money.ZERO, quote.saved)
        assertFalse(price.hasCacheDiscount)
    }

    @Test
    fun cachedTokensLargerThanPromptDoNotGoNegative() {
        val price = ModelPrice(input = 1.0, output = 2.0, cached = 0.5)
        val quote = price.quote(promptTokens = 100, completionTokens = 0, cachedTokens = 999)
        assertEquals(100 * 0.5 / 1_000_000, quote.cost.toDouble(), 1e-12)
    }

    @Test
    fun fixedPointAdditionDoesNotDrift() {
        // Double 相加 0.1 + 0.2 != 0.3，定点金额必须是精确的
        val sum = Money.fromDouble(0.1) + Money.fromDouble(0.2)
        assertEquals("0.3", sum.toString())
        assertEquals(0.3, sum.toDouble(), 1e-12)
    }

    @Test
    fun perMillionRoundsOnceAtTheEnd() {
        // 0.15 / 1M tokens * 1 token = 0.00000015
        assertEquals("0.00000015", Money.perMillion(0.15, 1).toString())
        // 非整除 token 数只做一次四舍五入
        assertEquals("0.0000006", Money.perMillion(0.15, 4).toString())
    }

    @Test
    fun costFormattingKeepsSmallAmountsReadable() {
        assertEquals("$0", formatMoney(Money.ZERO))
        assertEquals("<$0.0001", formatMoney(Money.fromDouble(0.00001)))
        assertTrue(formatMoney(Money.fromDouble(0.0032)).startsWith("$0.003"))
        assertEquals("$1.50", formatMoney(Money.fromDouble(1.5)))
        // 货币符号跟着价格走
        assertEquals("¥1.50", formatMoney(Money.fromDouble(1.5), "CNY"))
    }

    @Test
    fun currencyCodesAreNormalized() {
        assertEquals("CNY", Currency.normalize("rmb"))
        assertEquals("CNY", Currency.normalize("CNH"))
        assertEquals("USD", Currency.normalize(""))
        assertEquals("¥", Currency.symbol("cny"))
        assertEquals("$", Currency.symbol("USD"))
        assertEquals("€", Currency.symbol("eur"))
    }

    @Test
    fun userOverridesWinOverBuiltInTable() {
        ModelPricing.setOverrides(
            listOf(
                PriceOverride(match = "deepseek-*", input = 1.0, output = 2.0, cached = 0.1, currency = "cny"),
            )
        )
        val price = ModelPricing.priceFor("deepseek-chat")
        assertEquals(1.0, price!!.input, 1e-9)
        // 货币归一到标准代码
        assertEquals("CNY", price.currency)
        assertTrue(ModelPricing.isOverridden("deepseek-chat"))

        // 没有覆盖的模型仍走内置表
        assertFalse(ModelPricing.isOverridden("gpt-4o"))
    }

    @Test
    fun wildcardMatchingIsAnchoredOnBothEnds() {
        assertTrue(wildcardMatch("gpt-4o*", "gpt-4o-mini"))
        assertTrue(wildcardMatch("*mini", "gpt-4o-mini"))
        assertTrue(wildcardMatch("gpt-4o-mini", "gpt-4o-mini"))
        // 前后都要对上，不能只做 contains
        assertTrue(!wildcardMatch("gpt-4o*", "my-gpt-4o-mini"))
        assertTrue(!wildcardMatch("*4o", "gpt-4o-mini"))
    }
}
