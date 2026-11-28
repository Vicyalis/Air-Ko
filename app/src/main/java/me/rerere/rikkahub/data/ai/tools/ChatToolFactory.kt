package me.rerere.rikkahub.data.ai.tools

import android.util.Log
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.isValidMcpServerName
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.ssh.SshManager
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.workspace.WorkspaceShellStatus

private const val TAG = "ChatToolFactory"

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val sshManager: SshManager,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        conversationId: String? = null,
    ): List<Tool> = buildList {
        val workspace = assistant.workspaceId?.toString()?.let { workspaceRepository.getById(it) }
        if (assistant.enableMemory) {
            addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
        }
        if (shouldUseExternalWebSearch(assistant, model)) {
            addAll(createSearchTools(settings))
        }
        addAll(
            localTools.getTools(
                options = assistant.localTools,
                autoApprove = assistant.localToolsAutoApprove,
                deviceAutoApprove = workspace?.allowAllTools == true,
                conversationId = conversationId,
            )
        )
        if (assistant.enableRecentChatsReference) {
            addAll(createConversationTools(conversationRepository, assistant.id))
        }
        addAll(
            createWorkspaceToolsIfReady(
                workspace = workspace,
                cwd = workspaceCwd,
                autoApprove = assistant.localToolsAutoApprove,
            )
        )
        // 远程 SSH 主机：配了才暴露工具，避免空工具占 prompt
        addAll(
            createSshTools(
                hosts = settings.sshHosts,
                sshManager = sshManager,
            )
        )
        if (assistant.enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                )
            )
        }

        val mcpTools = mcpManager.getAllAvailableTools(assistant)
        val invalidNames = mcpTools
            .map { it.second }
            .distinct()
            .filter { name -> !isValidMcpServerName(name) }
        if (invalidNames.isNotEmpty()) {
            throw InvalidMcpServerNamesException(invalidNames)
        }
        mcpTools.forEach { (serverId, serverName, tool) ->
            add(
                Tool(
                    name = "mcp__${serverName}__${tool.name}",
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = { tool.needsApproval },
                    execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                )
            )
        }
    }

    private suspend fun createWorkspaceToolsIfReady(
        workspace: me.rerere.rikkahub.data.db.entity.WorkspaceEntity?,
        cwd: String?,
        autoApprove: Boolean,
    ): List<Tool> {
        if (workspace == null) return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=${workspace.id}, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(
            workspaceId = workspace.id,
            workspaceRepository = workspaceRepository,
            cwd = cwd,
            autoApprove = autoApprove,
            allowAll = workspace.allowAllTools,
        )
    }
}
