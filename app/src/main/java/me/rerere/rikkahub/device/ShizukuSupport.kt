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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        if (stdout.isNotBlank()) append("\n--- stdout ---\n").append(stdout.trimEnd())
        if (stderr.isNotBlank()) append("\n--- stderr ---\n").append(stderr.trimEnd())
    }
}

object ShizukuSupport {
    const val PERMISSION_REQUEST_CODE = 4319

    /** UserService 版本：服务类行为有变化时 +1，Shizuku 会重启旧实例。 */
    private const val SHELL_SERVICE_VERSION = 1
    private const val TAG = "ShizukuSupport"
    private const val WRITE_CHUNK_BYTES = 256 * 1024

    private var appContext: Context? = null
    private val lock = Any()
    private var bound = false
    private var shellService: IShizukuShellService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellService = IShizukuShellService.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
        }
    }

    /** 应用启动时调用一次（Application/RouteActivity 都行）。 */
    fun init(context: Context) {
        appContext = context.applicationContext
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
            runCatching {
                parseExecJson(service.exec(command, timeoutMs.coerceIn(1_000L, 300_000L).toInt()))
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
        if (!info().ready) return false
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
                        .version(SHELL_SERVICE_VERSION)
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
        while (System.currentTimeMillis() < deadline) {
            shellService?.let { return it }
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
        )
    }
}
