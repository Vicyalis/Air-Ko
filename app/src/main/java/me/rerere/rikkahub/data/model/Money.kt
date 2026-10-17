package me.rerere.rikkahub.data.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * 定点金额：以 1e-9 为单位的定长整数（移植自 Reasonix 的 `pricing.Amount`）。
 *
 * 花费若用 Double 逐条累加会漂移（0.1 + 0.2 之后越来越乱），这里把
 * 「乘费率 + 四舍五入」限制在单条用量上，聚合只是整数相加，结果可复现、可对账。
 */
@JvmInline
value class Money(val nanos: Long) : Comparable<Money> {
    val isZero: Boolean get() = nanos == 0L
    val isPositive: Boolean get() = nanos > 0L

    operator fun plus(other: Money): Money = Money(saturatingAdd(nanos, other.nanos))

    operator fun minus(other: Money): Money = Money(saturatingSub(nanos, other.nanos))

    override fun compareTo(other: Money): Int = nanos.compareTo(other.nanos)

    fun toDouble(): Double = nanos / 1e9

    /** 形如 `1.2345` 的十进制文本，最多 9 位小数、去掉尾随 0；和 Reasonix 的定点打印一致 */
    override fun toString(): String =
        if (nanos == 0L) "0" else BigDecimal.valueOf(nanos, 9).stripTrailingZeros().toPlainString()

    companion object {
        val ZERO = Money(0L)

        /** 从 Double 构造；只用于外部（如测试或迁移）传入的浮点金额 */
        fun fromDouble(value: Double): Money =
            Money(
                BigDecimal.valueOf(value)
                    .movePointRight(9)
                    .setScale(0, RoundingMode.HALF_UP)
                    .toLong()
            )

        /**
         * `token 数 × 每百万 token 的费率`，一次乘、一次舍入，全程 BigDecimal。
         *
         * 费率的小数位由 BigDecimal.valueOf(double) 的十进制表示决定，
         * 不会出现 0.1 表示成 0.1000000000000000055… 的二进制误差。
         */
        fun perMillion(rate: Double, tokens: Long): Money {
            if (tokens == 0L || rate == 0.0) return ZERO
            val product = BigDecimal.valueOf(rate)
                .multiply(BigDecimal.valueOf(tokens))
                .multiply(THOUSAND)
                .setScale(0, RoundingMode.HALF_UP)
            return Money(product.toLong())
        }

        private val THOUSAND = BigDecimal.valueOf(1000)

        private fun saturatingAdd(a: Long, b: Long): Long {
            val sum = a + b
            // 同号相加变号 = 溢出；饱和到 Long 边界而不是回绕
            return if (((a xor sum) and (b xor sum)) < 0) {
                if (a >= 0) Long.MAX_VALUE else Long.MIN_VALUE
            } else {
                sum
            }
        }

        private fun saturatingSub(a: Long, b: Long): Long {
            val diff = a - b
            // 异号相减变号 = 溢出
            return if (((a xor b) and (a xor diff)) < 0) {
                if (a >= 0) Long.MAX_VALUE else Long.MIN_VALUE
            } else {
                diff
            }
        }
    }
}

/**
 * ISO 4217 货币代码与符号（移植自 Reasonix pricing 的货币规整与符号表）。
 *
 * 价格表、用户覆盖的价格都带货币代码；统计页按币种分桶展示，
 * 不同币种绝不直接相加。
 */
object Currency {
    /** 别名 → 标准代码；人民币的写法各家不一，统一成 CNY */
    private val ALIASES = mapOf(
        "RMB" to "CNY",
        "CNH" to "CNY",
        "US$" to "USD",
        "USDT" to "USD",
        "EURO" to "EUR",
        "POUND" to "GBP",
        "YEN" to "JPY",
    )

    private val SYMBOLS = mapOf(
        "USD" to "$",
        "CNY" to "¥",
        "EUR" to "€",
        "GBP" to "£",
        "JPY" to "¥",
        "KRW" to "₩",
        "INR" to "₹",
        "HKD" to "HK$",
        "TWD" to "NT$",
        "SGD" to "S$",
        "AUD" to "A$",
        "CAD" to "C$",
        "CHF" to "CHF ",
        "RUB" to "₽",
        "BRL" to "R$",
        "THB" to "฿",
        "VND" to "₫",
    )

    /** 归一到标准代码：去空格、大写、别名映射；空值兜底 USD */
    fun normalize(code: String?): String {
        val raw = code?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (raw.isEmpty()) return "USD"
        return ALIASES[raw] ?: raw
    }

    /** 显示符号；未知代码退化为「代码+空格」，至少不会张冠李戴 */
    fun symbol(code: String?): String {
        val normalized = normalize(code)
        return SYMBOLS[normalized] ?: "$normalized "
    }
}

/**
 * 金额显示：小额多给几位小数，免得一屏都是 0.00 看不出差别。
 * 符号跟着货币走（$ / ¥ / € …）。
 */
fun formatMoney(money: Money, currency: String = "USD"): String {
    val symbol = Currency.symbol(currency)
    val value = money.toDouble()
    return when {
        value <= 0.0 -> "${symbol}0"
        value < 0.0001 -> "<${symbol}0.0001"
        value < 0.01 -> symbol + String.format(Locale.US, "%.4f", value)
        value < 1.0 -> symbol + String.format(Locale.US, "%.3f", value)
        else -> symbol + String.format(Locale.US, "%.2f", value)
    }
}
