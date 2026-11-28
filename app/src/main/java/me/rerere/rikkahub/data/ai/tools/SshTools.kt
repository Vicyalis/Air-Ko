package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.SshHost
import me.rerere.rikkahub.data.ssh.SshException
import me.rerere.rikkahub.data.ssh.SshExecResult
import me.rerere.rikkahub.data.ssh.SshManager
import me.rerere.rikkahub.utils.JsonInstantPretty

private const val MAX_SSH_OUTPUT_CHARS = 16_000

/**
 * SSH 远程主机工具（参考 dsh-Go 的远程主机模型）。
 *
 * 只操作用户在“设置 › 远程主机”里保存并由用户确认过主机指纹的机器：
 * 未信任的主机会直接返回 SSH_HOST_NOT_TRUSTED，而不是偷偷接受新指纹。
 */
fun createSshTools(
    hosts: List<SshHost>,
    sshManager: SshManager,
): List<Tool> {
    if (hosts.isEmpty()) return emptyList()

    fun resolveHost(idOrName: String): SshHost {
        val value = idOrName.trim()
        return hosts.firstOrNull { it.id == value }
            ?: hosts.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: hosts.firstOrNull { it.address.equals(value, ignoreCase = true) }
            ?: throw SshException("SSH_HOST_NOT_FOUND", "no saved SSH host named \"$value\"")
    }

    fun requireTrusted(host: SshHost) {
        if (!host.trusted) {
            throw SshException(
                "SSH_HOST_NOT_TRUSTED",
                "host \"${host.displayName}\" has no pinned host key; open Settings > Remote hosts and test/trust it first",
            )
        }
    }

    // SSH 命令/写入默认一律需要用户确认：远程机器没有沙箱，且这个开关不该被
    // “设备控制免确认”间接打开。
    fun approval(requiredByDefault: Boolean): (JsonElement) -> Boolean =
        { requiredByDefault }

    fun hostIdSchema() = buildJsonObject {
        put("type", "string")
        put("description", "Host id, name or user@host from ssh_list_hosts")
    }

    return listOf(
        Tool(
            name = "ssh_list_hosts",
            description = """
                List the remote SSH hosts saved by the user. Each item shows id, name, address,
                whether its host key is trusted, and the default working directory.
                Untrusted hosts cannot be used until the user confirms their fingerprint in Settings.
            """.trimIndent().replace("\n", " "),
            parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
            needsApproval = { false },
            execute = {
                listOf(
                    UIMessagePart.Text(
                        JsonInstantPretty.encodeToString(
                            kotlinx.serialization.json.JsonArray.serializer(),
                            buildJsonArray {
                                hosts.forEach { host ->
                                    add(buildJsonObject {
                                        put("id", host.id)
                                        put("name", host.displayName)
                                        put("address", host.address)
                                        put("trusted", host.trusted)
                                        put("working_directory", host.workingDirectory)
                                    })
                                }
                            },
                        )
                    )
                )
            },
        ),
        Tool(
            name = "ssh_exec",
            description = """
                Run a shell command on a saved remote SSH host. Use ssh_list_hosts first.
                The host must be trusted (host key pinned by the user). Commands run over SSH as
                the configured user; there is no sandbox on the remote machine, so keep commands
                scoped to what the user asked for. Output is truncated to about 16000 characters.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("host", hostIdSchema())
                        put("command", buildJsonObject {
                            put("type", "string")
                            put("description", "Shell command to run on the remote host")
                        })
                        put("cwd", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional working directory; defaults to the host setting")
                        })
                    },
                    required = listOf("host", "command"),
                )
            },
            needsApproval = approval(requiredByDefault = true),
            execute = { args ->
                val params = args.jsonObject
                val host = resolveHost(params["host"]?.jsonPrimitive?.contentOrNull.orEmpty())
                requireTrusted(host)
                val command = params["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
                require(command.isNotBlank()) { "command is required" }
                val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    .ifBlank { host.workingDirectory }
                val result = sshManager.exec(
                    host = host,
                    command = if (cwd.isBlank()) command else "cd ${shellQuote(cwd)} && $command",
                )
                listOf(UIMessagePart.Text(formatExecResult(result)))
            },
        ),
        Tool(
            name = "ssh_read_file",
            description = """
                Read a text file from a saved SSH host over SFTP. Maximum 512 KiB.
                Use absolute paths, or paths relative to the host working directory.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("host", hostIdSchema())
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Remote file path")
                        })
                    },
                    required = listOf("host", "path"),
                )
            },
            needsApproval = { false },
            execute = { args ->
                val params = args.jsonObject
                val host = resolveHost(params["host"]?.jsonPrimitive?.contentOrNull.orEmpty())
                requireTrusted(host)
                val path = params["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                require(path.isNotBlank()) { "path is required" }
                val content = sshManager.readText(host, resolvePath(host, path))
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", true)
                            put("host", host.displayName)
                            put("path", path)
                            put("content", content)
                        }.toString()
                    )
                )
            },
        ),
        Tool(
            name = "ssh_write_file",
            description = """
                Write a text file on a saved SSH host over SFTP. Overwrites the file if it exists;
                parent directories are not created. Use absolute paths or paths relative to the
                host working directory.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("host", hostIdSchema())
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Remote file path")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "Full file content")
                        })
                    },
                    required = listOf("host", "path", "content"),
                )
            },
            needsApproval = approval(requiredByDefault = true),
            execute = { args ->
                val params = args.jsonObject
                val host = resolveHost(params["host"]?.jsonPrimitive?.contentOrNull.orEmpty())
                requireTrusted(host)
                val path = params["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                require(path.isNotBlank()) { "path is required" }
                val content = params["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                sshManager.writeText(host, resolvePath(host, path), content)
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", true)
                            put("host", host.displayName)
                            put("path", path)
                            put("bytes", content.toByteArray().size)
                        }.toString()
                    )
                )
            },
        ),
        Tool(
            name = "ssh_list_dir",
            description = """
                List a directory on a saved SSH host over SFTP. Returns name, type (file/directory)
                and size. Use absolute paths or paths relative to the host working directory.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("host", hostIdSchema())
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Remote directory; defaults to the host working directory or home")
                        })
                    },
                    required = listOf("host"),
                )
            },
            needsApproval = { false },
            execute = { args ->
                val params = args.jsonObject
                val host = resolveHost(params["host"]?.jsonPrimitive?.contentOrNull.orEmpty())
                requireTrusted(host)
                val path = params["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    .ifBlank { host.workingDirectory.ifBlank { "." } }
                val entries = sshManager.listDir(host, resolvePath(host, path))
                listOf(
                    UIMessagePart.Text(
                        JsonInstantPretty.encodeToString(
                            kotlinx.serialization.json.JsonArray.serializer(),
                            buildJsonArray {
                                entries.forEach { entry ->
                                    add(buildJsonObject {
                                        put("name", entry.name)
                                        put("type", if (entry.isDirectory) "directory" else "file")
                                        put("size", entry.size)
                                    })
                                }
                            },
                        )
                    )
                )
            },
        ),
    )
}

private fun resolvePath(host: SshHost, path: String): String {
    val trimmed = path.trim()
    if (trimmed.startsWith("/") || trimmed.startsWith("~") || host.workingDirectory.isBlank()) return trimmed
    return host.workingDirectory.trimEnd('/') + "/" + trimmed
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun formatExecResult(result: SshExecResult): String = buildString {
    if (result.timedOut) appendLine("[timed out]")
    appendLine("exit=" + result.exitCode)
    if (result.stdout.isNotBlank()) {
        appendLine("--- stdout ---")
        appendLine(result.stdout.trimEnd())
    }
    if (result.stderr.isNotBlank()) {
        appendLine("--- stderr ---")
        appendLine(result.stderr.trimEnd())
    }
}.take(MAX_SSH_OUTPUT_CHARS)
