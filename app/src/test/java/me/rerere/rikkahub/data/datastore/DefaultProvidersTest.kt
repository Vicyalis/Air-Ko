package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Air-Ko 只保留 8 家预置供应商；这里锁住名单，防止以后又把别的预设加回来。
 */
class DefaultProvidersTest {
    @Test
    fun `default providers keep exactly the eight supported presets`() {
        val names = DEFAULT_PROVIDERS.map { it.name }

        assertEquals(
            listOf("OpenAI", "Gemini", "硅基流动", "DeepSeek", "月之暗面", "OpenRouter", "MiniMax", "MIMO"),
            names,
        )
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
