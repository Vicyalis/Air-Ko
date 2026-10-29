package me.rerere.rikkahub.ui.pages.extensions.recommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.sanitizeMcpServerName
import me.rerere.rikkahub.data.recommend.InstallKind
import me.rerere.rikkahub.data.recommend.RecommendEntry
import me.rerere.rikkahub.data.recommend.RecommendSort
import me.rerere.rikkahub.data.recommend.RecommendSource
import me.rerere.rikkahub.data.recommend.RecommendSourceRules
import me.rerere.rikkahub.data.recommend.RecommendType
import me.rerere.rikkahub.utils.toLocalDateTime
import me.rerere.rikkahub.utils.writeClipboardText

/** 导航参数里的 type 字符串 → 枚举；认不出来时按技能处理（不 crash 比严格更重要）。 */
fun recommendTypeFromNav(raw: String?): RecommendType =
    RecommendType.fromWire(raw) ?: RecommendType.SKILL

@Composable
fun recommendTypeTitle(type: RecommendType): String = stringResource(
    when (type) {
        RecommendType.SKILL -> R.string.wp9_title_skill
        RecommendType.PROMPT -> R.string.wp9_title_prompt
        RecommendType.QUICK_MESSAGE -> R.string.wp9_title_quick_message
        RecommendType.MCP -> R.string.wp9_title_mcp
    }
)

@Composable
fun recommendTypeShortName(type: RecommendType): String = stringResource(
    when (type) {
        RecommendType.SKILL -> R.string.wp9_type_skill
        RecommendType.PROMPT -> R.string.wp9_type_prompt
        RecommendType.QUICK_MESSAGE -> R.string.wp9_type_quick_message
        RecommendType.MCP -> R.string.wp9_type_mcp
    }
)

fun recommendTypeIcon(type: RecommendType): ImageVector = when (type) {
    RecommendType.SKILL -> HugeIcons.Puzzle
    RecommendType.PROMPT -> HugeIcons.MagicWand01
    RecommendType.QUICK_MESSAGE -> HugeIcons.Zap
    RecommendType.MCP -> HugeIcons.McpServer
}

@Composable
fun installKindLabel(kind: InstallKind): String = stringResource(
    when (kind) {
        InstallKind.NPX -> R.string.wp9_runtime_npx
        InstallKind.UVX -> R.string.wp9_runtime_uvx
        InstallKind.DOCKER -> R.string.wp9_runtime_docker
        InstallKind.REMOTE -> R.string.wp9_runtime_remote
        InstallKind.INLINE -> R.string.wp9_runtime_inline
    }
)

@Composable
fun recommendSortLabel(sort: RecommendSort): String = stringResource(
    when (sort) {
        RecommendSort.DEFAULT -> R.string.wp9_sort_default
        RecommendSort.NAME_ASC -> R.string.wp9_sort_name_asc
        RecommendSort.NAME_DESC -> R.string.wp9_sort_name_desc
        RecommendSort.AUTHOR -> R.string.wp9_sort_author
        RecommendSort.RUNTIME -> R.string.wp9_sort_runtime
    }
)

fun formatTimestamp(millis: Long): String =
    runCatching { Instant.ofEpochMilli(millis).toLocalDateTime() }.getOrDefault("-")

/** 源的显示名：本地内置源没存名字，按界面语言翻译。 */
@Composable
fun recommendSourceName(source: RecommendSource): String = when {
    source.name.isNotBlank() -> source.name
    source.local || source.id == RecommendSourceRules.LOCAL_SOURCE_ID ->
        stringResource(R.string.wp9_sources_local_name)

    else -> source.id
}

/** 条目的来源显示名：优先按 originId 找源，找不到就退化到 originName / id。 */
@Composable
fun recommendOriginLabel(entry: RecommendEntry): String = when {
    entry.originId == RecommendSourceRules.LOCAL_SOURCE_ID -> stringResource(R.string.wp9_sources_local_name)
    entry.originName.isNotBlank() -> entry.originName
    entry.originId.isNotBlank() -> entry.originId
    else -> stringResource(R.string.wp9_sources_unknown)
}

/**
 * MCP 设置里对应的配置片段。
 *
 * 生成的 JSON 与设置页「导入」接受的格式一致（mcpServers / type / url / headers / oauth）。
 * 只对「地址直接可用」的条目返回片段；`source_url` 指向配置 payload 的条目
 * 需要安装时下载解析，不能把配置文件的地址当成 MCP 端点展示给用户。
 */
fun mcpConfigSnippet(entry: RecommendEntry): String {
    val url = entry.mcpDirectUrl
    if (url.isBlank()) return ""
    val server = buildJsonObject {
        put("type", entry.mcpTransport.wire)
        put("url", url)
        if (entry.mcpHeaders.isNotEmpty()) {
            put(
                "headers",
                buildJsonObject {
                    entry.mcpHeaders.forEach { (name, value) -> put(name, value) }
                }
            )
        }
        entry.mcpOauth?.takeIf { it.enabled }?.let { oauth ->
            put(
                "oauth",
                buildJsonObject {
                    put("enabled", true)
                    oauth.scope?.takeIf { it.isNotBlank() }?.let { put("scope", it) }
                }
            )
        }
    }
    val root = buildJsonObject {
        put(
            "mcpServers",
            buildJsonObject {
                // 名称会写进设置并拼进工具名，展示名里的空格/中文在这里先规范化
                put(sanitizeMcpServerName(entry.name, entry.id), server)
            }
        )
    }
    return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
}

/** 部署讲解里「装依赖」对应的命令，按安装方式套用各自的包管理器。 */
fun dependencyCommand(kind: InstallKind, dependency: String): String = when (kind) {
    InstallKind.NPX -> "npm install -g $dependency"
    InstallKind.UVX -> "uv tool install $dependency"
    InstallKind.DOCKER -> "docker pull $dependency"
    InstallKind.REMOTE, InstallKind.INLINE -> dependency
}

/**
 * 一段文本 + 复制按钮。
 *
 * 部署讲解、MCP 配置片段、命令清单都用它：既能整段选中，也能一键复制到终端里跑。
 * [monospace] 为 false 时用于普通讲解文本。
 */
@Composable
fun CopyableCodeBlock(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 240.dp,
    monospace: Boolean = true,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { context.writeClipboardText(text) }) {
                Icon(
                    imageVector = HugeIcons.Copy01,
                    contentDescription = stringResource(R.string.wp9_copy),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    style = if (monospace) {
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}
