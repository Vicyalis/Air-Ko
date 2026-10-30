package me.rerere.rikkahub.data.recommend

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
    /** 被跳过的条目及原因：列表页直接展示，避免「条目不见了」却没有任何解释 */
    val skippedEntries: List<SkippedRecommendEntry> = emptyList(),
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

    // 商店按类型分仓：DSH 目录可能有数千条、几 MB，打开技能/MCP 商店时不该顺带加载。
    private val _states = MutableStateFlow<Map<RecommendType, RecommendFeedState>>(emptyMap())

    /** 某个商店类型的数据流。类型之间互不覆盖，返回上一页时不会重新拉取。 */
    fun stateFor(type: RecommendType): kotlinx.coroutines.flow.Flow<RecommendFeedState> =
        _states.map { it[type] ?: RecommendFeedState() }
            .distinctUntilChanged()

    fun snapshotFor(type: RecommendType): RecommendFeedState =
        _states.value[type] ?: RecommendFeedState()

    fun findEntry(type: RecommendType, id: String): RecommendEntry? =
        snapshotFor(type).findEntry(type, id)

    /** 源配置变化后清空内存状态，下次进入对应商店时重新加载。 */
    fun invalidate() {
        _states.value = emptyMap()
    }

    /** 首次进入某个类型的商店时自动拉一次；已经有数据就不重复请求。 */
    suspend fun ensureLoaded(type: RecommendType) {
        val current = snapshotFor(type)
        if (current.loadedOnce || current.loading) return
        refresh(type)
    }

    suspend fun refresh(type: RecommendType) = mutex.withLock {
        val allSources = sourceStore.sources.value.filter { it.enabled }
        val sources = allSources.filter { source ->
            source.targetTypes.isEmpty() || type in source.targetTypes
        }
        _states.update { states ->
            val current = states[type] ?: RecommendFeedState()
            states + (type to current.copy(loading = true))
        }

        val cache = readCache()
        val freshCache = mutableMapOf<String, CachedIndex>()
        val entries = mutableListOf<RecommendEntry>()
        val results = mutableListOf<RecommendSourceResult>()
        val cachedSourceIds = mutableSetOf<String>()
        val skippedEntries = mutableListOf<SkippedRecommendEntry>()

        for (source in sources) {
            when (val loaded = loadSource(source, cache)) {
                is SourceLoad.Success -> {
                    entries += loaded.entries
                    skippedEntries += loaded.skipped
                    results += RecommendSourceResult(
                        sourceId = source.id,
                        sourceName = source.name,
                        local = source.local,
                        fromCache = loaded.fromCache,
                        fetchedAt = loaded.fetchedAt,
                        itemCount = loaded.entries.size,
                        skipped = loaded.skipped.size,
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

        val newState = RecommendFeedState(
            loading = false,
            loadedOnce = true,
            // 同一个条目可能出现在多个源里，保留排序靠前的那个，避免列表里出现重复行
            entries = entries.filter { it.type == type }.distinctBy { it.identity },
            sourceResults = results,
            cachedSourceIds = cachedSourceIds,
            skippedCount = skippedEntries.size,
            skippedEntries = skippedEntries.distinctBy { "${it.id}\u0000${it.reason}" },
        )
        _states.update { it + (type to newState) }
    }

    private sealed interface SourceLoad {
        data class Success(
            val entries: List<RecommendEntry>,
            val skipped: List<SkippedRecommendEntry>,
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
                                skipped = parsed.skipped,
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
                skipped = parsed.skipped,
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
            is SourceConvertResult.Parsed -> return RecommendIndexParseResult.Success(
                entries = converted.entries,
                skipped = converted.skipped,
            )

            SourceConvertResult.NotApplicable -> raw
            is SourceConvertResult.Failed -> return RecommendIndexParseResult.Invalid(converted.message)
        }
        return parseRecommendIndex(
            text = payload,
            originId = source.id,
            originName = name,
            // 远端 index 里的相对 source_url 按它自己的地址解析；本地 asset 没有 base
            baseUrl = source.url.takeIf { !source.local },
        )
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
        const val FETCH_TIMEOUT_MS = 30_000L
        // DSH 合并目录约 2.6 MB，旧的 2 MB 上限会让整个源被拒绝；缓存/解析都要留余量。
        const val MAX_INDEX_CHARS = 12 * 1024 * 1024
    }
}

/**
 * 支持超时取消的 OkHttp 请求：协程被取消时顺手 `call.cancel()`，
 * 否则设置页来回切时会在后台堆积一批没人收的请求。
 */
private const val MAX_INDEX_BYTES = 12L * 1024 * 1024

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
                            throw IOException("HTTP ${response.code} ${response.message}".trim())
                        }
                        val body = response.body ?: throw IOException("响应没有 body")
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        body.byteStream().use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_INDEX_BYTES) {
                                    throw IOException("index 超过 ${MAX_INDEX_BYTES / 1024 / 1024} MB，已拒绝下载")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                        val text = output.toString(Charsets.UTF_8.name())
                        if (text.isBlank()) throw IOException("响应内容为空（0 字节）")
                        if (continuation.isActive) continuation.resume(text)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        }
    )
}
