/*
 * 工作区桌面·运行期偏好（SharedPreferences）。
 *
 * 与设备控制一样，这些是「本机才有意义」的旋钮，不进 DataStore：
 * - 下载加速前缀：国内访问 GitHub 慢/不通时，把下载地址换成镜像前缀（如 https://gh-proxy.com/）
 * - code-server 版本：api.github.com 不通时直接指定版本，跳过版本解析
 */
package me.rerere.rikkahub.device

import android.content.Context

object WorkspaceDesktopPrefs {
    private const val PREFS = "workspace_desktop_prefs"

    private const val KEY_DOWNLOAD_MIRROR = "desktop_download_mirror"
    private const val KEY_CODE_SERVER_VERSION = "code_server_version"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 下载加速前缀，例如 `https://gh-proxy.com/`；空＝直连。 */
    fun downloadMirror(context: Context): String =
        prefs(context).getString(KEY_DOWNLOAD_MIRROR, "")?.trim().orEmpty()

    fun setDownloadMirror(context: Context, value: String) {
        prefs(context).edit().putString(KEY_DOWNLOAD_MIRROR, value.trim()).apply()
    }

    /** 指定 code-server 版本（空＝先解析 latest，失败再退回内置版本）。 */
    fun codeServerVersion(context: Context): String =
        prefs(context).getString(KEY_CODE_SERVER_VERSION, "")?.trim().orEmpty()

    fun setCodeServerVersion(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CODE_SERVER_VERSION, value.trim()).apply()
    }
}
