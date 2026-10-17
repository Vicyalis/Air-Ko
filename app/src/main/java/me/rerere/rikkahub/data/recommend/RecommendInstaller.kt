package me.rerere.rikkahub.data.recommend

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.skills.SkillFrontmatterParser
import me.rerere.rikkahub.data.skills.SkillManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 安装结果。
 *
 * [steps] 是给用户看的执行流水（下载了多少字节、sha256 是哪个、落到了哪个目录），
 * 安装这种「会改变 AI 行为」的操作，必须让用户看得见每一步做了什么。
 */
data class RecommendInstallResult(
    val success: Boolean,
    val message: String,
    val steps: List<String> = emptyList(),
    val identity: String? = null,
)

/**
 * 把推荐条目真正装进 App：
 * - skill → 解压/落盘到沙箱 `filesDir/skills`（工作区终端里就是 `/skills`）
 * - prompt / quick_message → 写进设置里的对应表
 * - mcp → 生成可用的 MCP 配置并写入 MCP 设置（仅远程类；本地 npx/uvx/docker 需要先在终端跑起来）
 *
 * 安全约束（见设计规格 §9 的坑②）：
 * 1. 先下载、再校验 sha256，**不匹配立即拒绝**，不落盘；
 * 2. 没提供 sha256 时允许继续，但在流水里明确写「未提供 sha256，跳过完整性校验」；
 * 3. 压缩包内路径做了归一化，`..` 之类的条目不参与落盘。
 */
class RecommendInstaller(
    private val client: OkHttpClient,
    private val skillManager: SkillManager,
    private val settingsStore: SettingsStore,
) {
    suspend fun install(entry: RecommendEntry): RecommendInstallResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<String>()
        try {
            val payload = resolvePayload(entry, steps)
                ?: return@withContext RecommendInstallResult(
                    success = false,
                    message = "这个条目没有可安装的内容（既没有内嵌内容也没有下载地址）",
                    steps = steps,
                )

            // inline 内容是从 index 里读出来的，来源就是 index 自己
            if (entry.installKind == InstallKind.INLINE) {
                steps += "使用 index 内嵌内容（${payload.size} 字节）"
            }

            if (!verifyPayload(entry, payload, steps)) {
                return@withContext RecommendInstallResult(
                    success = false,
                    message = "sha256 校验失败，已拒绝安装（内容与 index 声明不一致）",
                    steps = steps,
                )
            }

            when (entry.type) {
                RecommendType.SKILL -> installSkill(entry, payload, steps)
                RecommendType.PROMPT -> installPrompt(entry, payload, steps)
                RecommendType.QUICK_MESSAGE -> installQuickMessage(entry, payload, steps)
                RecommendType.MCP -> installMcp(entry, steps)
            }
        } catch (e: Exception) {
            Log.w(TAG, "install: ${entry.identity} 安装失败", e)
            RecommendInstallResult(
                success = false,
                message = "安装失败：${e.message ?: e::class.java.simpleName}",
                steps = steps,
            )
        }
    }

    /** 取到待安装的字节：优先内嵌内容，其次按 source_url 下载。 */
    private suspend fun resolvePayload(entry: RecommendEntry, steps: MutableList<String>): ByteArray? {
        if (entry.installKind == InstallKind.INLINE && entry.content.isNotBlank()) {
            return entry.content.toByteArray(Charsets.UTF_8)
        }
        if (entry.sourceUrl.isBlank()) return null

        steps += "来源：${entry.sourceUrl}"
        val bytes = download(entry.sourceUrl)
        steps += "已下载 ${bytes.size} 字节"
        return bytes
    }

    private fun verifyPayload(
        entry: RecommendEntry,
        payload: ByteArray,
        steps: MutableList<String>,
    ): Boolean {
        if (!entry.hasChecksum) {
            steps += "该条目未提供 sha256，已跳过完整性校验（来源见上）"
            return true
        }
        val actual = sha256Hex(payload)
        steps += "sha256：$actual"
        if (actual == entry.sha256) {
            steps += "sha256 校验通过"
            return true
        }
        steps += "index 声明：${entry.sha256}"
        return false
    }

    private suspend fun installSkill(
        entry: RecommendEntry,
        payload: ByteArray,
        steps: MutableList<String>,
    ): RecommendInstallResult {
        val files: Map<String, ByteArray>
        val skillName: String

        if (isZip(payload)) {
            steps += "识别为压缩包，开始解压"
            when (val result = extractSkillArchive(payload)) {
                is SkillArchiveResult.Failure -> return RecommendInstallResult(false, result.message, steps)
                is SkillArchiveResult.Success -> {
                    files = result.archive.files
                    skillName = result.archive.skillName
                    steps += "解压出 ${files.size} 个文件，技能名：$skillName"
                }
            }
        } else {
            val text = payload.toString(Charsets.UTF_8)
            val frontmatter = SkillFrontmatterParser.parse(text)
            val name = frontmatter["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                return RecommendInstallResult(false, "SKILL.md 缺少 name 字段，无法安装", steps)
            }
            if (frontmatter["description"].isNullOrBlank()) {
                return RecommendInstallResult(false, "SKILL.md 缺少 description 字段，无法安装", steps)
            }
            files = mapOf("SKILL.md" to payload)
            skillName = name
            steps += "识别为单个 SKILL.md，技能名：$skillName"
        }

        if (!isSafeSkillName(skillName)) {
            return RecommendInstallResult(false, "技能名包含非法字符：$skillName", steps)
        }

        val saved = skillManager.saveSkillFileBytesAtomically(skillName, files)
        if (!saved) {
            return RecommendInstallResult(false, "写入技能目录失败（沙箱目录不可写）", steps)
        }
        steps += "已写入沙箱技能目录：${skillManager.getSkillsDir().absolutePath}"
        return RecommendInstallResult(
            success = true,
            message = "技能「$skillName」已安装，可在技能页启用",
            steps = steps,
            identity = entry.identity,
        )
    }

    private suspend fun installPrompt(
        entry: RecommendEntry,
        payload: ByteArray,
        steps: MutableList<String>,
    ): RecommendInstallResult {
        val content = payload.toString(Charsets.UTF_8).trim()
        if (content.isEmpty()) {
            return RecommendInstallResult(false, "提示词内容为空，已取消", steps)
        }
        val injection = PromptInjection.ModeInjection(
            name = entry.name,
            content = content,
            enabled = true,
        )
        settingsStore.update { settings ->
            val existing = settings.modeInjections.firstOrNull { it.name == injection.name }
            settings.copy(
                modeInjections = if (existing != null) {
                    settings.modeInjections.map { if (it.id == existing.id) injection.copy(id = existing.id) else it }
                } else {
                    settings.modeInjections + injection
                }
            )
        }
        steps += "已写入模式注入（设置 → 提示词 → 模式注入）"
        return RecommendInstallResult(
            success = true,
            message = "提示词「${entry.name}」已加入模式注入",
            steps = steps,
            identity = entry.identity,
        )
    }

    private suspend fun installQuickMessage(
        entry: RecommendEntry,
        payload: ByteArray,
        steps: MutableList<String>,
    ): RecommendInstallResult {
        val content = payload.toString(Charsets.UTF_8).trim()
        if (content.isEmpty()) {
            return RecommendInstallResult(false, "快捷消息内容为空，已取消", steps)
        }
        settingsStore.update { settings ->
            val existing = settings.quickMessages.firstOrNull { it.title == entry.name }
            val message = QuickMessage(title = entry.name, content = content)
            settings.copy(
                quickMessages = if (existing != null) {
                    settings.quickMessages.map { if (it.id == existing.id) message.copy(id = existing.id) else it }
                } else {
                    settings.quickMessages + message
                }
            )
        }
        steps += "已写入快捷消息列表"
        return RecommendInstallResult(
            success = true,
            message = "快捷消息「${entry.name}」已添加",
            steps = steps,
            identity = entry.identity,
        )
    }

    /**
     * MCP：只有远程类条目能一键写入（本 App 的 MCP 客户端目前只支持 SSE / Streamable HTTP）。
     *
     * npx/uvx/docker 类的 server 得先在设备上跑起来（部署讲解里有命令），本地 stdio 没法直接吃进设置，
     * 所以这里如实拒绝，而不是写一条永远连不上的配置进去。
     */
    private suspend fun installMcp(
        entry: RecommendEntry,
        steps: MutableList<String>,
    ): RecommendInstallResult {
        val url = entry.resolvedRemoteUrl
        if (url.isBlank()) {
            return RecommendInstallResult(
                success = false,
                message = "这是本地 ${entry.installKind.wire} 类型的 MCP server，需要先在终端里按部署讲解跑起来，" +
                    "再用它暴露出的 HTTP 地址手动添加",
                steps = steps,
            )
        }
        if (!isHttpUrl(url)) {
            return RecommendInstallResult(false, "MCP 地址不是 http/https：$url", steps)
        }

        val config = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = entry.name),
            url = url,
        )
        settingsStore.update { settings ->
            val existing = settings.mcpServers.firstOrNull { it.commonOptions.name == entry.name }
            settings.copy(
                mcpServers = if (existing != null) {
                    settings.mcpServers.map {
                        if (it.id == existing.id) config.clone(id = existing.id) else it
                    }
                } else {
                    settings.mcpServers + config
                }
            )
        }
        steps += "已写入 MCP 设置：${entry.name} → $url"
        return RecommendInstallResult(
            success = true,
            message = "MCP server「${entry.name}」已写入设置",
            steps = steps,
            identity = entry.identity,
        )
    }

    private suspend fun download(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .build()
        return client.newCall(request).awaitBytes()
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

    private companion object {
        const val TAG = "RecommendInstaller"
    }

    /** 技能名会当成目录名用，只允许常规字符，避免路径穿越。 */
    private fun isSafeSkillName(name: String): Boolean =
        name.isNotBlank() &&
            !name.contains('/') &&
            !name.contains('\\') &&
            !name.contains("..") &&
            name.none { it.isISOControl() } &&
            File(name).name == name
}

/** 带体积上限的下载，超限直接失败而不是把设备内存吃满。 */
private suspend fun Call.awaitBytes(): ByteArray = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val body = response.body ?: throw IOException("响应为空")
                        if (body.contentLength() > RecommendInstallerLimits.MAX_PAYLOAD_BYTES) {
                            throw IOException("文件超过 ${RecommendInstallerLimits.MAX_PAYLOAD_BYTES / 1024 / 1024} MB")
                        }
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        body.byteStream().use { input ->
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > RecommendInstallerLimits.MAX_PAYLOAD_BYTES) {
                                    throw IOException("文件超过 ${RecommendInstallerLimits.MAX_PAYLOAD_BYTES / 1024 / 1024} MB")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                        if (continuation.isActive) continuation.resume(output.toByteArray())
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        }
    )
}

/** 下载上限对文件级 helper 也可用（companion 常量无法直接从顶层函数访问）。 */
private object RecommendInstallerLimits {
    const val MAX_PAYLOAD_BYTES = 8L * 1024 * 1024
}
