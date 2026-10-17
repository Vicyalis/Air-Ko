/*
 * 设备控制·运行期开关（SharedPreferences）。
 *
 * 口径对齐 Reticle 的 settings prefs：看门狗与开机自启是「设备本地」的开关（仿系统的无障碍
 * 授权状态），不进 DataStore 的 Settings（避免设置导入导出把「本机才有意义」的开关带去别的设备）。
 */
package me.rerere.rikkahub.device

import android.content.Context
import me.rerere.rikkahub.utils.JsonInstant

object DeviceControlPrefs {
    private const val PREFS = "device_control_prefs"

    /** 看门狗总开关，默认开（同 Reticle；关掉＝本应用不再写任何系统设置）。 */
    const val KEY_WATCHDOG = "a11y_watchdog"

    /** 开机自启，默认关（同 Reticle；选项旁边要写清它对系统权限的依赖）。 */
    const val KEY_BOOT = "boot_autostart"

    /** 用户从最近任务划掉过 App ＝「明示停手」，开机/自启都不覆盖，直到手动打开 App。 */
    const val KEY_USER_STOPPED = "user_stopped"

    /** 看门狗曾经成功恢复过无障碍（次数与最近时间；状态页会显示）。 */
    const val KEY_FIXES = "a11y_watchdog_fixes"
    const val KEY_LAST_FIX_AT = "a11y_watchdog_last_fix_at"

    /** 是否曾经授权过（连上过服务）；没授权过就不自动写无障碍名单（不替用户做决定）。 */
    const val KEY_EVER_AUTHORIZED = "ever_authorized"

    /** 最近一次检查的时间与结论（状态页显示用）。 */
    const val KEY_LAST_CHECK_AT = "a11y_watchdog_last_check_at"
    const val KEY_LAST_NOTE = "a11y_watchdog_last_note"

    /** 守护检查间隔（毫秒）。 */
    const val KEY_WATCHDOG_INTERVAL_MS = "a11y_watchdog_interval_ms"

    /** 审计日志保留天数。 */
    const val KEY_AUDIT_RETENTION_DAYS = "audit_retention_days"

    /** 下载是否只用应用自己的目录。 */
    const val KEY_DOWNLOAD_APP_DIR_ONLY = "download_app_dir_only"

    /** 每个工具「执行前询问」的用户覆盖（JSON: toolName -> Boolean）。 */
    const val KEY_TOOL_APPROVALS = "tool_approvals"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun watchdogEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_WATCHDOG, true)

    fun setWatchdogEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_WATCHDOG, value).apply()
    }

    fun bootAutoStartEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BOOT, false)

    fun setBootAutoStartEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_BOOT, value).apply()
    }

    fun userStopped(context: Context): Boolean = prefs(context).getBoolean(KEY_USER_STOPPED, false)

    fun setUserStopped(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_USER_STOPPED, value).apply()
    }

    fun everAuthorized(context: Context): Boolean = prefs(context).getBoolean(KEY_EVER_AUTHORIZED, false)

    fun markEverAuthorized(context: Context) {
        if (!everAuthorized(context)) {
            prefs(context).edit().putBoolean(KEY_EVER_AUTHORIZED, true).apply()
        }
    }

    @Synchronized
    fun bumpWatchdogFixes(context: Context): Int {
        val next = prefs(context).getInt(KEY_FIXES, 0) + 1
        prefs(context).edit()
            .putInt(KEY_FIXES, next)
            .putLong(KEY_LAST_FIX_AT, System.currentTimeMillis())
            .apply()
        return next
    }

    fun watchdogFixes(context: Context): Int = prefs(context).getInt(KEY_FIXES, 0)

    fun lastFixAt(context: Context): Long = prefs(context).getLong(KEY_LAST_FIX_AT, 0L)

    @Synchronized
    fun resetWatchdogFixes(context: Context) {
        prefs(context).edit().putInt(KEY_FIXES, 0).putLong(KEY_LAST_FIX_AT, 0L).apply()
    }

    fun lastCheckAt(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK_AT, 0L)

    fun setLastCheckAt(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_LAST_CHECK_AT, value).apply()
    }

    fun lastNote(context: Context): String? = prefs(context).getString(KEY_LAST_NOTE, null)

    fun setLastNote(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST_NOTE, value).apply()
    }

    // -----------------------------------------------------------------------------------------
    // WP16-B：用户可调的选项（守护间隔 / 审计保留 / 下载目录 / 每个工具的执行前询问）
    // -----------------------------------------------------------------------------------------

    /** 守护检查间隔（毫秒），默认 10 秒。 */
    fun watchdogIntervalMs(context: Context): Long =
        prefs(context).getLong(KEY_WATCHDOG_INTERVAL_MS, 10_000L).coerceIn(5_000L, 120_000L)

    fun setWatchdogIntervalMs(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_WATCHDOG_INTERVAL_MS, value.coerceIn(5_000L, 120_000L)).apply()
    }

    /** 审计日志保留天数，默认 14 天。 */
    fun auditRetentionDays(context: Context): Int =
        prefs(context).getInt(KEY_AUDIT_RETENTION_DAYS, 14).coerceIn(1, 365)

    fun setAuditRetentionDays(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_AUDIT_RETENTION_DAYS, value.coerceIn(1, 365)).apply()
    }

    /** 下载目录：true＝固定用应用自己的下载目录；false＝有「所有文件访问」时优先系统下载目录。 */
    fun downloadToAppDirOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DOWNLOAD_APP_DIR_ONLY, false)

    fun setDownloadToAppDirOnly(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DOWNLOAD_APP_DIR_ONLY, value).apply()
    }

    /** 每个工具「执行前询问」的用户覆盖（缺省＝用工具自己的默认值）。 */
    fun toolApprovalOverrides(context: Context): Map<String, Boolean> {
        val raw = prefs(context).getString(KEY_TOOL_APPROVALS, null) ?: return emptyMap()
        return runCatching {
            JsonInstant.decodeFromString<Map<String, Boolean>>(raw)
        }.getOrDefault(emptyMap())
    }

    fun setToolApprovalOverride(context: Context, toolName: String, needsApproval: Boolean?) {
        val current = toolApprovalOverrides(context).toMutableMap()
        if (needsApproval == null) {
            current.remove(toolName)
        } else {
            current[toolName] = needsApproval
        }
        val raw = runCatching { JsonInstant.encodeToString(current) }.getOrNull() ?: "{}"
        prefs(context).edit().putString(KEY_TOOL_APPROVALS, raw).apply()
    }
}
