package me.rerere.rikkahub.ui.pages.extensions.plugins

import androidx.annotation.StringRes
import me.rerere.rikkahub.R

/**
 * 推荐商店里的一条 DeepSeek Harness 生态插件。
 *
 * 这些条目只是外链，不参与 Air-Ko 的安装流程：它们是 Cordis/Node 插件，Android 端无法执行。
 * 名称/仓库/主页是专有名词，只有描述走字符串资源。
 */
data class RecommendedHarnessPlugin(
    val name: String,
    val repo: String,
    val homepage: String,
    @StringRes val descriptionRes: Int,
)

val RECOMMENDED_HARNESS_PLUGINS: List<RecommendedHarnessPlugin> = listOf(
    RecommendedHarnessPlugin(
        name = "Archify",
        repo = "tt-a1i/archify",
        homepage = "https://github.com/tt-a1i/archify",
        descriptionRes = R.string.plugins_store_archify_desc,
    ),
    RecommendedHarnessPlugin(
        name = "dsh-desktop",
        repo = "anywhere-labs/dsh-desktop",
        homepage = "https://github.com/anywhere-labs/dsh-desktop",
        descriptionRes = R.string.plugins_store_dsh_desktop_desc,
    ),
    RecommendedHarnessPlugin(
        name = "dsh-our-free-model",
        repo = "Ebony-Vinyl/dsh-our-free-model",
        homepage = "https://github.com/Ebony-Vinyl/dsh-our-free-model",
        descriptionRes = R.string.plugins_store_dsh_free_model_desc,
    ),
    RecommendedHarnessPlugin(
        name = "OpenViking",
        repo = "volcengine/OpenViking",
        homepage = "https://github.com/volcengine/OpenViking",
        descriptionRes = R.string.plugins_store_openviking_desc,
    ),
    RecommendedHarnessPlugin(
        name = "DeepSeek-Reasonix",
        repo = "esengine/DeepSeek-Reasonix",
        homepage = "https://github.com/esengine/DeepSeek-Reasonix",
        descriptionRes = R.string.plugins_store_reasonix_desc,
    ),
    RecommendedHarnessPlugin(
        name = "rea",
        repo = "morluto/rea",
        homepage = "https://github.com/morluto/rea",
        descriptionRes = R.string.plugins_store_rea_desc,
    ),
)

/**
 * 「安装示例插件」用的内置清单，用来验证「安装 → 启用 → 进入系统提示词」整条链路。
 * 提示词本身保持无害，只影响回答格式。
 */
val SAMPLE_PLUGIN_JSON: String = """
    {
      "id": "airko-sample",
      "name": "Air-Ko Sample Plugin",
      "version": "1.0.0",
      "description": "A minimal prompt pack that asks the model to answer with a short summary first.",
      "author": "Air-Ko",
      "homepage": "",
      "prompt": "When answering, start with a one or two sentence summary, then list the key points as short bullets. Keep the response concise and avoid filler.",
      "minHarness": false
    }
""".trimIndent()
