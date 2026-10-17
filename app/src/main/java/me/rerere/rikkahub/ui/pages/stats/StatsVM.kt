package me.rerere.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.getMessageCountPerDay
import me.rerere.rikkahub.data.db.dao.getModelTokenStats
import me.rerere.rikkahub.data.db.dao.getTokenStats
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.CostCoverage
import me.rerere.rikkahub.data.model.ModelCost
import me.rerere.rikkahub.data.model.Money
import me.rerere.rikkahub.data.model.toModelCosts
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val totalPromptTokens: Long = 0L,
    val totalCompletionTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
    /** 估算总花费，按币种分桶（一般只有一个币种）；只统计有价格的模型 */
    val totalCosts: Map<String, Money> = emptyMap(),
    /** 缓存命中省下的钱（同样按币种） */
    val cacheSaved: Map<String, Money> = emptyMap(),
    /** 花得最多的几个模型，已计价 */
    val modelCosts: List<ModelCost> = emptyList(),
    /** 价格表里没有价格的模型名（最多列几个） */
    val unpricedModels: List<String> = emptyList(),
    /** 价格表里没有价格的模型数 */
    val unpricedModelCount: Int = 0,
    /** 没有计价的消息数（用来如实说明「统计不完整」） */
    val unpricedMessages: Int = 0,
    /** 带用量、可计价的消息总数（已计价 + 未计价） */
    val billableMessages: Int = 0,
) {
    /** 有价格的消息数 = 可计价消息 - 未计价消息 */
    val pricedMessages: Int get() = (billableMessages - unpricedMessages).coerceAtLeast(0)

    /** 覆盖率：全部计价才算 COMPLETE（对齐 Reasonix 的覆盖率语义） */
    val coverage: CostCoverage get() = when {
        unpricedMessages == 0 && billableMessages > 0 -> CostCoverage.COMPLETE
        billableMessages > unpricedMessages -> CostCoverage.PARTIAL
        else -> CostCoverage.NONE
    }

    /** 整体缓存命中率：命中 token / 输入 token */
    val cacheHitRate: Double
        get() = if (totalPromptTokens > 0) totalCachedTokens.toDouble() / totalPromptTokens else 0.0
}

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    init {
        viewModelScope.launch { loadStats() }
    }

    /** 价格可能被改过（模型价格页）或数据库写入了新用量，回前台时重算一次 */
    fun refresh() {
        viewModelScope.launch { loadStats() }
    }

    private suspend fun loadStats() {
        delay(50)

        val today = LocalDate.now()

        // 热力图起始日期（52 周前的周日），格式 "yyyy-MM-dd" 直接与 JSON 中的 LocalDateTime 前缀比较
        val startDate = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
            .toString()

        // 基于用户消息的 createdAt 统计每日活跃消息数，SQLite 侧 GROUP BY，返回 ≤371 行
        val conversationsPerDay = withContext(Dispatchers.IO) {
            messageNodeDAO
                .getMessageCountPerDay(startDate)
                .mapNotNull { entry ->
                    runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
                }
                .toMap()
        }

        val totalConversations = conversationDAO.countAll()

        // json_each() + json_extract() 在 SQLite 侧聚合，不再加载完整 JSON 到 Kotlin
        val tokenStats = messageNodeDAO.getTokenStats()

        // 费用必须按模型算，价格表里没有的模型单独计数，别混进总价里
        val modelCosts = withContext(Dispatchers.IO) {
            runCatching { messageNodeDAO.getModelTokenStats().toModelCosts() }
                .getOrElse { emptyList() }
        }
        val pricedCosts = modelCosts.filter { it.isPriced }
        val unpricedCosts = modelCosts.filterNot { it.isPriced }

        // 金额定点累加，再按币种分桶：不同币种绝不换算相加
        val totalCosts = mutableMapOf<String, Money>()
        val cacheSaved = mutableMapOf<String, Money>()
        pricedCosts.forEach { cost ->
            val amount = cost.cost ?: return@forEach
            totalCosts[cost.currency] = (totalCosts[cost.currency] ?: Money.ZERO) + amount
            if (cost.saved.isPositive) {
                cacheSaved[cost.currency] = (cacheSaved[cost.currency] ?: Money.ZERO) + cost.saved
            }
        }

        val launchCount = settingsStore.launchCountFlow.first()

        _stats.value = AppStats(
            isLoading = false,
            totalConversations = totalConversations,
            totalMessages = tokenStats.totalMessages,
            totalPromptTokens = tokenStats.promptTokens,
            totalCompletionTokens = tokenStats.completionTokens,
            totalCachedTokens = tokenStats.cachedTokens,
            conversationsPerDay = conversationsPerDay,
            launchCount = launchCount,
            totalCosts = totalCosts,
            cacheSaved = cacheSaved,
            // 统计页最多列前 5 个模型，再多就成了模型清单
            modelCosts = pricedCosts.take(5),
            unpricedModels = unpricedCosts.map { it.modelKey }.take(4),
            unpricedModelCount = unpricedCosts.size,
            unpricedMessages = unpricedCosts.sumOf { it.messageCount },
            billableMessages = modelCosts.sumOf { it.messageCount },
        )
    }
}
