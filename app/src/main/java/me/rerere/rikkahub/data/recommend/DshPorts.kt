package me.rerere.rikkahub.data.recommend

import java.net.URI

/**
 * DSH 插件在 Android 端的兼容级别。
 *
 * DSH 插件本身是 Cordis/Node ESM 包（`dsh.bundle.patch` + `dsh.client`），
 * Air-Ko 不能执行这类代码。商店按包内实际内容把兼容路径分成四类：
 * - [PORT]：我们随 APK 打包了提示词/工作流移植版，安装后直接启用；
 * - [SKILL]：已知是技能包，安装时提取 `SKILL.md` 作为 Air-Ko 技能；
 * - [AUTO]：安装时下载包并自动检测技能/指令文件，能装就装；
 * - [RUNTIME]：明确的 UI/运行时插件，纯代码部分需要 DSH（Node）宿主。
 */
enum class DshPluginKind(val wire: String) {
    PORT("port"),
    SKILL("skill"),
    AUTO("auto"),
    RUNTIME("runtime");

    companion object {
        fun fromWire(value: String?): DshPluginKind? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

/** 一条随 APK 打包的 Air-Ko 移植条目。 */
data class DshPort(
    val id: String,
    val name: String,
    val assetPath: String,
    val repoPath: String,
    val packageNames: Set<String> = emptySet(),
)

/**
 * DSH 原项目 → Air-Ko 移植版的映射。
 *
 * 只按包名/仓库精确匹配，绝不接受索引里传来的 asset 路径，避免外部目录把安装流程
 * 指向任意 APK 资源。没有映射的条目走“下载后提取技能/指令”的兼容路径。
 */
object DshPorts {
    private val ports = listOf(
        DshPort(
            id = "deepseek-archify",
            name = "Archify",
            assetPath = "plugin_store/deepseek-archify.json",
            repoPath = "tt-a1i/archify",
            packageNames = setOf("@tt-a1i/archify-dsh"),
        ),
        DshPort(
            id = "deepseek-dsh-desktop",
            name = "DSH Desktop",
            assetPath = "plugin_store/deepseek-dsh-desktop.json",
            repoPath = "anywhere-labs/dsh-desktop",
        ),
        DshPort(
            id = "deepseek-our-free-model",
            name = "dsh-our-free-model",
            assetPath = "plugin_store/deepseek-our-free-model.json",
            repoPath = "ebony-vinyl/dsh-our-free-model",
        ),
        DshPort(
            id = "deepseek-openviking",
            name = "OpenViking",
            assetPath = "plugin_store/deepseek-openviking.json",
            repoPath = "volcengine/openviking",
            packageNames = setOf("@openviking/dsh-memory-plugin"),
        ),
        DshPort(
            id = "deepseek-reasonix",
            name = "DeepSeek-Reasonix",
            assetPath = "plugin_store/deepseek-reasonix.json",
            repoPath = "esengine/deepseek-reasonix",
        ),
        DshPort(
            id = "deepseek-rea",
            name = "REA",
            assetPath = "plugin_store/deepseek-rea.json",
            repoPath = "morluto/rea",
        ),
    )

    fun find(packageName: String, repositoryUrl: String): DshPort? {
        val name = packageName.trim().lowercase()
        val repo = normalizeRepo(repositoryUrl)
        return ports.firstOrNull { port ->
            (repo != null && port.repoPath.equals(repo, ignoreCase = true)) ||
                (name.isNotBlank() && port.packageNames.any { it.equals(name, ignoreCase = true) })
        }
    }

    /** 把 GitHub 仓库地址归一化成 `owner/repo`，忽略 `git+`、`.git`、`#readme` 与子目录。 */
    fun normalizeRepo(raw: String): String? {
        val trimmed = raw.trim()
            .removePrefix("git+")
            .removeSuffix("/")
        if (trimmed.isBlank()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "github.com" && !host.endsWith(".github.com")) return null
        val parts = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return "${parts[0]}/${parts[1].removeSuffix(".git")}"
    }
}
