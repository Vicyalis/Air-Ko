package me.rerere.rikkahub.ui.pages.extensions.plugins

import androidx.annotation.StringRes
import me.rerere.rikkahub.R

/**
 * 推荐商店里的一条 DeepSeek 生态插件。
 *
 * 每个条目都带一份随 APK 打包的 Air-Ko 移植清单（[assetPath]）：点「下载」会直接安装并启用，
 * 和技能一样可用。原项目是 Cordis/Node 应用或服务，其代码不会在 Android 上运行；
 * 移植版把它们的提示词/工作流提取成 Air-Ko 插件。名称/仓库/主页是专有名词，只有描述走字符串资源。
 */
data class RecommendedHarnessPlugin(
    val id: String,
    val name: String,
    val repo: String,
    val homepage: String,
    val assetPath: String,
    @StringRes val descriptionRes: Int,
)

val RECOMMENDED_HARNESS_PLUGINS: List<RecommendedHarnessPlugin> = listOf(
    RecommendedHarnessPlugin(
        id = "deepseek-archify",
        name = "Archify",
        repo = "tt-a1i/archify",
        homepage = "https://github.com/tt-a1i/archify",
        assetPath = "plugin_store/deepseek-archify.json",
        descriptionRes = R.string.plugins_store_archify_desc,
    ),
    RecommendedHarnessPlugin(
        id = "deepseek-dsh-desktop",
        name = "dsh-desktop",
        repo = "anywhere-labs/dsh-desktop",
        homepage = "https://github.com/anywhere-labs/dsh-desktop",
        assetPath = "plugin_store/deepseek-dsh-desktop.json",
        descriptionRes = R.string.plugins_store_dsh_desktop_desc,
    ),
    RecommendedHarnessPlugin(
        id = "deepseek-our-free-model",
        name = "dsh-our-free-model",
        repo = "Ebony-Vinyl/dsh-our-free-model",
        homepage = "https://github.com/Ebony-Vinyl/dsh-our-free-model",
        assetPath = "plugin_store/deepseek-our-free-model.json",
        descriptionRes = R.string.plugins_store_dsh_free_model_desc,
    ),
    RecommendedHarnessPlugin(
        id = "deepseek-openviking",
        name = "OpenViking",
        repo = "volcengine/OpenViking",
        homepage = "https://github.com/volcengine/OpenViking",
        assetPath = "plugin_store/deepseek-openviking.json",
        descriptionRes = R.string.plugins_store_openviking_desc,
    ),
    RecommendedHarnessPlugin(
        id = "deepseek-reasonix",
        name = "DeepSeek-Reasonix",
        repo = "esengine/DeepSeek-Reasonix",
        homepage = "https://github.com/esengine/DeepSeek-Reasonix",
        assetPath = "plugin_store/deepseek-reasonix.json",
        descriptionRes = R.string.plugins_store_reasonix_desc,
    ),
    RecommendedHarnessPlugin(
        id = "deepseek-rea",
        name = "rea",
        repo = "morluto/rea",
        homepage = "https://github.com/morluto/rea",
        assetPath = "plugin_store/deepseek-rea.json",
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
