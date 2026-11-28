package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * 把用户保存的 SSH 远程主机注入系统提示。
 *
 * 只在配置了主机时生效；列表来自 Settings（不含密码/私钥），未信任的主机也列出来，
 * 让模型知道它存在、但工具会拒绝连接，避免模型反复重试。
 */
class SshReminderTransformer(
    private val settingsStore: SettingsStore,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val hosts = settingsStore.settingsFlow.value.sshHosts
        val model = ctx.model
        if (hosts.isEmpty() || ModelAbility.TOOL !in model.abilities) return messages
        val prompt = buildString {
            appendLine("<ssh_hosts>")
            appendLine("The user saved these remote SSH hosts. Use ssh_list_hosts / ssh_exec / ssh_read_file / ssh_write_file / ssh_list_dir only when the task needs a remote machine.")
            appendLine("A host with trusted=false has no confirmed host key yet: do not retry it; tell the user to open Settings > Remote hosts and confirm its fingerprint.")
            hosts.forEach { host ->
                appendLine(
                    "- id=" + host.id +
                        " name=\"" + host.displayName + "\"" +
                        " address=" + host.address +
                        " trusted=" + host.trusted +
                        " working_dir=" + host.workingDirectory.ifBlank { "~" }
                )
            }
            append("</ssh_hosts>")
        }
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n" + prompt)
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + messages
        }
    }

    private fun UIMessage.appendText(extra: String): UIMessage {
        val updatedParts = parts.toMutableList()
        val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
        if (firstTextIndex >= 0) {
            val text = updatedParts[firstTextIndex] as UIMessagePart.Text
            updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
        } else {
            updatedParts.add(UIMessagePart.Text(extra))
        }
        return copy(parts = updatedParts)
    }
}
