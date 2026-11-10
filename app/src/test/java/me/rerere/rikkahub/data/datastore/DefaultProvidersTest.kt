package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Air-Ko 只保留 9 家预置供应商；这里锁住名单，防止以后又把别的预设加回来。
 */
class DefaultProvidersTest {
    @Test
    fun `default providers keep exactly the nine supported presets`() {
        val names = DEFAULT_PROVIDERS.map { it.name }

        assertEquals(
            listOf("OpenAI", "Gemini", "硅基流动", "DeepSeek", "月之暗面", "OpenRouter", "MiniMax", "MIMO", "OpenCode Go"),
            names,
        )
    }

    @Test
    fun `opencode go preset points at the openai compatible go endpoint`() {
        val provider = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.OpenAI>()
            .single { it.name == "OpenCode Go" }

        assertEquals("https://opencode.ai/zen/go/v1", provider.baseUrl)
        assertTrue(provider.models.isNotEmpty())
        assertTrue(provider.models.any { it.modelId == "deepseek-v4.1-flash" })
    }

    @Test
    fun `default providers are all built in and have a base url or api key field`() {
        assertTrue(DEFAULT_PROVIDERS.all { it.builtIn })
    }

    @Test
    fun `deepseek and moonshot keep their balance endpoints`() {
        val openAiProviders = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.OpenAI>()

        val deepSeek = openAiProviders.single { it.name == "DeepSeek" }
        assertTrue(deepSeek.balanceOption.enabled)
        assertEquals("/user/balance", deepSeek.balanceOption.apiPath)

        val moonshot = openAiProviders.single { it.name == "月之暗面" }
        assertTrue(moonshot.balanceOption.enabled)
        assertEquals("/users/me/balance", moonshot.balanceOption.apiPath)
    }
}
