package me.rerere.tts.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 服务端可选的一个音色。
 *
 * @param group 服务端给的分组（ElevenLabs 的 category、MiniMax 的音色来源），UI 可以按组显示
 */
data class TtsVoiceOption(
    val id: String,
    val label: String = id,
    val group: String? = null,
)

/**
 * 一次列表拉取的结果。
 *
 * 字段为 null 表示该服务**没有**对应接口（不是失败）；请求或解析失败会抛 [TtsCatalogException]，
 * 让 UI 能把「这里没有列表功能」和「列表拿不到」分开提示。
 *
 * @param live 至少有一项来自服务端实时接口；false 表示全是内置建议表
 */
data class TtsCatalog(
    val models: List<String>? = null,
    val voices: List<TtsVoiceOption>? = null,
    val live: Boolean = false,
)

/** 列表拉取失败：把原因带给用户，而不是静默给一份内置表 */
class TtsCatalogException(message: String, cause: Throwable? = null) : Exception(message, cause)

private val catalogClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}

// ---------- 内置表（服务端没有列表接口时的手动输入提示，可能随官方更新） ----------

/** OpenAI 官方内置音色（`gpt-4o-mini-tts` 还支持 instructions 自定义语气） */
private val OPENAI_VOICES = listOf(
    "alloy", "ash", "ballad", "coral", "echo", "fable",
    "nova", "onyx", "sage", "shimmer", "verse",
)

/** OpenAI 的 TTS 模型；`/v1/models` 被 key 限制拉不到时用它兜底 */
private val OPENAI_TTS_MODELS = listOf("gpt-4o-mini-tts", "tts-1", "tts-1-hd")

/** Gemini 官方预置音色（TTS 的 voiceName 取值） */
private val GEMINI_VOICES = listOf(
    "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede",
    "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba",
    "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
    "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi",
    "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat",
)

/** MiniMax 语音模型档位（hd 质量优先 / turbo 速度优先） */
private val MINIMAX_MODELS = listOf(
    "speech-2.8-hd", "speech-2.8-turbo",
    "speech-02-hd", "speech-02-turbo",
    "speech-01-hd", "speech-01-turbo",
)

/** xAI 官方预置音色 */
private val XAI_VOICES = listOf("eve", "ara", "leo", "rex", "sal")

/** 火山引擎语音合成的资源 ID（版本 / 并发档位取值不同） */
private val VOLCENGINE_RESOURCE_IDS = listOf("seed-tts-2.0", "seed-tts-1.0", "seed-tts-1.0-concurr")

/**
 * 拉取该服务支持的 model / voice 列表。
 *
 * - 字段为 null：该服务没有对应接口，UI 提示手动填写
 * - 抛 [TtsCatalogException]：有接口但请求 / 解析失败，把原因带给用户
 */
suspend fun TTSProviderSetting.fetchCatalog(): TtsCatalog = withContext(Dispatchers.IO) {
    // withContext 的 lambda 接收者是 CoroutineScope，会把 this 遮住，这里先取回 provider 自己
    val provider = this@fetchCatalog
    when (provider) {
        is TTSProviderSetting.OpenAI -> {
            // key 被限制时 list models 里没有 tts 模型是常态，用内置表兜底，不当失败
            val live = runCatching {
                parseOpenAiModelIds(getText(joinUrl(provider.baseUrl, "/models"), bearer = provider.apiKey))
                    .filter { it.contains("tts", ignoreCase = true) || it.contains("audio", ignoreCase = true) }
            }.getOrNull().orEmpty()
            if (live.isEmpty()) {
                TtsCatalog(models = OPENAI_TTS_MODELS, voices = OPENAI_VOICES.toVoiceOptions())
            } else {
                TtsCatalog(models = live, voices = OPENAI_VOICES.toVoiceOptions(), live = true)
            }
        }

        is TTSProviderSetting.Gemini -> TtsCatalog(
            models = parseGeminiTtsModels(
                getText(
                    joinUrl(provider.baseUrl, "/models") +
                        "?key=" + URLEncoder.encode(provider.apiKey, "UTF-8")
                )
            ),
            voices = GEMINI_VOICES.toVoiceOptions(),
            live = true,
        )

        is TTSProviderSetting.Groq -> TtsCatalog(
            models = parseOpenAiModelIds(getText(joinUrl(provider.baseUrl, "/models"), bearer = provider.apiKey))
                .filter {
                    it.contains("tts", ignoreCase = true) ||
                        it.contains("orpheus", ignoreCase = true) ||
                        it.contains("playai", ignoreCase = true)
                },
            live = true,
        )

        is TTSProviderSetting.MiniMax -> TtsCatalog(
            models = MINIMAX_MODELS,
            voices = parseMiniMaxVoices(
                postJson(joinUrl(provider.baseUrl, "/get_voice"), """{"voice_type":"all"}""", bearer = provider.apiKey)
            ),
            live = true,
        )

        is TTSProviderSetting.XAI -> TtsCatalog(voices = XAI_VOICES.toVoiceOptions())

        is TTSProviderSetting.ElevenLabs -> TtsCatalog(
            models = parseElevenLabsModels(
                getText(joinUrl(provider.baseUrl, "/v1/models"), header = "xi-api-key" to provider.apiKey)
            ),
            voices = parseElevenLabsVoices(
                getText(joinUrl(provider.baseUrl, "/v1/voices"), header = "xi-api-key" to provider.apiKey)
            ),
            live = true,
        )

        is TTSProviderSetting.FishAudio -> TtsCatalog(
            // Fish 的 /model 返回的是音色（voice model），不是合成引擎型号
            voices = parseFishModels(getText(joinUrl(provider.baseUrl, "/model"), bearer = provider.apiKey)),
            live = true,
        )

        is TTSProviderSetting.Volcengine -> TtsCatalog(models = VOLCENGINE_RESOURCE_IDS)

        // 没有列表接口：保持手动输入
        is TTSProviderSetting.Step,
        is TTSProviderSetting.Qwen,
        is TTSProviderSetting.MiMo,
        is TTSProviderSetting.SystemTTS -> TtsCatalog()
    }
}

/** 该服务是否有可拉取的列表（UI 据此决定要不要显示「获取列表」按钮） */
fun TTSProviderSetting.hasCatalog(): Boolean = when (this) {
    is TTSProviderSetting.OpenAI,
    is TTSProviderSetting.Gemini,
    is TTSProviderSetting.Groq,
    is TTSProviderSetting.MiniMax,
    is TTSProviderSetting.XAI,
    is TTSProviderSetting.ElevenLabs,
    is TTSProviderSetting.FishAudio,
    is TTSProviderSetting.Volcengine -> true

    else -> false
}

/** 当前配置里「模型」位的值（各 provider 字段名不同，UI 用来高亮已选项） */
val TTSProviderSetting.currentModelValue: String?
    get() = when (this) {
        is TTSProviderSetting.OpenAI -> model
        is TTSProviderSetting.Gemini -> model
        is TTSProviderSetting.MiniMax -> model
        is TTSProviderSetting.Qwen -> model
        is TTSProviderSetting.Groq -> model
        is TTSProviderSetting.MiMo -> model
        is TTSProviderSetting.ElevenLabs -> model
        is TTSProviderSetting.FishAudio -> model
        is TTSProviderSetting.Step -> model
        is TTSProviderSetting.Volcengine -> resourceId
        is TTSProviderSetting.XAI, is TTSProviderSetting.SystemTTS -> null
    }

/** 当前配置里「音色」位的值 */
val TTSProviderSetting.currentVoiceValue: String?
    get() = when (this) {
        is TTSProviderSetting.OpenAI -> voice
        is TTSProviderSetting.Gemini -> voiceName
        is TTSProviderSetting.MiniMax -> voiceId
        is TTSProviderSetting.Qwen -> voice
        is TTSProviderSetting.Groq -> voice
        is TTSProviderSetting.MiMo -> voice
        is TTSProviderSetting.XAI -> voiceId
        is TTSProviderSetting.ElevenLabs -> voiceId
        is TTSProviderSetting.Step -> voice
        is TTSProviderSetting.FishAudio -> referenceId.ifEmpty { null }
        is TTSProviderSetting.Volcengine -> speaker
        is TTSProviderSetting.SystemTTS -> null
    }

/**
 * 把列表里选中的值写回配置；传 null 表示该项不动。
 *
 * 各 provider 的字段名不一样（voice / voiceName / voiceId / referenceId / speaker），统一在这里映射，
 * 免得 UI 里写 12 份 when。
 */
fun TTSProviderSetting.withCatalogSelection(
    model: String? = null,
    voice: String? = null,
): TTSProviderSetting = when (this) {
    is TTSProviderSetting.OpenAI -> copy(model = model ?: this.model, voice = voice ?: this.voice)
    is TTSProviderSetting.Gemini -> copy(model = model ?: this.model, voiceName = voice ?: this.voiceName)
    is TTSProviderSetting.MiniMax -> copy(model = model ?: this.model, voiceId = voice ?: this.voiceId)
    is TTSProviderSetting.Qwen -> copy(model = model ?: this.model, voice = voice ?: this.voice)
    is TTSProviderSetting.Groq -> copy(model = model ?: this.model, voice = voice ?: this.voice)
    is TTSProviderSetting.MiMo -> copy(model = model ?: this.model, voice = voice ?: this.voice)
    is TTSProviderSetting.XAI -> copy(voiceId = voice ?: this.voiceId)
    is TTSProviderSetting.ElevenLabs -> copy(model = model ?: this.model, voiceId = voice ?: this.voiceId)
    is TTSProviderSetting.FishAudio -> copy(model = model ?: this.model, referenceId = voice ?: this.referenceId)
    is TTSProviderSetting.Step -> copy(model = model ?: this.model, voice = voice ?: this.voice)
    is TTSProviderSetting.Volcengine -> copy(resourceId = model ?: this.resourceId, speaker = voice ?: this.speaker)
    is TTSProviderSetting.SystemTTS -> this
}

// ---------- HTTP ----------

private fun joinUrl(base: String, path: String): String = base.trimEnd('/') + path

private fun getText(url: String, bearer: String? = null, header: Pair<String, String>? = null): String {
    val request = try {
        val builder = Request.Builder().url(url).get()
        if (!bearer.isNullOrEmpty()) builder.header("Authorization", "Bearer $bearer")
        if (header != null) builder.header(header.first, header.second)
        builder.build()
    } catch (e: Exception) {
        throw TtsCatalogException("请求地址无效：${e.message ?: e.javaClass.simpleName}", e)
    }
    return execute(request)
}

private fun postJson(url: String, body: String, bearer: String? = null): String {
    val request = try {
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
        if (!bearer.isNullOrEmpty()) builder.header("Authorization", "Bearer $bearer")
        builder.build()
    } catch (e: Exception) {
        throw TtsCatalogException("请求地址无效：${e.message ?: e.javaClass.simpleName}", e)
    }
    return execute(request)
}

private fun execute(request: Request): String {
    val response = try {
        catalogClient.newCall(request).execute()
    } catch (e: Exception) {
        throw TtsCatalogException("请求失败：${e.message ?: e.javaClass.simpleName}", e)
    }
    return response.use { resp ->
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            throw TtsCatalogException("服务返回 HTTP ${resp.code}：${text.take(300)}")
        }
        text
    }
}

private val catalogJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

// ---------- 解析（internal 便于单测） ----------
// 全部容错：字段缺失就跳过，不抛异常。这里不用 org.json —— 它在 JVM 单测里是 android 的桩实现，
// 测不到真实解析行为；kotlinx.serialization 是模块里已有的依赖，也不会引新包。

/** OpenAI 风格 `{"data":[{"id":...}]}`（OpenAI / Groq 等兼容接口） */
internal fun parseOpenAiModelIds(json: String): List<String> =
    parseJson(json)?.objOrNull()?.get("data")?.arrOrNull()
        ?.mapNotNull { it.objOrNull()?.str("id") }
        .orEmpty()

/** Gemini `/models`：只留名字里带 tts 的，并去掉 `models/` 前缀 */
internal fun parseGeminiTtsModels(json: String): List<String> =
    parseJson(json)?.objOrNull()?.get("models")?.arrOrNull()
        ?.mapNotNull { it.objOrNull()?.str("name")?.removePrefix("models/") }
        ?.filter { it.contains("tts", ignoreCase = true) }
        .orEmpty()

/** ElevenLabs `/v1/models`：顶层是数组，取 model_id */
internal fun parseElevenLabsModels(json: String): List<String> =
    parseJson(json)?.arrOrNull()
        ?.mapNotNull { element ->
            val obj = element.objOrNull() ?: return@mapNotNull null
            obj.str("model_id") ?: obj.str("id")
        }
        .orEmpty()

/** ElevenLabs `/v1/voices`：`{"voices":[{"voice_id","name","category"}]}` */
internal fun parseElevenLabsVoices(json: String): List<TtsVoiceOption> =
    parseJson(json)?.objOrNull()?.get("voices")?.arrOrNull()
        ?.mapNotNull { element ->
            val obj = element.objOrNull() ?: return@mapNotNull null
            val id = obj.str("voice_id") ?: obj.str("id") ?: return@mapNotNull null
            TtsVoiceOption(id = id, label = obj.str("name") ?: id, group = obj.str("category"))
        }
        .orEmpty()

/** MiniMax `get_voice`：系统音色 / 复刻音色 / 生成音色三组 */
internal fun parseMiniMaxVoices(json: String): List<TtsVoiceOption> {
    val root = parseJson(json)?.objOrNull() ?: return emptyList()
    val result = mutableListOf<TtsVoiceOption>()
    for (key in listOf("system_voice", "voice_cloning", "voice_generation")) {
        val voices = root[key]?.arrOrNull() ?: continue
        voices.mapNotNullTo(result) { element ->
            val obj = element.objOrNull() ?: return@mapNotNullTo null
            val id = obj.str("voice_id") ?: obj.str("id") ?: return@mapNotNullTo null
            TtsVoiceOption(id = id, label = obj.str("voice_name") ?: obj.str("name") ?: id, group = key)
        }
    }
    return result
}

/** Fish Audio `/model`：兼容 `{"items":[...]}` 与裸数组两种返回 */
internal fun parseFishModels(json: String): List<TtsVoiceOption> {
    val element = parseJson(json) ?: return emptyList()
    val array = (element.objOrNull()?.get("items") ?: element).arrOrNull() ?: return emptyList()
    return array.mapNotNull { item ->
        val obj = item.objOrNull() ?: return@mapNotNull null
        val id = obj.str("_id") ?: obj.str("id") ?: return@mapNotNull null
        TtsVoiceOption(id = id, label = obj.str("title") ?: id)
    }
}

private fun parseJson(text: String): JsonElement? =
    runCatching { catalogJson.parseToJsonElement(text) }.getOrNull()

private fun JsonElement.objOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement.arrOrNull(): JsonArray? = this as? JsonArray

private fun JsonObject.str(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

private fun List<String>.toVoiceOptions(): List<TtsVoiceOption> = map { TtsVoiceOption(it) }
