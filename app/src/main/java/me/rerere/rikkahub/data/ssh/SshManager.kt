package me.rerere.rikkahub.data.ssh

import android.content.Context
import android.util.Base64
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.SshAuthType
import me.rerere.rikkahub.data.model.SshHost
import me.rerere.rikkahub.data.security.SshSecretStore

/** SSH 调用失败；code 供工具层/UI 区分“没信任主机”“连不上”“命令超时”等情况。 */
class SshException(
    val code: String,
    message: String,
    val hostKeyType: String? = null,
    val hostKeyFingerprint: String? = null,
) : Exception(message)

data class SshExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)

data class SshTestResult(
    val ok: Boolean,
    val message: String,
    val hostKeyType: String? = null,
    val hostKeyFingerprint: String? = null,
)

data class SshFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
)

/**
 * SSH 连接池与操作封装（参考 dsh-Go 的 SSH 主机模型；Air-Ko 只做远程主机，
 * 不托管远端进程）：
 *
 * - 密码/私钥从 [SshSecretStore] 取，明文不进入 Settings；
 * - 首次连接必须由用户在设置页确认指纹（TOFU），此后指纹不匹配直接拒绝；
 * - 连接按主机复用，空闲 5 分钟后下次使用自动重连；出错即失效，下一次重建；
 * - 所有阻塞调用都在 IO 线程，并对连接/命令设置超时。
 */
class SshManager(
    private val context: Context,
    private val secretStore: SshSecretStore,
) {
    private data class Holder(
        val session: Session,
        val configKey: String,
        var lastUsed: Long,
    )

    private val lock = Any()
    private val sessions = HashMap<String, Holder>()

    suspend fun testConnection(host: SshHost, secret: SshSecretStoreData): SshTestResult =
        withContext(Dispatchers.IO) {
            val repository = CapturingHostKeyRepository(pinnedFingerprint = null)
            try {
                val session = openSession(host, secret, repository)
                val key = repository.presentedKey
                val fingerprint = key?.let { fingerprintOf(it) }
                val type = key?.let { typeOf(it) }
                runCatching { session.disconnect() }
                SshTestResult(
                    ok = true,
                    message = "connected",
                    hostKeyType = type,
                    hostKeyFingerprint = fingerprint,
                )
            } catch (e: SshException) {
                SshTestResult(false, e.message ?: "SSH_FAILED")
            } catch (e: Exception) {
                val key = repository.presentedKey
                SshTestResult(
                    ok = false,
                    message = e.message ?: e.javaClass.simpleName,
                    hostKeyType = key?.let { typeOf(it) },
                    hostKeyFingerprint = key?.let { fingerprintOf(it) },
                )
            }
        }

    /** 建立并保持连接（配置了端口转发时会同时建立隧道），供模型提供商走 127.0.0.1 使用。 */
    suspend fun ensureTunnel(host: SshHost): Boolean = withContext(Dispatchers.IO) {
        acquire(host)
        true
    }

    suspend fun exec(host: SshHost, command: String, timeoutMs: Long = 30_000L): SshExecResult =
        withContext(Dispatchers.IO) {
            var channel: ChannelExec? = null
            try {
                val session = acquire(host)
                channel = session.openChannel("exec") as ChannelExec
                channel.setCommand(command)
                channel.setInputStream(null)
                val stdout = ByteArrayOutputStream()
                val stderr = ByteArrayOutputStream()
                channel.setOutputStream(stdout)
                channel.setErrStream(stderr)
                channel.connect(10_000)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (!channel.isClosed) {
                    if (System.currentTimeMillis() > deadline) {
                        runCatching { channel.disconnect() }
                        invalidate(host.id)
                        return@withContext SshExecResult(
                            exitCode = -1,
                            stdout = stdout.toString("UTF-8"),
                            stderr = stderr.toString("UTF-8"),
                            timedOut = true,
                        )
                    }
                    Thread.sleep(40)
                }
                SshExecResult(
                    exitCode = channel.exitStatus,
                    stdout = stdout.toString("UTF-8"),
                    stderr = stderr.toString("UTF-8"),
                )
            } catch (e: SshException) {
                throw e
            } catch (e: Exception) {
                invalidate(host.id)
                throw SshException("SSH_EXEC_FAILED", e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { channel?.disconnect() }
            }
        }

    suspend fun readText(host: SshHost, path: String, maxBytes: Long = 512 * 1024): String =
        withContext(Dispatchers.IO) {
            val sftp = openSftp(host)
            try {
                val attrs = runCatching { sftp.lstat(path) }.getOrNull()
                if (attrs != null && attrs.size > maxBytes) {
                    throw SshException(
                        "SSH_FILE_TOO_LARGE",
                        "remote file is ${attrs.size} bytes, limit is $maxBytes",
                    )
                }
                val bytes = sftp.get(path).use { input -> readLimited(input, maxBytes) }
                bytes.toString(Charsets.UTF_8)
            } catch (e: SftpException) {
                throw SshException("SSH_SFTP_FAILED", e.message ?: "sftp read failed")
            } finally {
                runCatching { sftp.disconnect() }
            }
        }

    suspend fun writeText(host: SshHost, path: String, content: String) = withContext(Dispatchers.IO) {
        val sftp = openSftp(host)
        try {
            sftp.put(ByteArrayInputStream(content.toByteArray()), path)
        } catch (e: SftpException) {
            throw SshException("SSH_SFTP_FAILED", e.message ?: "sftp write failed")
        } finally {
            runCatching { sftp.disconnect() }
        }
    }

    suspend fun listDir(host: SshHost, path: String): List<SshFileEntry> = withContext(Dispatchers.IO) {
        val sftp = openSftp(host)
        try {
            @Suppress("UNCHECKED_CAST")
            (sftp.ls(path) as List<ChannelSftp.LsEntry>)
                .asSequence()
                .filter { it.filename != "." && it.filename != ".." }
                .map { SshFileEntry(it.filename, it.attrs.isDir, it.attrs.size) }
                .sortedWith(compareByDescending<SshFileEntry> { it.isDirectory }.thenBy { it.name })
                .toList()
        } catch (e: SftpException) {
            throw SshException("SSH_SFTP_FAILED", e.message ?: "sftp list failed")
        } finally {
            runCatching { sftp.disconnect() }
        }
    }

    fun close() {
        synchronized(lock) {
            sessions.values.forEach { runCatching { it.session.disconnect() } }
            sessions.clear()
        }
    }

    private fun openSftp(host: SshHost): ChannelSftp {
        val session = acquire(host)
        return try {
            (session.openChannel("sftp") as ChannelSftp).apply { connect(10_000) }
        } catch (e: Exception) {
            invalidate(host.id)
            throw SshException("SSH_SFTP_FAILED", e.message ?: "cannot open sftp channel")
        }
    }

    private fun acquire(host: SshHost): Session = synchronized(lock) {
        // 防呆：调用方应先用 requireTrusted 检查；这里再挡一次，未固定指纹一律不连
        if (!host.trusted) {
            throw SshException(
                "SSH_HOST_NOT_TRUSTED",
                "host " + host.displayName + " has no pinned host key",
            )
        }
        val secret = secretStore.load(host.id)
            ?: throw SshException("SSH_SECRET_MISSING", "no saved password or private key for \"${host.displayName}\"")
        val configKey = configKeyOf(host)
        val existing = sessions[host.id]
        val now = System.currentTimeMillis()
        if (existing != null &&
            existing.configKey == configKey &&
            existing.session.isConnected &&
            (host.forwardingEnabled || now - existing.lastUsed <= IDLE_DISCONNECT_MS)
        ) {
            existing.lastUsed = now
            return@synchronized existing.session
        }
        existing?.let { runCatching { it.session.disconnect() } }
        sessions.remove(host.id)
        val repository = CapturingHostKeyRepository(pinnedFingerprint = host.hostKeyFingerprint)
        val session = try {
            openSession(host, secret, repository)
        } catch (e: Exception) {
            val key = repository.presentedKey
            if (key != null && host.hostKeyFingerprint.isNotBlank() &&
                fingerprintOf(key) != host.hostKeyFingerprint
            ) {
                throw SshException(
                    "SSH_HOST_KEY_CHANGED",
                    "host key changed for \"${host.displayName}\"",
                    typeOf(key),
                    fingerprintOf(key),
                )
            }
            throw e
        }
        if (host.forwardingEnabled) {
            try {
                session.setPortForwardingL(
                    host.forwardLocalPort,
                    "127.0.0.1",
                    host.forwardRemotePort,
                )
            } catch (e: Exception) {
                runCatching { session.disconnect() }
                throw SshException(
                    "SSH_FORWARD_FAILED",
                    "cannot forward 127.0.0.1:" + host.forwardLocalPort +
                        " to 127.0.0.1:" + host.forwardRemotePort + ": " + (e.message ?: e.javaClass.simpleName),
                )
            }
        }
        sessions[host.id] = Holder(session, configKey, now)
        session
    }

    private fun invalidate(hostId: String) {
        synchronized(lock) {
            sessions.remove(hostId)?.let { runCatching { it.session.disconnect() } }
        }
    }

    private fun openSession(
        host: SshHost,
        secret: SshSecretStoreData,
        repository: HostKeyRepository,
    ): Session {
        if (host.host.isBlank() || host.user.isBlank()) {
            throw SshException("SSH_BAD_CONFIG", "host and user are required")
        }
        val jsch = JSch()
        jsch.hostKeyRepository = repository
        var keyFile: File? = null
        try {
            if (host.auth == SshAuthType.PRIVATE_KEY) {
                if (secret.privateKeyPem.isBlank()) {
                    throw SshException("SSH_SECRET_MISSING", "private key is empty")
                }
                keyFile = File(context.cacheDir, "ssh_key_${host.id}.pem").apply {
                    writeText(secret.privateKeyPem)
                    setReadable(true, true)
                    setWritable(true, true)
                    setExecutable(false, false)
                }
                jsch.addIdentity(
                    keyFile.absolutePath,
                    secret.passphrase.takeIf { it.isNotBlank() },
                )
            }
            val session = jsch.getSession(host.user, host.host, host.port)
            if (host.auth == SshAuthType.PASSWORD) {
                if (secret.password.isBlank()) {
                    throw SshException("SSH_SECRET_MISSING", "password is empty")
                }
                session.setPassword(secret.password)
                session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
            } else {
                session.setConfig("PreferredAuthentications", "publickey,password")
            }
            session.setConfig("StrictHostKeyChecking", "yes")
            if (host.compression) {
                session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
                session.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
            }
            session.serverAliveInterval = 30_000
            session.serverAliveCountMax = 3
            session.connect(15_000)
            return session
        } finally {
            runCatching { keyFile?.delete() }
        }
    }

    private fun configKeyOf(host: SshHost): String =
        listOf(
            host.host,
            host.port,
            host.user,
            host.auth.name,
            host.compression,
            host.forwardLocalPort,
            host.forwardRemotePort,
        ).joinToString("|")

    private fun readLimited(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw SshException("SSH_FILE_TOO_LARGE", "remote file exceeds $maxBytes bytes")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private class CapturingHostKeyRepository(
        private val pinnedFingerprint: String?,
    ) : HostKeyRepository {
        var presentedKey: ByteArray? = null

        override fun check(host: String, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.NOT_INCLUDED
            presentedKey = key
            if (pinnedFingerprint.isNullOrBlank()) {
                // 测试连接时接受任意未知主机键，由 UI 把指纹展示给用户确认
                return HostKeyRepository.OK
            }
            return if (fingerprintOf(key) == pinnedFingerprint) {
                HostKeyRepository.OK
            } else {
                HostKeyRepository.CHANGED
            }
        }

        override fun add(hostkey: com.jcraft.jsch.HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "airko-ssh"
        override fun getHostKey(): Array<com.jcraft.jsch.HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<com.jcraft.jsch.HostKey> = emptyArray()
    }

    private companion object {
        private const val TAG = "SshManager"
        private const val IDLE_DISCONNECT_MS = 5 * 60 * 1000L
    }
}

/** 兼容旧调用：SecretStore 返回类型别名，避免把内部数据类暴露给 UI。 */
typealias SshSecretStoreData = me.rerere.rikkahub.data.model.SshSecret

private fun typeOf(key: ByteArray): String = runCatching {
    if (key.size < 5) return@runCatching "ssh-unknown"
    val length = ((key[0].toInt() and 0xff) shl 24) or
        ((key[1].toInt() and 0xff) shl 16) or
        ((key[2].toInt() and 0xff) shl 8) or
        (key[3].toInt() and 0xff)
    if (length <= 0 || 4 + length > key.size) return@runCatching "ssh-unknown"
    String(key, 4, length, Charsets.US_ASCII)
}.getOrDefault("ssh-unknown")

internal fun fingerprintOf(key: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key)
    val encoded = Base64.encodeToString(
        digest,
        Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
    )
    return "SHA256:$encoded"
}
