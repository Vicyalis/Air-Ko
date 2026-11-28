/*
 * Shizuku 支持层：把命令交给 shell(ADB)/root 身份执行。
 *
 * 与无障碍能力（device 包内的设备控制实现）互补：无障碍负责「看屏幕、点屏幕」，Shizuku 负责
 * 无障碍做不到的事——安装/卸载应用、改系统设置、强制停止、input 兜底、系统级截屏等。
 *
 * Shizuku 13 起 `Shizuku.newProcess` 已不可用，因此命令通过 UserService（ShizukuShellService）
 * 执行：Shizuku 把服务跑在自己的进程里，`Runtime.exec` 天然带 shell/root 权限。
 *
 * Shizuku 需要用户先安装 Shizuku（或 Sui，root 设备），并在应用内授权；没有授权时所有调用
 * 一律返回可读错误，绝不静默降级成别的行为。
 */
package me.rerere.rikkahub.device

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.BuildConfig
import rikka.shizuku.Shizuku

/** Shizuku 可用状态。 */
enum class ShizukuState {
    /** 没装 Shizuku / 装了但服务没在跑（Shizuku 每次重启后需要在 Shizuku 应用里重新启动）。 */
    NotRunning,

    /** 服务在跑，但本应用还没拿到授权。 */
    PermissionNeeded,

    /** 可以执行命令。 */
    Ready,

    /** 版本过旧（pre-v11），不支持。 */
    Unsupported,
}

data class ShizukuInfo(
    val state: ShizukuState,
    val version: Int = -1,
    val uid: Int = -1,
    val isRoot: Boolean = false,
) {
    val ready: Boolean get() = state == ShizukuState.Ready
}

data class ShizukuExecResult(
    val ok: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val error: String? = null,
) {
    /** 给模型看的单段文本（截断由调用方处理）。 */
    fun describe(): String = buildString {
        if (error != null) {
            append("error=").append(error)
            return@buildString
        }
        append("exit=").append(exitCode)
        if (timedOut) append(" (timed out)")
        if (truncated) append(" (output truncated)")
        if (stdout.isNotBlank()) append("\n--- stdout ---\n").append(stdout.trimEnd())
        if (stderr.isNotBlank()) append("\n--- stderr ---\n").append(stderr.trimEnd())
    }
}

object ShizukuSupport {
    const val PERMISSION_REQUEST_CODE = 4319

    private const val TAG = "ShizukuSupport"
    private const val WRITE_CHUNK_BYTES = 256 * 1024
    private const val READ_CHUNK_BYTES = 256 * 1024
    private const val MAX_REMOTE_READ_BYTES = 32L * 1024 * 1024

    private var appContext: Context? = null
    private val lock = Any()
    private var bound = false
    private var shellService: IShizukuShellService? = null
    private var deadListenerAdded = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellService = IShizukuShellService.Stub.asInterface(service)
            // 连接后确认服务进程身份：2000 是 shell(ADB)，0 是 root；只打日志，不阻断
            runCatching { Log.i(TAG, "shell service connected, uid=" + shellService?.uid) }
                .onFailure { Log.w(TAG, "echo uid failed", it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 必须把 bound 复位，否则下次 awaitService 不会重新 bind，整个进程内永久不可用
            synchronized(lock) {
                shellService = null
                bound = false
            }
        }
    }

    /** 应用启动时调用一次（Application/RouteActivity 都行）。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (!deadListenerAdded) {
            deadListenerAdded = true
            runCatching {
                Shizuku.addBinderDeadListener(object : Shizuku.OnBinderDeadListener {
                    override fun onBinderDead() {
                        // Shizuku 服务被停止/更新后，旧 UserService 已经不可用
                        synchronized(lock) {
                            shellService = null
                            bound = false
                        }
                        Log.i(TAG, "Shizuku binder died, will rebind on next call")
                    }
                })
            }.onFailure { Log.w(TAG, "addBinderDeadListener failed", it) }
        }
    }

    fun info(): ShizukuInfo {
        return runCatching {
            if (!Shizuku.pingBinder()) return ShizukuInfo(ShizukuState.NotRunning)
            val version = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
            if (Shizuku.isPreV11() || version in 0 until 11) {
                return ShizukuInfo(ShizukuState.Unsupported, version = version)
            }
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
            ShizukuInfo(
                state = if (granted) ShizukuState.Ready else ShizukuState.PermissionNeeded,
                version = version,
                uid = uid,
                isRoot = uid == 0,
            )
        }.getOrElse { error ->
            Log.w(TAG, "info failed", error)
            ShizukuInfo(ShizukuState.NotRunning)
        }
    }

    /** 请求授权（需要 Shizuku 服务在跑；失败只记日志，不抛）。 */
    fun requestPermission() {
        runCatching {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) return
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }.onFailure { Log.w(TAG, "requestPermission failed", it) }
    }

    fun addPermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
            .onFailure { Log.w(TAG, "addPermissionListener failed", it) }
    }

    fun removePermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
            .onFailure { Log.w(TAG, "removePermissionListener failed", it) }
    }

    /** 执行一条 shell 命令（在 Shizuku 进程里 `sh -c`）。 */
    suspend fun exec(command: String, timeoutMs: Long = 30_000L): ShizukuExecResult =
        withContext(Dispatchers.IO) {
            val current = info()
            if (!current.ready) {
                return@withContext ShizukuExecResult(
                    ok = false,
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    error = stateError(current.state),
                )
            }
            val service = awaitService()
                ?: return@withContext ShizukuExecResult(false, -1, "", "", error = "SHIZUKU_SERVICE_UNAVAILABLE")
            // binder 调用本身是同步的，超时后线程可能仍被占着；这里至少让调用方
            // 不再无限等待，能拿到一个明确的超时结果。
            val raw = withTimeoutOrNull(timeoutMs + 5_000L) {
                service.exec(command, timeoutMs.coerceIn(1_000L, 300_000L).toInt())
            } ?: return@withContext ShizukuExecResult(
                ok = false,
                exitCode = -1,
                stdout = "",
                stderr = "",
                timedOut = true,
                error = "SHIZUKU_TIMEOUT",
            )
            runCatching {
                parseExecJson(raw)
            }.getOrElse { error ->
                Log.w(TAG, "exec failed: $command", error)
                ShizukuExecResult(false, -1, "", "", error = error.message ?: "SHIZUKU_EXEC_FAILED")
            }
        }

    /** 把字节分块写进 shell/root 可写的位置（例如 /data/local/tmp）。 */
    suspend fun writeRemoteFile(path: String, bytes: ByteArray): ShizukuExecResult =
        withContext(Dispatchers.IO) {
            val service = awaitService()
                ?: return@withContext ShizukuExecResult(false, -1, "", "", error = "SHIZUKU_SERVICE_UNAVAILABLE")
            runCatching {
                var offset = 0
                var append = false
                while (offset < bytes.size) {
                    val end = minOf(offset + WRITE_CHUNK_BYTES, bytes.size)
                    val chunk = bytes.copyOfRange(offset, end)
                    if (!service.writeFile(path, chunk, append)) {
                        return@runCatching ShizukuExecResult(false, -1, "", "", error = "SHIZUKU_WRITE_FAILED")
                    }
                    append = true
                    offset = end
                }
                ShizukuExecResult(true, 0, "wrote ${bytes.size} bytes to $path", "")
            }.getOrElse { error ->
                Log.w(TAG, "writeRemoteFile failed: $path", error)
                ShizukuExecResult(false, -1, "", "", error = error.message ?: "SHIZUKU_WRITE_FAILED")
            }
        }

    /** 分块读回远端文件（截图等大文件不能走 exec 的 stdout）。 */
    suspend fun readRemoteFile(path: String, maxBytes: Long = MAX_REMOTE_READ_BYTES): ByteArray? =
        withContext(Dispatchers.IO) {
            val service = awaitService() ?: return@withContext null
            runCatching {
                val size = service.fileSize(path)
                if (size <= 0L || size > maxBytes) return@runCatching null
                val output = java.io.ByteArrayOutputStream(size.toInt())
                var offset = 0L
                while (offset < size) {
                    val length = minOf(READ_CHUNK_BYTES.toLong(), size - offset).toInt()
                    val chunk = service.readFile(path, offset, length)
                    if (chunk.isEmpty()) break
                    output.write(chunk)
                    offset += chunk.size
                }
                output.toByteArray()
            }.getOrNull()
        }

    /** 流式写入远端文件：安装大 APK 时不用把整个文件读进内存。 */
    suspend fun writeRemoteFile(path: String, input: InputStream): ShizukuExecResult =
        withContext(Dispatchers.IO) {
            val service = awaitService()
                ?: return@withContext ShizukuExecResult(false, -1, "", "", error = "SHIZUKU_SERVICE_UNAVAILABLE")
            runCatching {
                input.use { stream ->
                    val buffer = ByteArray(WRITE_CHUNK_BYTES)
                    var append = false
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                        if (!service.writeFile(path, chunk, append)) {
                            return@runCatching ShizukuExecResult(
                                false, -1, "", "", error = "SHIZUKU_WRITE_FAILED",
                            )
                        }
                        append = true
                    }
                }
                ShizukuExecResult(true, 0, "wrote stream to $path", "")
            }.getOrElse { error ->
                Log.w(TAG, "writeRemoteFile(stream) failed: $path", error)
                ShizukuExecResult(false, -1, "", "", error = error.message ?: "SHIZUKU_WRITE_FAILED")
            }
        }

    /** 删除 shell/root 身份下的文件。 */
    suspend fun deleteRemoteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        val service = awaitService() ?: return@withContext false
        runCatching { service.deleteFile(path) }.getOrDefault(false)
    }

    private fun stateError(state: ShizukuState): String = when (state) {
        ShizukuState.NotRunning -> "SHIZUKU_NOT_RUNNING"
        ShizukuState.PermissionNeeded -> "SHIZUKU_PERMISSION_NEEDED"
        ShizukuState.Unsupported -> "SHIZUKU_UNSUPPORTED"
        ShizukuState.Ready -> "SHIZUKU_ERROR"
    }

    /** 绑定 UserService（幂等）；返回 true 表示已经连上。 */
    private fun ensureBound(): Boolean {
        val context = appContext ?: return false
        synchronized(lock) {
            if (shellService != null) return true
            if (!bound) {
                runCatching {
                    val args = Shizuku.UserServiceArgs(
                        ComponentName(context.packageName, ShizukuShellService::class.java.name),
                    )
                        .daemon(false)
                        .processNameSuffix("shizuku_shell")
                        .debuggable(false)
                        // 跟随 APK versionCode：升级后强制换新实例，避免旧服务代码残留
                        .version(BuildConfig.VERSION_CODE.toIntOrNull() ?: 1)
                    Shizuku.bindUserService(args, connection)
                    bound = true
                }.onFailure { Log.w(TAG, "bindUserService failed", it) }
            }
        }
        return false
    }

    /** 等 UserService 连上（首次绑定要几十毫秒到几百毫秒）。 */
    private suspend fun awaitService(timeoutMs: Long = 4_000L): IShizukuShellService? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastPermissionCheck = 0L
        while (System.currentTimeMillis() < deadline) {
            shellService?.let { return it }
            // 权限/服务状态是 4 次 binder 调用，不必每 50ms 查一遍
            val now = System.currentTimeMillis()
            if (now - lastPermissionCheck >= 250L) {
                if (!info().ready) return null
                lastPermissionCheck = now
            }
            ensureBound()
            delay(50L)
        }
        return shellService
    }

    private fun parseExecJson(raw: String): ShizukuExecResult {
        val obj = Json.parseToJsonElement(raw).jsonObject
        return ShizukuExecResult(
            ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
            exitCode = obj["exit"]?.jsonPrimitive?.intOrNull ?: -1,
            stdout = obj["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            stderr = obj["stderr"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            timedOut = obj["timedOut"]?.jsonPrimitive?.booleanOrNull ?: false,
            truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }
}
