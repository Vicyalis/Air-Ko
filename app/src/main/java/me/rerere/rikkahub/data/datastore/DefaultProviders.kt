package me.rerere.rikkahub.data.datastore

import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import kotlin.uuid.Uuid

val DEFAULT_AUTO_MODEL_ID = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08")

/**
 * 预置供应商（Air-Ko 口径：只保留这 9 家，其余供应商预设已移除）。
 *
 * 想用别的服务时，在「供应商」页里新增一个 OpenAI 兼容供应商、填上自己的 baseUrl 即可。
 */
val DEFAULT_PROVIDERS = listOf(
    ProviderSetting.OpenAI(
        id = Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e"),
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "",
        builtIn = true
    ),
    ProviderSetting.Google(
        id = Uuid.parse("6ab18148-c138-4394-a46f-1cd8c8ceaa6d"),
        name = "Gemini",
        apiKey = "",
        enabled = true,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("56a94d29-c88b-41c5-8e09-38a7612d6cf8"),
        name = "硅基流动",
        baseUrl = "https://api.siliconflow.cn/v1",
        apiKey = "",
        builtIn = true,
        description = {
            MarkdownBlock(
                content = """
                    ${stringResource(R.string.silicon_flow_description)}
                    ${stringResource(R.string.silicon_flow_website)}
                """.trimIndent()
            )
        },
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b"),
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "",
        builtIn = true,
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/user/balance",
            resultPath = "balance_infos[0].total_balance"
        )
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("d6c4d8c6-3f62-4ca9-a6f3-7ade6b15ecc3"),
        name = "月之暗面",
        baseUrl = "https://api.moonshot.cn/v1",
        apiKey = "",
        enabled = true,
        builtIn = true,
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/users/me/balance",
            resultPath = "data.available_balance"
        )
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("d5734028-d39b-4d41-9841-fd648d65440e"),
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKey = "",
        builtIn = true,
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/credits",
            resultPath = "data.total_credits - data.total_usage",
        )
    ),
    ProviderSetting.Claude(
        id = Uuid.parse("b4deabea-20fb-4101-a74c-65679c7e4754"),
        name = "MiniMax",
        baseUrl = "https://api.minimaxi.com/anthropic/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("a2bafe83-eaf8-47bf-a8c7-3dd82d89f637"),
        name = "MIMO",
        baseUrl = "https://api.xiaomimimo.com/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("be902bea-c3b4-5ccd-85f7-9675a74ef834"),
        name = "OpenCode Go",
        baseUrl = "https://opencode.ai/zen/go/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
        models = listOf(
            openCodeGoModel("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", "f65b8a02-35e5-55f4-b075-a412d0eb768d"),
            openCodeGoModel("deepseek-v4-pro", "DeepSeek V4 Pro", "6ae61cbb-e798-5175-8d35-deb1b4e64448"),
            openCodeGoModel("kimi-k3.1-pro", "Kimi K3.1 Pro", "010dd66c-3bd6-5b66-9ce9-a5eedc09b127"),
            openCodeGoModel("kimi-k3", "Kimi K3", "a256036c-f9c9-51b0-bb3a-a40416f45f50"),
            openCodeGoModel("kimi-k2.7-code", "Kimi K2.7 Code", "4f20e5e4-2e49-5ede-be89-de8ad55c649b"),
            openCodeGoModel("glm-5.3-flash", "GLM-5.3-Flash", "10ece95d-d129-5a33-8de2-7cb9984a1b82"),
            openCodeGoModel("glm-5.3", "GLM-5.3", "a97cfc8c-2be0-575e-bb8e-ed7e3eb27d29"),
            openCodeGoModel("glm-5.2", "GLM-5.2", "7670dc8d-e1ce-56ad-92aa-97c527a35f2b"),
            openCodeGoModel("minimax-m3.1-flash", "MiniMax M3.1 Flash", "0d5a484f-9427-549d-8244-2e80cf85193c"),
            openCodeGoModel("mimo-v2.6-flash", "MiMo V2.6 Flash", "6b621d4d-4a9c-5799-980f-d2633ef21221"),
            openCodeGoModel("mimo-v2.6-pro", "MiMo V2.6 Pro", "8f818e9d-a742-532a-8b83-40f77ac7177a"),
            openCodeGoModel("longcat-2.0", "LongCat-2.0", "38d598e7-abad-5992-a16d-bf17db98ae06"),
            openCodeGoModel("hy4", "Hy4", "067baf66-f567-55f2-b42c-aa8b3abcb80a"),
            openCodeGoModel("step-5-preview", "Step 5 Preview", "296e7aef-13b0-50ec-82df-01c855739650"),
        ),
        description = {
            MarkdownBlock(
                content = stringResource(R.string.provider_opencode_go_description)
            )
        },
    ),
)

private fun openCodeGoModel(id: String, name: String, uuid: String): Model = Model(
    modelId = id,
    displayName = name,
    id = Uuid.parse(uuid),
    abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
    contextLength = 256_000,
)
