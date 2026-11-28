package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** SSH 登录方式。密钥本身不进 Settings，由 SshSecretStore 用 Android Keystore 加密保存。 */
@Serializable
enum class SshAuthType {
    @SerialName("password")
    PASSWORD,

    @SerialName("private_key")
    PRIVATE_KEY,
}

/**
 * 一台可被 AI 使用的远程主机（SSH）。字段对齐 dsh-Go 的 SshHostConfig：
 * 连接信息 + 认证方式 + 工作目录 + TOFU 固定的主机指纹。
 */
@Serializable
data class SshHost(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val auth: SshAuthType = SshAuthType.PASSWORD,
    // AI 执行命令时的默认工作目录；空表示登录后的 home
    val workingDirectory: String = "",
    // 首次连接由用户确认后固定；留空表示还未信任，AI 工具会拒绝连接
    val hostKeyType: String = "",
    val hostKeyFingerprint: String = "",
    // 服务器支持时开启 SSH 压缩
    val compression: Boolean = true,
    // 可选：SSH 本地端口转发。远端跑着只能从 127.0.0.1 访问的服务（例如本地 DeepSeek/vLLM/DSH）时，
    // 把 remote 端口映射到手机本地的 local 端口，模型提供商就能用 http://127.0.0.1:<local> 访问。
    val forwardLocalPort: Int = 0,
    val forwardRemotePort: Int = 0,
) {
    val displayName: String get() = name.ifBlank { address }

    val address: String
        get() = if (port == 22) "$user@$host" else "$user@$host:$port"

    val trusted: Boolean get() = hostKeyFingerprint.isNotBlank()

    val forwardingEnabled: Boolean
        get() = forwardLocalPort in 1..65535 && forwardRemotePort in 1..65535

    /** 连接配置变化后旧指纹不再可信（dsh-Go 同样会在换机后清空固定指纹）。 */
    fun withKeyPinned(type: String, fingerprint: String): SshHost =
        copy(hostKeyType = type, hostKeyFingerprint = fingerprint)

    fun withoutPinnedKey(): SshHost = copy(hostKeyType = "", hostKeyFingerprint = "")
}

/** 密码 / 私钥口令只保存在 Keystore 加密的存储里。 */
@Serializable
data class SshSecret(
    val password: String = "",
    val privateKeyPem: String = "",
    val passphrase: String = "",
) {
    val isConfigured: Boolean
        get() = password.isNotBlank() || privateKeyPem.isNotBlank()
}
