package me.rerere.tts.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSProviderCatalogTest {

    @Test
    fun openAiModelIdsAreParsed() {
        val json = """{"object":"list","data":[{"id":"gpt-4o-mini-tts"},{"id":"whisper-1"},{"id":"tts-1"}]}"""
        assertEquals(listOf("gpt-4o-mini-tts", "whisper-1", "tts-1"), parseOpenAiModelIds(json))
    }

    @Test
    fun brokenOpenAiJsonReturnsEmpty() {
        assertEquals(emptyList<String>(), parseOpenAiModelIds("not json"))
        assertEquals(emptyList<String>(), parseOpenAiModelIds("""{"data":"oops"}"""))
    }

    @Test
    fun geminiOnlyKeepsTtsModelsAndStripsPrefix() {
        val json = """
            {"models":[
              {"name":"models/gemini-2.5-flash"},
              {"name":"models/gemini-2.5-flash-preview-tts"},
              {"name":"models/gemini-embedding-001"}
            ]}
        """.trimIndent()
        assertEquals(listOf("gemini-2.5-flash-preview-tts"), parseGeminiTtsModels(json))
    }

    @Test
    fun elevenLabsModelsAndVoicesAreParsed() {
        val models = """[{"model_id":"eleven_multilingual_v2"},{"model_id":"eleven_v3"}]"""
        assertEquals(listOf("eleven_multilingual_v2", "eleven_v3"), parseElevenLabsModels(models))

        val voices = """
            {"voices":[
              {"voice_id":"JBFqnCBsd6RMkjVDRZzb","name":"George","category":"premade"},
              {"voice_id":"abc","name":"","category":""}
            ]}
        """.trimIndent()
        val parsed = parseElevenLabsVoices(voices)
        assertEquals(2, parsed.size)
        assertEquals("George", parsed[0].label)
        assertEquals("premade", parsed[0].group)
        // 没有名字就用 id，不产生空标签
        assertEquals("abc", parsed[1].label)
        assertNull(parsed[1].group)
    }

    @Test
    fun miniMaxVoicesAreGroupedBySource() {
        val json = """
            {"system_voice":[{"voice_id":"female-shaonv","voice_name":"少女音色"}],
             "voice_cloning":[{"voice_id":"clone-1","voice_name":"我的音色"}],
             "voice_generation":[]}
        """.trimIndent()
        val parsed = parseMiniMaxVoices(json)
        assertEquals(2, parsed.size)
        assertEquals("female-shaonv", parsed[0].id)
        assertEquals("少女音色", parsed[0].label)
        assertEquals("system_voice", parsed[0].group)
        assertEquals("voice_cloning", parsed[1].group)
    }

    @Test
    fun fishModelsSupportItemsAndBareArray() {
        val wrapped = """{"items":[{"_id":"7f1","title":"我的音色"}]}"""
        val bare = """[{"_id":"7f2"}]"""
        assertEquals("我的音色", parseFishModels(wrapped).single().label)
        // 没有 title 就用 _id
        assertEquals("7f2", parseFishModels(bare).single().label)
        assertEquals(emptyList<TtsVoiceOption>(), parseFishModels("""{"total":0}"""))
    }

    @Test
    fun catalogSupportIsReportedPerProvider() {
        assertTrue(TTSProviderSetting.OpenAI().hasCatalog())
        assertTrue(TTSProviderSetting.Gemini().hasCatalog())
        assertTrue(TTSProviderSetting.ElevenLabs().hasCatalog())
        assertTrue(TTSProviderSetting.MiniMax().hasCatalog())
        assertTrue(TTSProviderSetting.FishAudio().hasCatalog())
        assertFalse(TTSProviderSetting.Step().hasCatalog())
        assertFalse(TTSProviderSetting.Qwen().hasCatalog())
        assertFalse(TTSProviderSetting.MiMo().hasCatalog())
        assertFalse(TTSProviderSetting.SystemTTS().hasCatalog())
    }

    @Test
    fun selectionWritesBackIntoTheRightFields() {
        // 字段名不同的 provider 各测一个，防止映射写错（返回的是密封基类，测试里转型回具体类型）
        val gemini = TTSProviderSetting.Gemini()
            .withCatalogSelection(model = "gemini-2.5-pro-preview-tts", voice = "Puck") as TTSProviderSetting.Gemini
        assertEquals("gemini-2.5-pro-preview-tts", gemini.model)
        assertEquals("Puck", gemini.voiceName)

        val eleven = TTSProviderSetting.ElevenLabs()
            .withCatalogSelection(model = "eleven_v3", voice = "abc") as TTSProviderSetting.ElevenLabs
        assertEquals("eleven_v3", eleven.model)
        assertEquals("abc", eleven.voiceId)

        val fish = TTSProviderSetting.FishAudio()
            .withCatalogSelection(voice = "7f1") as TTSProviderSetting.FishAudio
        assertEquals("7f1", fish.referenceId)

        val volcengine = TTSProviderSetting.Volcengine()
            .withCatalogSelection(model = "seed-tts-1.0", voice = "zh_female_vv_uranus_bigtts") as TTSProviderSetting.Volcengine
        assertEquals("seed-tts-1.0", volcengine.resourceId)
        assertEquals("zh_female_vv_uranus_bigtts", volcengine.speaker)

        // 传 null 的项要保持原样
        val openai = TTSProviderSetting.OpenAI()
            .withCatalogSelection(voice = "nova") as TTSProviderSetting.OpenAI
        assertEquals("gpt-4o-mini-tts", openai.model)
        assertEquals("nova", openai.voice)

        // 系统 TTS 没有可选 ID，调用它应是安全的空操作
        val system = TTSProviderSetting.SystemTTS().withCatalogSelection("a", "b")
        assertTrue(system is TTSProviderSetting.SystemTTS)
        assertEquals(1.0f, (system as TTSProviderSetting.SystemTTS).speechRate, 0.0001f)
    }

    @Test
    fun currentValuesFollowEachProviderField() {
        assertEquals("gpt-4o-mini-tts", TTSProviderSetting.OpenAI().currentModelValue)
        assertEquals("alloy", TTSProviderSetting.OpenAI().currentVoiceValue)
        assertEquals("Kore", TTSProviderSetting.Gemini().currentVoiceValue)
        assertEquals("female-shaonv", TTSProviderSetting.MiniMax().currentVoiceValue)
        assertEquals("seed-tts-2.0", TTSProviderSetting.Volcengine().currentModelValue)
        assertEquals("eve", TTSProviderSetting.XAI().currentVoiceValue)
        // 没填 referenceId 时视为「未选」，避免高亮到一个空项
        assertNull(TTSProviderSetting.FishAudio().currentVoiceValue)
        assertNull(TTSProviderSetting.SystemTTS().currentVoiceValue)
    }
}
