package me.rerere.rikkahub.data.recommend

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.AppScope

/**
 * 推荐源的配置存储。
 *
 * 刻意**不**复用 `Settings.kt` / DataStore：源列表、排序偏好、安装历史都是推荐商店自己的东西，
 * 独立一个 JSON 文件（`filesDir/recommend_sources.json`）既避免和设置体系耦合，也方便单独备份/清理。
 */
class RecommendSourceStore(
    context: Context,
    private val appScope: AppScope,
) {
    private val file = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()

    private val _sources = MutableStateFlow(RecommendSourceRules.defaultSources())
    val sources = _sources.asStateFlow()

    private val _sort = MutableStateFlow(RecommendSort.DEFAULT)
    val sort = _sort.asStateFlow()

    private val _confirmed = MutableStateFlow<Set<String>>(emptySet())

    /** 已经确认过安全提示的条目（首次安装必须弹窗确认，确认过一次就不用反复打扰） */
    val confirmed = _confirmed.asStateFlow()

    private val _installed = MutableStateFlow<Set<String>>(emptySet())

    /** 已安装过的条目标识，列表页用来显示「已安装」 */
    val installed = _installed.asStateFlow()

    // 用户在磁盘内容读完之前就改了配置的话，落盘的旧数据不能再覆盖内存里的新值
    private var dirty = false

    init {
        appScope.launch {
            mutex.withLock {
                if (!dirty) readFromDisk()
            }
        }
    }

    val enabledSources: List<RecommendSource> get() = _sources.value.filter { it.enabled }

    fun findSource(id: String): RecommendSource? = _sources.value.firstOrNull { it.id == id }

    fun isConfirmed(identity: String): Boolean = identity in _confirmed.value

    suspend fun addSource(name: String, url: String): Result<Unit> = mutateSources { current ->
        RecommendSourceRules.add(current, name, url)
    }

    suspend fun updateSource(id: String, name: String, url: String): Result<Unit> = mutateSources { current ->
        RecommendSourceRules.update(current, id, name, url)
    }

    suspend fun removeSource(id: String) = mutateSources { current ->
        Result.success(RecommendSourceRules.remove(current, id))
    }

    suspend fun setSourceEnabled(id: String, enabled: Boolean) = mutateSources { current ->
        Result.success(RecommendSourceRules.setEnabled(current, id, enabled))
    }

    suspend fun moveSource(from: Int, to: Int) = mutateSources { current ->
        Result.success(RecommendSourceRules.move(current, from, to))
    }

    suspend fun moveSourceBy(id: String, offset: Int) = mutateSources { current ->
        Result.success(RecommendSourceRules.moveBy(current, id, offset))
    }

    suspend fun setSort(sort: RecommendSort) {
        mutex.withLock {
            dirty = true
            _sort.value = sort
            persist()
        }
    }

    suspend fun markConfirmed(identity: String) {
        mutex.withLock {
            dirty = true
            _confirmed.value = _confirmed.value + identity
            persist()
        }
    }

    suspend fun markInstalled(identity: String) {
        mutex.withLock {
            dirty = true
            _installed.value = _installed.value + identity
            persist()
        }
    }

    /** 恢复到出厂源列表（预置源 + 本地源），用户自己加的源会被丢弃。 */
    suspend fun resetToDefaults() {
        mutex.withLock {
            dirty = true
            _sources.value = RecommendSourceRules.defaultSources()
            persist()
        }
    }

    private suspend fun mutateSources(
        transform: (List<RecommendSource>) -> Result<List<RecommendSource>>,
    ): Result<Unit> = mutex.withLock {
        transform(_sources.value).fold(
            onSuccess = { next ->
                dirty = true
                _sources.value = next
                persist()
                Result.success(Unit)
            },
            onFailure = { error -> Result.failure(error) },
        )
    }

    private fun readFromDisk() {
        runCatching {
            if (!file.exists()) return@runCatching
            val parsed = json.decodeFromString(SourcesFile.serializer(), file.readText())
            _sources.value = RecommendSourceRules.normalize(parsed.sources)
            _sort.value = RecommendSort.fromWire(parsed.sort) ?: RecommendSort.DEFAULT
            _confirmed.value = parsed.confirmed.toSet()
            _installed.value = parsed.installed.toSet()
        }.onFailure {
            Log.w(TAG, "readFromDisk: 读取推荐源配置失败，使用默认源", it)
        }
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        runCatching {
            val payload = SourcesFile(
                sources = _sources.value,
                sort = _sort.value.wire,
                confirmed = _confirmed.value.toList(),
                installed = _installed.value.toList(),
            )
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(json.encodeToString(SourcesFile.serializer(), payload))
            if (!temp.renameTo(file)) {
                // Windows 上目标存在时 rename 可能失败，退回直接写（这里丢了也无所谓，重新拉一次即可）
                file.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure {
            Log.w(TAG, "persist: 保存推荐源配置失败", it)
        }
    }

    @Serializable
    private data class SourcesFile(
        val version: Int = 1,
        val sources: List<RecommendSource> = emptyList(),
        val sort: String = RecommendSort.DEFAULT.wire,
        val confirmed: List<String> = emptyList(),
        val installed: List<String> = emptyList(),
    )

    private companion object {
        const val TAG = "RecommendSourceStore"
        const val FILE_NAME = "recommend_sources.json"
        val json = RecommendIndexJson
    }
}
