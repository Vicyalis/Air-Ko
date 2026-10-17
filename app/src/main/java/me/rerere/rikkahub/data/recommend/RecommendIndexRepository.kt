package me.rerere.rikkahub.data.recommend

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.AppScope
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 单个源的加载结果，列表页顶部用它显示「来自哪里 / 为什么没加载出来」。 */
data class RecommendSourceResult(
    val sourceId: String,
    val sourceName: String,
    val local: Boolean = false,
    val fromCache: Boolean = false,
    val fetchedAt: Long = 0L,
    val itemCount: Int = 0,
    val error: String? = null,
    val skipped: Int = 0,
)

/** 整个商店的数据状态：合并后的条目 + 每个源的结果。 */
data class RecommendFeedState(
    val loading: Boolean = false,
    val loadedOnce: Boolean = false,
    val entries: List<RecommendEntry> = emptyList(),
    val sourceResults: List<RecommendSourceResult> = emptyList(),
    val cachedSourceIds: Set<String> = emptySet(),
    val skippedCount: Int = 0,
) {
    /** 有没有任何源走了缓存：UI 需要显著标注「缓存数据」 */
    val hasCachedData: Boolean get() = cachedSourceIds.isNotEmpty()

    fun entriesOf(type: RecommendType): List<RecommendEntry> = entries.filter { it.type == type }

    fun findEntry(type: RecommendType, id: String): RecommendEntry? =
        entries.firstOrNull { it.type == type && it.id == id }

    val failedSources: List<RecommendSourceResult> get() = sourceResults.filter { it.error != null }
}

/**
 * 拉取各源的 index 并合并，带本地缓存兜底。
 *
 * 源拉取失败的优先级是「网络失败 → 用上次缓存（UI 标注缓存数据）→ 明确报错」，
 * 绝不静默当作空列表 —— 空列表会让用户以为这个源本来就没内容。
 */
class RecommendIndexRepository(
    context: Context,
    private val client: OkHttpClient,
    private val sourceStore: RecommendSourceStore,
    appScope: AppScope,
) {
    private val appContext = context.applicationContext
    private val cacheFile = File(appContext.filesDir, CACHE_FILE_NAME)
    private val mutex = Mutex()
    private val json = RecommendIndexJson

    private val _state = MutableStateFlow(RecommendFeedState())
    val state = _state.asStateFlow()

    init {
        appScope.launch { ensureLoaded() }
    }

    /** 首次进入商店时自动拉一次；已经有数据就不重复请求。 */
    suspend fun ensureLoaded() {
        if (_state.value.loadedOnce || _state.value.loading) return
        refresh()
    }

    suspend fun refresh() = mutex.withLock {
        val sources = sourceStore.sources.value.filter { it.enabled }
        _state.update { it.copy(loading = true) }

        val cache = readCache()
        val freshCache = mutableMapOf<String, CachedIndex>()
        val entries = mutableListOf<RecommendEntry>()
        val results = mutableListOf<RecommendSourceResult>()
        val cachedSourceIds = mutableSetOf<String>()
        var skippedTotal = 0

        for (source in sources) {
            when (val loaded = loadSource(source, cache)) {
                is SourceLoad.Success -> {
                    entries += loaded.entries
                    skippedTotal += loaded.skipped
                    results += RecommendSourceResult(
                        sourceId = source.id,
                        sourceName = source.name,
                        local = source.local,
                        fromCache = loaded.fromCache,
                        fetchedAt = loaded.fetchedAt,
                        itemCount = loaded.entries.size,
                        skipped = loaded.skipped,
                    )
                    when {
                        loaded.fromCache -> cachedSourceIds += source.id
                        loaded.body != null -> freshCache[source.url] =
                            CachedIndex(fetchedAt = loaded.fetchedAt, body = loaded.body)
                    }
                }

                is SourceLoad.Failure -> results += RecommendSourceResult(
                    sourceId = source.id,
                    sourceName = source.name,
                    local = source.local,
                    error = loaded.message,
                )
            }
        }

        if (freshCache.isNotEmpty()) {
            writeCache(cache + freshCache)
        }

        _state.value = RecommendFeedState(
            loading = false,
            loadedOnce = true,
            // 同一个条目可能出现在多个源里，保留排序靠前的那个，避免列表里出现重复行
            entries = entries.distinctBy { it.identity },
            sourceResults = results,
            cachedSourceIds = cachedSourceIds,
            skippedCount = skippedTotal,
        )
    }

    private sealed interface SourceLoad {
        data class Success(
            val entries: List<RecommendEntry>,
            val skipped: Int,
            val fromCache: Boolean,
            val fetchedAt: Long,
            val body: String?,
        ) : SourceLoad

        data class Failure(val message: String) : SourceLoad
    }

    private suspend fun loadSource(
        source: RecommendSource,
        cache: Map<String, CachedIndex>,
    ): SourceLoad {
        val raw = if (source.local) {
            runCatching { readLocalIndex() }.getOrElse { error ->
                return SourceLoad.Failure("本地 index 读取失败：${error.message ?: "unknown"}")
            }
        } else {
            when (val fetched = fetchIndex(source.url)) {
                is FetchResult.Success -> fetched.body
                is FetchResult.Failure -> {
                    val cached = cache[source.url]
                    if (cached == null) return SourceLoad.Failure(fetched.message)
                    // 网络失败但有上次成功的结果：用缓存，并让 UI 明确标注
                    parseEntries(cached.body, source).let { parsed ->
                        return when (parsed) {
                            is RecommendIndexParseResult.Success -> SourceLoad.Success(
                                entries = parsed.entries,
                                skipped = parsed.skipped.size,
                                fromCache = true,
                                fetchedAt = cached.fetchedAt,
                                body = null,
                            )

                            is RecommendIndexParseResult.Invalid ->
                                SourceLoad.Failure("${fetched.message}；缓存也不可用：${parsed.message}")
                        }
                    }
                }
            }
        }

        return when (val parsed = parseEntries(raw, source)) {
            is RecommendIndexParseResult.Success -> SourceLoad.Success(
                entries = parsed.entries,
                skipped = parsed.skipped.size,
                fromCache = false,
                fetchedAt = System.currentTimeMillis(),
                body = raw,
            )

            is RecommendIndexParseResult.Invalid -> SourceLoad.Failure(parsed.message)
        }
    }

    private fun parseEntries(raw: String, source: RecommendSource): RecommendIndexParseResult {
        val name = source.name.ifBlank { if (source.local) "builtin" else source.id }
        val payload = when (val converted = convertSourceText(source, raw)) {
            is SourceConvertResult.Converted -> converted.json
            SourceConvertResult.NotApplicable -> raw
            is SourceConvertResult.Failed -> return RecommendIndexParseResult.Invalid(converted.message)
        }
        return parseRecommendIndex(payload, originId = source.id, originName = name)
    }

    private fun readLocalIndex(): String =
        appContext.assets.open(LOCAL_INDEX_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private sealed interface FetchResult {
        data class Success(val body: String) : FetchResult
        data class Failure(val message: String) : FetchResult
    }

    private suspend fun fetchIndex(url: String): FetchResult {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain;q=0.9, */*;q=0.1")
            .build()
        return try {
            val body = withContext(Dispatchers.IO) {
                withTimeout(FETCH_TIMEOUT_MS) { client.newCall(request).awaitBody() }
            }
            if (body.length > MAX_INDEX_CHARS) {
                FetchResult.Failure("index 超过 ${MAX_INDEX_CHARS / 1024} KB，已拒绝解析")
            } else {
                FetchResult.Success(body)
            }
        } catch (e: Exception) {
            FetchResult.Failure("拉取失败：${e.message ?: e::class.java.simpleName}")
        }
    }

    private fun readCache(): Map<String, CachedIndex> {
        return runCatching {
            if (!cacheFile.exists()) return@runCatching emptyMap()
            json.decodeFromString(IndexCacheFile.serializer(), cacheFile.readText()).indexes
        }.onFailure {
            Log.w(TAG, "readCache: 缓存读取失败", it)
        }.getOrDefault(emptyMap())
    }

    private suspend fun writeCache(indexes: Map<String, CachedIndex>) = withContext(Dispatchers.IO) {
        runCatching {
            val payload = IndexCacheFile(indexes = indexes)
            val temp = File(cacheFile.parentFile, "$CACHE_FILE_NAME.tmp")
            temp.writeText(json.encodeToString(IndexCacheFile.serializer(), payload))
            if (!temp.renameTo(cacheFile)) {
                cacheFile.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure {
            Log.w(TAG, "writeCache: 缓存写入失败", it)
        }
    }

    @Serializable
    private data class IndexCacheFile(
        val version: Int = 1,
        val indexes: Map<String, CachedIndex> = emptyMap(),
    )

    @Serializable
    data class CachedIndex(
        val fetchedAt: Long = 0L,
        val body: String = "",
    )

    private companion object {
        const val TAG = "RecommendIndexRepo"
        const val CACHE_FILE_NAME = "recommend_index_cache.json"
        const val LOCAL_INDEX_ASSET = "recommend/builtin_index.json"
        const val FETCH_TIMEOUT_MS = 20_000L
        const val MAX_INDEX_CHARS = 2 * 1024 * 1024
    }
}

/**
 * 支持超时取消的 OkHttp 请求：协程被取消时顺手 `call.cancel()`，
 * 否则设置页来回切时会在后台堆积一批没人收的请求。
 */
private suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) {
                            throw IOException("HTTP ${response.code}")
                        }
                        val body = response.body?.string().orEmpty()
                        if (body.isBlank()) throw IOException("响应内容为空")
                        if (continuation.isActive) continuation.resume(body)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        }
    )
}
