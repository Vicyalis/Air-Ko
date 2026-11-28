package me.rerere.rikkahub.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.SshSecret

/**
 * SSH 密码/私钥的本地加密存储。
 *
 * - 密文放 SharedPreferences（App 私有目录），明文密钥留在 Android Keystore，不落盘；
 * - 每个主机一个条目，删除主机时同步删除；
 * - Keystore 不可用（极旧 ROM/模拟器）时只记日志并返回 null，调用方按“没有凭据”处理。
 */
class SshSecretStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun save(hostId: String, secret: SshSecret) {
        runCatching {
            val key = keystoreKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val plain = json.encodeToString(SshSecret.serializer(), secret).toByteArray()
            val encrypted = cipher.doFinal(plain)
            val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit().putString(hostId, payload).apply()
        }.onFailure { Log.w(TAG, "save ssh secret failed", it) }
    }

    fun load(hostId: String): SshSecret? {
        val payload = prefs.getString(hostId, null) ?: return null
        return runCatching {
            val parts = payload.split(":", limit = 2)
            if (parts.size != 2) return@runCatching null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(encrypted)
            json.decodeFromString(SshSecret.serializer(), plain.toString(Charsets.UTF_8))
        }.onFailure { Log.w(TAG, "load ssh secret failed", it) }.getOrNull()
    }

    fun delete(hostId: String) {
        runCatching { prefs.edit().remove(hostId).apply() }
    }

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        private const val TAG = "SshSecretStore"
        private const val PREFS_NAME = "airko_ssh_secrets"
        private const val KEY_ALIAS = "airko_ssh_secret_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
