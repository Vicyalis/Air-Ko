package me.rerere.workspace

import java.io.File
import java.net.URI

/**
 * 工作区桌面方案。
 *
 * [id] 直接落进 Room 的 `workspaces.desktop` 列, 是稳定标识, 不要改名。
 */
enum class WorkspaceDesktop(val id: String) {
    NONE("none"),
    CODE_SERVER("code-server"),
    NOVNC("novnc"),
    JUPYTER("jupyter"),
    GNOME("gnome"),
    NIRI("niri"),
    CUSTOM("custom"),
    ;

    companion object {
        /** 反序列化: 数据库里可能是旧值或脏数据, 一律退回 [NONE] */
        fun fromId(id: String?): WorkspaceDesktop = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * rootfs 内可用的包管理器, 决定安装脚本用哪套命令。
 *
 * 不看发行版名字: 用户可能自己粘贴任意 rootfs 地址, 只能按真实二进制探测。
 */
enum class WorkspacePackageManager {
    APK,
    APT,
    PACMAN,
    DNF,
    UNKNOWN,
    ;

    companion object {
        /** 按 [RootfsDistroCatalog] 里已收录的发行版 id 映射 (列表页/测试用) */
        fun fromDistro(distroId: String?): WorkspacePackageManager = when (distroId) {
            RootfsDistroCatalog.ID_ALPINE -> APK
            RootfsDistroCatalog.ID_UBUNTU, RootfsDistroCatalog.ID_DEBIAN -> APT
            RootfsDistroCatalog.ID_ARCH -> PACMAN
            RootfsDistroCatalog.ID_FEDORA -> DNF
            else -> UNKNOWN
        }
    }
}

/**
 * 桌面方案在「已装 rootfs 的工作区」上的完整执行计划。
 *
 * 所有脚本都在 rootfs 内 (proot) 执行 —— App 自身不下载任何二进制, 这是产品硬约束。
 *
 * @param installScript 安装脚本; null 表示该方案没有安装动作 (不使用 / 自定义)
 * @param probeScript 安装结果探测脚本, 退出码 0 才算装好; null 表示没有可探测的安装结果
 * @param startScript 启动脚本, 内部自己 nohup 并重定向到 [WorkspaceDesktopCatalog.LOG_PATH]
 * @param stopScript 停止脚本, 只负责发信号; 是否真的停了由 App 侧端口探测确认
 * @param port 监听端口; 0 表示未知 (自定义方案没填端口)
 * @param url App 内打开桌面用的地址; 端口未知时为 null
 * @param sizeHintBytes 下载体积估计, 用于让用户在选择方案时就看到代价
 * @param installMinutes 安装耗时估计 (分钟)
 */
data class WorkspaceDesktopPlan(
    val desktop: WorkspaceDesktop,
    val packageManager: WorkspacePackageManager,
    val installScript: String?,
    val probeScript: String?,
    val startScript: String?,
    val stopScript: String?,
    val port: Int,
    val url: String?,
    val sizeHintBytes: Long?,
    val installMinutes: Int?,
)

/** 桌面在 UI 上的状态, 由 [WorkspaceDesktopCatalog.statusOf] 从探测结果推导 */
enum class WorkspaceDesktopStatus {
    /** 工作区没有配置桌面方案 */
    NOT_CONFIGURED,

    /** 配了方案, 但 rootfs 还没装 */
    ROOTFS_MISSING,

    /** rootfs 装好了, 桌面还没装 */
    NOT_INSTALLED,

    /** 装好了, 端口没有在监听 */
    INSTALLED,

    /** 端口能响应 HTTP, 认为正在运行 */
    RUNNING,
}

/**
 * 各发行版的桌面方案脚本生成器 (纯逻辑, 不发任何网络请求)。
 *
 * 约定:
 * - 端口只绑 127.0.0.1。App 自己的 WebServer 占用 8080, 任何方案都不许用 8080。
 * - 所有下载都在 rootfs 内进行 (curl/wget/包管理器), App 只负责投递脚本、读日志、探测端口。
 * - 日志固定写到 [LOG_PATH] / [INSTALL_LOG_PATH], App 从 rootfs 的 /tmp 里原样读出来给用户看。
 */
object WorkspaceDesktopCatalog {
    const val PORT_CODE_SERVER = 8081
    const val PORT_NOVNC = 6080
    const val PORT_JUPYTER = 8888

    /** GNOME / Niri / noVNC 都用 VNC 出画面, 网页入口 (noVNC + websockify) 共用这一个端口。 */
    const val PORT_VNC = 6080

    /** 原始 VNC (RFB) 端口: x11vnc / wayvnc 都只绑这个回环口, 由 websockify 转成 websocket。 */
    const val VNC_RAW_PORT = 5900

    /** api.github.com 不可达时回退的 code-server 版本（可在安装脚本里用 AIRKO_DESKTOP_VERSION 覆盖）。 */
    const val PINNED_CODE_SERVER_VERSION = "4.141.0"

    /** rootfs 内的工作日志路径 (启动脚本重定向到这里) */
    const val LOG_PATH = "/tmp/airko-desktop.log"

    /** rootfs 内的安装日志路径 (App 包装安装命令时重定向到这里) */
    const val INSTALL_LOG_PATH = "/tmp/airko-desktop-install.log"

    /**
     * 安装脚本给 App 打的下载进度标记行: `[airko-dl] <已下载字节> <总字节>`
     * (总字节为 0 表示服务器没给 Content-Length, App 只显示已下载量)。
     */
    const val DOWNLOAD_PROGRESS_PREFIX = "[airko-dl]"

    /** code-server 的安装目录 (rootfs 内) */
    const val CODE_SERVER_HOME = "\$HOME/.airko/code-server"

    /** 自备 noVNC 前端时的安装目录 (Arch 官方仓库没有 novnc 包) */
    const val NOVNC_HOME = "\$HOME/.airko/novnc"

    /** 读日志尾部时最多给 UI 多少字节: apt/pip 的输出能到几十 MB, 全读会撑爆内存 */
    const val LOG_TAIL_BYTES = RootfsLogTail.DEFAULT_MAX_BYTES

    private val APK_PATHS = listOf("sbin/apk", "usr/sbin/apk", "bin/apk", "usr/bin/apk")
    private val APT_PATHS = listOf("usr/bin/apt-get", "bin/apt-get")
    private val PACMAN_PATHS = listOf("usr/bin/pacman", "bin/pacman")
    private val DNF_PATHS = listOf("usr/bin/dnf", "bin/dnf", "usr/bin/dnf5", "bin/dnf5")

    /** rootfs 里真实存在的包管理器; apk/apt-get/pacman 都是普通可执行文件, 宿主侧 exists() 可靠 */
    fun detectPackageManager(linuxDir: File): WorkspacePackageManager {
        if (!linuxDir.isDirectory) return WorkspacePackageManager.UNKNOWN
        return when {
            APK_PATHS.any { File(linuxDir, it).exists() } -> WorkspacePackageManager.APK
            APT_PATHS.any { File(linuxDir, it).exists() } -> WorkspacePackageManager.APT
            PACMAN_PATHS.any { File(linuxDir, it).exists() } -> WorkspacePackageManager.PACMAN
            DNF_PATHS.any { File(linuxDir, it).exists() } -> WorkspacePackageManager.DNF
            else -> WorkspacePackageManager.UNKNOWN
        }
    }

    /** 方案的下载体量估计 (与发行版无关, 选择方案时就要展示) */
    fun sizeHintBytes(desktop: WorkspaceDesktop): Long? = when (desktop) {
        // code-server 官方 linux tarball (含 node) 实测约 240 MB
        WorkspaceDesktop.CODE_SERVER -> 240L * 1024 * 1024
        // xvfb + x11vnc + fluxbox + xterm + novnc + websockify 的包管理体量
        WorkspaceDesktop.NOVNC -> 80L * 1024 * 1024
        // jupyterlab 本体不大, 但依赖链 (ipykernel/nbconvert/pyzmq…) 装完要几百 MB
        WorkspaceDesktop.JUPYTER -> 200L * 1024 * 1024
        // 完整 GNOME 桌面 (gnome-shell 的 X11 会话 + Xvnc)
        WorkspaceDesktop.GNOME -> 1200L * 1024 * 1024
        // niri + wayvnc（Wayland 合成器，包体量小）
        WorkspaceDesktop.NIRI -> 120L * 1024 * 1024
        WorkspaceDesktop.NONE, WorkspaceDesktop.CUSTOM -> null
    }

    /** 安装耗时估计, 按手机上的「下载 + 解包 + 装依赖」估算 */
    fun installMinutes(desktop: WorkspaceDesktop): Int? = when (desktop) {
        WorkspaceDesktop.CODE_SERVER -> 5
        WorkspaceDesktop.NOVNC -> 3
        WorkspaceDesktop.JUPYTER -> 8
        WorkspaceDesktop.GNOME -> 15
        WorkspaceDesktop.NIRI -> 5
        WorkspaceDesktop.NONE, WorkspaceDesktop.CUSTOM -> null
    }

    fun isValidPort(port: Int): Boolean = port in 1..65535

    /** 桌面在 App 内打开时的地址, 只可能是回环地址 */
    fun urlFor(port: Int): String? =
        if (isValidPort(port)) "http://127.0.0.1:$port/" else null

    /**
     * WebView 只允许加载回环地址: 桌面方案监听在本机, 页内 JS 跑在 shell 旁边,
     * 一旦跟随链接跳到公网, 等于把一个可执行的浏览器指向外部站点。
     */
    fun isLocalUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        val normalized = host.removePrefix("[").removeSuffix("]")
        return normalized.equals("127.0.0.1", ignoreCase = true) ||
            normalized.equals("localhost", ignoreCase = true) ||
            normalized == "::1"
    }

    /** POSIX 单引号字面量; 自定义命令里可能有引号/空格/分号, 拼进脚本前必须转义 */
    fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** 由探测结果推导 UI 状态; installed == null 表示该方案没有安装步骤, 视为已就绪 */
    fun statusOf(
        desktop: WorkspaceDesktop,
        rootfsReady: Boolean,
        installed: Boolean?,
        running: Boolean,
    ): WorkspaceDesktopStatus = when {
        desktop == WorkspaceDesktop.NONE -> WorkspaceDesktopStatus.NOT_CONFIGURED
        !rootfsReady -> WorkspaceDesktopStatus.ROOTFS_MISSING
        running -> WorkspaceDesktopStatus.RUNNING
        installed == false -> WorkspaceDesktopStatus.NOT_INSTALLED
        else -> WorkspaceDesktopStatus.INSTALLED
    }

    /**
     * 生成方案脚本。
     *
     * @param packageManager 由 [detectPackageManager] 探测得到; [WorkspacePackageManager.UNKNOWN] 时,
     *   依赖包管理器的方案会给出一个明确报错的脚本, 而不是拼一堆必然 command not found 的命令。
     */
    fun plan(
        desktop: WorkspaceDesktop,
        packageManager: WorkspacePackageManager,
        customCommand: String = "",
        customPort: Int = 0,
    ): WorkspaceDesktopPlan? = when (desktop) {
        WorkspaceDesktop.NONE -> null

        WorkspaceDesktop.CODE_SERVER -> WorkspaceDesktopPlan(
            desktop = desktop,
            packageManager = packageManager,
            installScript = codeServerInstallScript(),
            probeScript = CODE_SERVER_PROBE,
            startScript = codeServerStartScript(),
            stopScript = codeServerStopScript(),
            port = PORT_CODE_SERVER,
            url = urlFor(PORT_CODE_SERVER),
            sizeHintBytes = sizeHintBytes(desktop),
            installMinutes = installMinutes(desktop),
        )

        WorkspaceDesktop.NOVNC -> WorkspaceDesktopPlan(
            desktop = desktop,
            packageManager = packageManager,
            installScript = novncInstallScript(packageManager),
            probeScript = NOVNC_PROBE,
            startScript = novncStartScript(),
            stopScript = novncStopScript(),
            port = PORT_NOVNC,
            url = vncWebUrl(),
            sizeHintBytes = sizeHintBytes(desktop),
            installMinutes = installMinutes(desktop),
        )

        WorkspaceDesktop.JUPYTER -> WorkspaceDesktopPlan(
            desktop = desktop,
            packageManager = packageManager,
            installScript = jupyterInstallScript(packageManager),
            probeScript = JUPYTER_PROBE,
            startScript = jupyterStartScript(),
            stopScript = jupyterStopScript(),
            port = PORT_JUPYTER,
            url = urlFor(PORT_JUPYTER),
            sizeHintBytes = sizeHintBytes(desktop),
            installMinutes = installMinutes(desktop),
        )

        WorkspaceDesktop.GNOME -> WorkspaceDesktopPlan(
            desktop = desktop,
            packageManager = packageManager,
            installScript = gnomeInstallScript(packageManager),
            probeScript = GNOME_PROBE,
            startScript = gnomeStartScript(),
            stopScript = gnomeStopScript(),
            port = PORT_VNC,
            url = vncWebUrl(),
            sizeHintBytes = sizeHintBytes(desktop),
            installMinutes = installMinutes(desktop),
        )

        WorkspaceDesktop.NIRI -> WorkspaceDesktopPlan(
            desktop = desktop,
            packageManager = packageManager,
            installScript = niriInstallScript(packageManager),
            probeScript = NIRI_PROBE,
            startScript = niriStartScript(),
            stopScript = niriStopScript(),
            port = PORT_VNC,
            url = vncWebUrl(),
            sizeHintBytes = sizeHintBytes(desktop),
            installMinutes = installMinutes(desktop),
        )

        WorkspaceDesktop.CUSTOM -> {
            val command = customCommand.trim()
            val port = if (isValidPort(customPort)) customPort else 0
            WorkspaceDesktopPlan(
                desktop = desktop,
                packageManager = packageManager,
                // 自定义方案没有 App 能代劳的安装动作: 用户自己的安装命令去终端里跑,
                // 这里只负责把命令当成常驻服务拉起来, 以及按命令文本停掉
                installScript = null,
                probeScript = null,
                startScript = command.takeIf { it.isNotEmpty() }?.let(::customStartScript),
                stopScript = command.takeIf { it.isNotEmpty() }?.let(::customStopScript),
                port = port,
                url = urlFor(port),
                sizeHintBytes = null,
                installMinutes = null,
            )
        }
    }

    // ---------------------------------------------------------------- code-server

    /**
     * 官方 standalone tarball 的资源名里带版本号 (code-server-<版本>-linux-<架构>.tar.gz),
     * 所以不能用 releases/latest/download/<固定文件名> 这种地址, 得先解析最新 tag。
     *
     * 国内加速: 下载地址前缀由 `AIRKO_DESKTOP_DOWNLOAD_MIRROR` 提供 (例如 https://gh-proxy.com/);
     * api.github.com 不通时用 `AIRKO_DESKTOP_VERSION=4.141.0` 直接指定版本, 跳过解析。
     */
    /**
     * 保证 rootfs 里有 curl + CA 证书（Ubuntu/Debian 最小 rootfs 两样都没有）。
     * 幂等：已装过再跑也只是 apt 的 no-op。
     */
    private val ENSURE_DOWNLOAD_TOOLS = script(
        "if command -v apt-get >/dev/null 2>&1; then",
        "  export DEBIAN_FRONTEND=noninteractive",
        "  apt-get update && apt-get install -y --no-install-recommends curl ca-certificates procps",
        "  command -v update-ca-certificates >/dev/null 2>&1 && update-ca-certificates || true",
        "elif command -v apk >/dev/null 2>&1; then",
        "  apk add --no-cache curl ca-certificates",
        "elif command -v pacman >/dev/null 2>&1; then",
        "  pacman -Sy --noconfirm curl ca-certificates",
        "elif command -v dnf >/dev/null 2>&1; then",
        "  dnf install -y curl ca-certificates procps-ng",
        "fi",
    )

    /**
     * 带进度输出的下载函数 (curl 版), 在 rootfs 里定义成 shell 函数。
     *
     * 原来直接前台 `curl -fsSL`, 下载 100+ MB 期间一条日志都没有, 用户判断不了是不是卡住了;
     * 而前台 curl 的进度只在同一条回车刷新的行里, 日志尾巴没法解析。所以这里把下载挪到后台,
     * 每秒打一行「已下载字节 总字节」(见 [DOWNLOAD_PROGRESS_PREFIX]) 给 App 解析出百分比。
     * 总字节来自 HEAD 请求的 Content-Length; 拿不到 (服务器不给或 HEAD 被拒) 就记 0。
     */
    private fun curlDownloadHelperLines(indent: String): Array<String> {
        val dollar = '$'
        return arrayOf(
            "${indent}airko_download() {",
            "${indent}  airko_dl_url=\"${dollar}1\"; airko_dl_dest=\"${dollar}2\"",
            "${indent}  airko_dl_total=${dollar}(curl -fsSIL \"${dollar}airko_dl_url\" 2>/dev/null | tr -d '\\r' | " +
                "sed -n 's/^[Cc]ontent-[Ll]ength: *//p' | tail -n 1)",
            "${indent}  case \"${dollar}airko_dl_total\" in ''|*[!0-9]*) airko_dl_total=0 ;; esac",
            "${indent}  curl -fsSL \"${dollar}airko_dl_url\" -o \"${dollar}airko_dl_dest\" &",
            "${indent}  airko_dl_pid=${dollar}!",
            "${indent}  airko_dl_n=0",
            "${indent}  while kill -0 \"${dollar}airko_dl_pid\" 2>/dev/null && [ \"${dollar}airko_dl_n\" -lt 21600 ]; do",
            "${indent}    airko_dl_size=${dollar}(wc -c < \"${dollar}airko_dl_dest\" 2>/dev/null || echo 0)",
            "${indent}    echo \"$DOWNLOAD_PROGRESS_PREFIX ${dollar}airko_dl_size ${dollar}airko_dl_total\"",
            "${indent}    sleep 1",
            "${indent}    airko_dl_n=${dollar}((airko_dl_n + 1))",
            "${indent}  done",
            "${indent}  wait \"${dollar}airko_dl_pid\"",
            "${indent}}",
            "${indent}airko_fetch() { curl -fsSL \"${dollar}1\"; }",
        )
    }

    /** wget 版下载函数: wget 不方便先取总大小, 总字节一律 0 (App 只显示已下载量) */
    private fun wgetDownloadHelperLines(indent: String): Array<String> {
        val dollar = '$'
        return arrayOf(
            "${indent}airko_download() {",
            "${indent}  airko_dl_url=\"${dollar}1\"; airko_dl_dest=\"${dollar}2\"",
            "${indent}  wget -q -O \"${dollar}airko_dl_dest\" \"${dollar}airko_dl_url\" &",
            "${indent}  airko_dl_pid=${dollar}!",
            "${indent}  airko_dl_n=0",
            "${indent}  while kill -0 \"${dollar}airko_dl_pid\" 2>/dev/null && [ \"${dollar}airko_dl_n\" -lt 21600 ]; do",
            "${indent}    airko_dl_size=${dollar}(wc -c < \"${dollar}airko_dl_dest\" 2>/dev/null || echo 0)",
            "${indent}    echo \"$DOWNLOAD_PROGRESS_PREFIX ${dollar}airko_dl_size 0\"",
            "${indent}    sleep 1",
            "${indent}    airko_dl_n=${dollar}((airko_dl_n + 1))",
            "${indent}  done",
            "${indent}  wait \"${dollar}airko_dl_pid\"",
            "${indent}}",
            "${indent}airko_fetch() { wget -q -O - \"${dollar}1\"; }",
        )
    }

    private fun codeServerInstallScript(): String {
        val dollar = '$'
        return script(
            "set -e",
            "case \"${dollar}(uname -m)\" in",
            "  x86_64|amd64) asset=amd64 ;;",
            "  aarch64|arm64) asset=arm64 ;;",
            "  *) echo \"code-server has no build for ${dollar}(uname -m)\"; exit 1 ;;",
            "esac",
            "",
            // Ubuntu/Debian 的最小 rootfs 里没有 curl，也没有 CA 证书（curl 会报 error 77）：先补齐
            "echo \"ensuring curl + ca-certificates...\"",
            ENSURE_DOWNLOAD_TOOLS,
            "",
            "if command -v curl >/dev/null 2>&1; then",
            *curlDownloadHelperLines(indent = "  "),
            "elif command -v wget >/dev/null 2>&1; then",
            *wgetDownloadHelperLines(indent = "  "),
            "else",
            "  echo \"curl or wget is required to download code-server\"",
            "  exit 1",
            "fi",
            "",
            "version=\"${dollar}{AIRKO_DESKTOP_VERSION:-}\"",
            "version=\"${dollar}{version#v}\"",
            "if [ -z \"${dollar}version\" ]; then",
            "  tag=\"${dollar}(airko_fetch \"https://api.github.com/repos/coder/code-server/releases/latest\" | " +
                "sed -n 's/.*\"tag_name\": *\"\\([^\"]*\\)\".*/\\1/p' | head -n 1)\"",
            "  version=\"${dollar}{tag#v}\"",
            "fi",
            "if [ -z \"${dollar}version\" ]; then",
            // 国内访问 api.github.com 经常不通：退回一个固定版本，至少还能从 releases 下到文件
            "  version=\"$PINNED_CODE_SERVER_VERSION\"",
            "  echo \"cannot resolve the latest code-server version (api.github.com unreachable?); " +
                "falling back to ${dollar}version - set AIRKO_DESKTOP_VERSION to override\"",
            "fi",
            "",
            "mirror=\"${dollar}{AIRKO_DESKTOP_DOWNLOAD_MIRROR:-}\"",
            "url=\"${dollar}{mirror}https://github.com/coder/code-server/releases/download/v${dollar}version/" +
                "code-server-${dollar}version-linux-${dollar}asset.tar.gz\"",
            "echo \"installing code-server ${dollar}version (${dollar}asset) from ${dollar}url\"",
            "",
            "target=\"${dollar}HOME/.airko/code-server\"",
            "work=\"${dollar}HOME/.airko/.code-server-download\"",
            "rm -rf \"${dollar}target\" \"${dollar}work\"",
            "mkdir -p \"${dollar}work\"",
            "airko_download \"${dollar}url\" \"${dollar}work/code-server.tar.gz\"",
            "tar -xzf \"${dollar}work/code-server.tar.gz\" -C \"${dollar}work\"",
            "# 解包目录名带版本号, 统一挪到固定路径, 启动脚本才不用再解析版本",
            "mv \"${dollar}work/code-server-${dollar}version-linux-${dollar}asset\" \"${dollar}target\"",
            "rm -rf \"${dollar}work\"",
            "mkdir -p /usr/local/bin",
            "ln -sf \"${dollar}target/bin/code-server\" /usr/local/bin/code-server",
            "",
            "# Alpine 是 musl, 官方 standalone 里的 node 是 glibc 构建, 缺兼容层会直接起不来",
            "if command -v apk >/dev/null 2>&1; then",
            "  apk add --no-cache libstdc++ gcompat >/dev/null 2>&1 ||",
            "    echo \"warning: could not install gcompat/libstdc++, code-server may fail to start\"",
            "fi",
            "echo \"code-server installed to ${dollar}target\"",
        )
    }

    private const val CODE_SERVER_PROBE =
        "command -v code-server >/dev/null 2>&1 && code-server --version >/dev/null 2>&1"

    // --auth none 只在这条「只绑回环地址」的前提下安全: 端口不出本机, 没有登录页要过。
    // 谁把 bind-addr 改成 0.0.0.0, 谁就必须同时换回密码认证。
    private fun codeServerStartScript(): String = script(
        "nohup \"\$HOME/.airko/code-server/bin/code-server\" \\",
        "  --bind-addr 127.0.0.1:$PORT_CODE_SERVER \\",
        "  --auth none --disable-telemetry --disable-update-check \\",
        "  /workspace > $LOG_PATH 2>&1 &",
    )

    private fun codeServerStopScript(): String = script(
        "pkill -f -- \"\$HOME/.airko/code-server\" >/dev/null 2>&1 || true",
        // 收尾兜底同 stopSweep: 最多等 10 秒让进程自己退, 再强杀
        "for __airko_i in 1 2 3 4 5 6 7 8 9 10; do",
        "  pgrep -f -- \"\$HOME/.airko/code-server\" >/dev/null 2>&1 || break",
        "  sleep 1",
        "done",
        "pkill -9 -f -- \"\$HOME/.airko/code-server\" >/dev/null 2>&1 || true",
    )

    // ---------------------------------------------------------------- novnc

    // noVNC 的网页根目录按发行版落在不同位置; Arch 官方仓库既没有 novnc 也没有 websockify,
    // 只能 pip 装 websockify + 官方 tarball 铺前端, 所以三种来源都要认。
    private val NOVNC_WEBROOT_RESOLVE = script(
        "webroot=/usr/share/novnc",
        "[ -d \"\$webroot\" ] || webroot=/usr/share/webapps/novnc",
        "[ -d \"\$webroot\" ] || webroot=\"\$HOME/.airko/novnc\"",
    )

    /**
     * websockify 的兼容启动包装 (Python 3.14 起 multiprocessing 默认改用 forkserver):
     * · forkserver 启动时要先 bind 一个临时 unix socket, 而 proot 会把 rootfs 里的路径翻译成
     *   宿主长路径, 一超 108 字节 (AF_UNIX sun_path 上限) 就 EINVAL —— 必须强制回 fork;
     * · 包装文件不能叫 websockify.py, 否则会遮蔽同名包, import websockify.websocketproxy 失败;
     * · websockify 的入口函数是 websockify_init (不是 main)。
     */
    private val WEBSOCKIFY_FORK_WRAPPER = script(
        "mkdir -p \"\$HOME/.airko\"",
        "rm -f \"\$HOME/.airko/websockify.py\"",
        "cat > \"\$HOME/.airko/airko-ws.py\" <<'AIRKO_WS_EOF'",
        "import multiprocessing, sys",
        "if __name__ == \"__main__\":",
        "    multiprocessing.set_start_method(\"fork\", force=True)",
        "    from websockify.websocketproxy import websockify_init",
        "    sys.exit(websockify_init())",
        "AIRKO_WS_EOF",
    )

    /** VNC 系方案共用的网页入口: 解析 noVNC 前端目录 -> 铺 websockify 包装 -> 6080 转 5900。 */
    private fun vncWebFrontendStartLines(): String = script(
        NOVNC_WEBROOT_RESOLVE,
        WEBSOCKIFY_FORK_WRAPPER,
        "nohup python3 \"\$HOME/.airko/airko-ws.py\" --web \"\$webroot\" 127.0.0.1:$PORT_NOVNC " +
            "127.0.0.1:$VNC_RAW_PORT >> $LOG_PATH 2>&1 &",
    )

    /** noVNC 页面地址: autoconnect 免去手动点「连接」, resize=scale 让画面铺满 WebView。 */
    private fun vncWebUrl(): String? =
        urlFor(PORT_NOVNC)?.let { "${it}vnc.html?autoconnect=true&resize=scale" }

    private const val NOVNC_PROBE =
        "{ test -f /usr/share/novnc/vnc.html || test -f /usr/share/webapps/novnc/vnc.html || " +
            "test -f \"\$HOME/.airko/novnc/vnc.html\"; } && command -v websockify >/dev/null 2>&1"

    private fun novncInstallScript(packageManager: WorkspacePackageManager): String =
        when (packageManager) {
            WorkspacePackageManager.APK -> script(
                "set -e",
                "# xterm 让用户进桌面后有东西可点: 只有一个空窗口管理器的话看起来像坏了",
                "apk add --no-cache xvfb x11vnc fluxbox xterm novnc websockify procps-ng",
            )

            WorkspacePackageManager.APT -> script(
                "set -e",
                "export DEBIAN_FRONTEND=noninteractive",
                "apt-get update",
                "apt-get install -y --no-install-recommends xvfb x11vnc fluxbox xterm novnc websockify procps",
            )

            WorkspacePackageManager.PACMAN -> script(
                "set -e",
                "pacman -Sy --noconfirm xorg-server-xvfb x11vnc fluxbox xterm " +
                "python python-pip curl ca-certificates procps",
                pythonPipInstall("websockify"),
                novncFrontendFromGithub(),
            )

            WorkspacePackageManager.DNF -> script(
                "set -e",
                "dnf install -y xorg-x11-server-Xvfb x11vnc fluxbox xterm novnc python3-websockify procps-ng",
            )

            WorkspacePackageManager.UNKNOWN -> unknownDistroScript("noVNC")
        }

    private fun novncStartScript(): String = script(
        "export DISPLAY=:99",
        "export XDG_RUNTIME_DIR=/run",
        "mkdir -p \"\$XDG_RUNTIME_DIR\" /tmp/.X11-unix",
        "chmod 700 \"\$XDG_RUNTIME_DIR\"",
        "# 清掉上次残留的 X socket/锁, 避免 Xvfb 起不来",
        "rm -f /tmp/.X11-unix/X99 /tmp/.X99-lock",
        "# 三个进程都只监听回环; websockify(noVNC 网页 + websocket) 是唯一入口, 只有 App 的 WebView 会连它",
        "nohup Xvfb :99 -screen 0 1280x800x24 -nolisten tcp > $LOG_PATH 2>&1 &",
        "sleep 1",
        "nohup fluxbox >> $LOG_PATH 2>&1 &",
        "sleep 2",
        "# 主动开一个终端窗口, 不然用户只能看到空桌面",
        "(command -v xterm >/dev/null 2>&1 && nohup xterm -geometry 80x24+10+10 >> $LOG_PATH 2>&1 &)",
        "# proot 里没有 SysV 共享内存 (shmget ENOSYS), 不加 -noshm 的话 x11vnc 会直接退出",
        "nohup x11vnc -display :99 -forever -shared -nopw -localhost -noshm -rfbport $VNC_RAW_PORT >> $LOG_PATH 2>&1 &",
        "sleep 2",
        // x11vnc 起不来时明确失败, 别让 websockify 独自撑着让用户看黑屏
        "pgrep -x x11vnc >/dev/null 2>&1 || { echo 'x11vnc did not start' >> $LOG_PATH; exit 1; }",
        vncWebFrontendStartLines(),
    )

    // stop 里 fluxbox/xterm 只能 pkill -x (按进程名精确匹配): pkill -f 的 "xterm" 会命中
    // App 常驻 shell 命令行里的 "TERM=xterm-256color" 参数, 把自己的会话一起杀掉
    private fun novncStopScript(): String = script(
        "pkill -f -- 'airko-ws.py' >/dev/null 2>&1 || true",
        "pkill -f -- 'x11vnc -display' >/dev/null 2>&1 || true",
        "pkill -f -- 'Xvfb :99' >/dev/null 2>&1 || true",
        "pkill -x fluxbox >/dev/null 2>&1 || true",
        "pkill -x xterm >/dev/null 2>&1 || true",
        *stopSweep(
            fPatterns = listOf("airko-ws.py", "x11vnc -display", "Xvfb :99"),
            xPatterns = listOf("fluxbox", "xterm"),
        ).toTypedArray(),
    )

    // ---------------------------------------------------------------- jupyter

    private const val JUPYTER_PROBE =
        "command -v jupyter-lab >/dev/null 2>&1 || python3 -c \"import jupyterlab\" >/dev/null 2>&1"

    private fun jupyterInstallScript(packageManager: WorkspacePackageManager): String =
        when (packageManager) {
            WorkspacePackageManager.APK -> script(
                "set -e",
                "apk add --no-cache python3 py3-pip",
                pythonPipInstall("jupyterlab"),
            )

            WorkspacePackageManager.APT -> script(
                "set -e",
                "export DEBIAN_FRONTEND=noninteractive",
                "apt-get update",
                "apt-get install -y --no-install-recommends python3 python3-pip ca-certificates procps",
                pythonPipInstall("jupyterlab"),
            )

            WorkspacePackageManager.PACMAN -> script(
                "set -e",
                "pacman -Sy --noconfirm python python-pip",
                pythonPipInstall("jupyterlab"),
            )

            WorkspacePackageManager.DNF -> script(
                "set -e",
                "dnf install -y python3 python3-pip ca-certificates procps-ng",
                pythonPipInstall("jupyterlab"),
            )

            WorkspacePackageManager.UNKNOWN -> unknownDistroScript("JupyterLab")
        }

    // token/password 置空 + 只绑回环, 理由同 code-server
    private fun jupyterStartScript(): String =
        "nohup python3 -m jupyterlab --ServerApp.ip=127.0.0.1 --ServerApp.port=$PORT_JUPYTER " +
            "--ServerApp.token= --ServerApp.password= --ServerApp.root_dir=/workspace " +
            "--allow-root --no-browser > $LOG_PATH 2>&1 &"

    private fun jupyterStopScript(): String = script(
        "for pattern in 'python3 -m jupyterlab' 'jupyter-lab'; do",
        "  pkill -f -- \"\$pattern\" >/dev/null 2>&1 || true",
        "done",
        *stopSweep(listOf("python3 -m jupyterlab", "jupyter-lab")).toTypedArray(),
    )

    // ---------------------------------------------------------------- GNOME（X11 + Xvnc）

    /**
     * 完整 GNOME 桌面（gnome-shell 的 X11 会话 + Xvnc）。配方在 Ubuntu 24.04 / GNOME 46 上实机验证：
     * 1. gnome-session 在非 systemd 环境里起不来（它会硬连系统总线），直接起 `gnome-shell --x11`；
     * 2. gnome-shell 是否去找 logind 由 `/run/systemd/seats` 是否存在决定；安装阶段的包维护脚本
     *    会把它建出来，启动前必须清掉，让 shell 退回不依赖 systemd 的 LoginManagerDummy；
     * 3. 完全没有系统总线时 accountsservice 会抛异常把 shell 打死，所以要先拉一个不降权的私有
     *    system bus（总线上没有任何服务时只是给出警告，不致命）；
     * 4. Xvnc 用 -localhost 只绑回环，网页入口仍是 websockify + noVNC。
     *
     * 版本限制：GNOME 49 起移除了 X11 会话，只有 ≤48 的发行版能这么跑。APT 分支先查候选版本再决定
     * 是否下载；Fedora/Arch 当前版本的 gnome-shell 已 ≥49，直接给出明确提示而不是白下一趟。
     */
    private fun gnomeInstallScript(packageManager: WorkspacePackageManager): String = when (packageManager) {
        WorkspacePackageManager.APT -> script(
            "set -e",
            "export DEBIAN_FRONTEND=noninteractive",
            "apt-get update",
            // 先看候选版本, 避免下完 1 GB 才发现 GNOME >=49 装不了
            "candidate=\$(apt-cache policy gnome-shell 2>/dev/null | awk '/Candidate:/{print \$2; exit}')",
            "major=\$(echo \"\$candidate\" | grep -oE '^[0-9]+')",
            "if [ -z \"\$major\" ] || [ \"\$major\" -ge 49 ]; then",
            "  echo \"gnome-shell \$candidate 不可用: GNOME 49 起移除了 X11 会话 (proot 里没有 systemd, Wayland 会话起不来)\" >&2",
            "  echo '请改用 Ubuntu 24.04 / Debian 12 等 GNOME <=48 的 rootfs, 或选 niri 桌面' >&2",
            "  exit 1",
            "fi",
            "apt-get install -y --no-install-recommends gnome-shell gnome-terminal nautilus " +
                "tigervnc-standalone-server novnc websockify procps dbus-x11 fonts-cantarell",
        )

        WorkspacePackageManager.PACMAN, WorkspacePackageManager.DNF ->
            unavailableScript(
                "GNOME",
                "当前 Arch/Fedora 的 gnome-shell 已 >=49 (无 X11 会话), 请改用 Ubuntu 24.04 / Debian 12 rootfs",
            )

        WorkspacePackageManager.APK, WorkspacePackageManager.UNKNOWN ->
            unavailableScript("GNOME", "Alpine 仓库没有 GNOME，请换 Ubuntu 24.04 / Debian 12 rootfs")
    }

    private const val GNOME_PROBE =
        "{ command -v Xvnc || command -v Xtigervnc; } >/dev/null 2>&1 && " +
            "command -v gnome-shell >/dev/null 2>&1 && command -v dbus-launch >/dev/null 2>&1 && " +
            "command -v websockify >/dev/null 2>&1 && " +
            "{ test -f /usr/share/novnc/vnc.html || test -f /usr/share/webapps/novnc/vnc.html || " +
            "test -f \"\$HOME/.airko/novnc/vnc.html\"; }"

    private fun gnomeStartScript(): String = script(
        // 日志只在开头清一次, 后面全部追加 —— 否则 dbus 这类先启动的组件出错时,
        // 报错行会被之后 Xvnc 的 `> 日志` 截断冲掉, 排障时什么都看不到
        ": > $LOG_PATH",
        "export DISPLAY=:1",
        "export XDG_RUNTIME_DIR=/run",
        "mkdir -p \"\$XDG_RUNTIME_DIR\" /tmp/.X11-unix",
        // dbus 拒绝在 world-writable 的 XDG_RUNTIME_DIR 里建 transient 服务目录
        "chmod 700 \"\$XDG_RUNTIME_DIR\"",
        // /run/systemd 存在 => gnome-shell 认定有 logind(LoginManagerSystemd), 连系统总线失败会直接崩;
        // 包维护脚本在安装阶段会把它建出来, 每次启动都要清掉 => 退回 LoginManagerDummy
        "rm -rf \"\$XDG_RUNTIME_DIR/systemd\" \"\$XDG_RUNTIME_DIR/user\"",
        "rm -f /tmp/.X11-unix/X1 /tmp/.X1-lock",
        // 私有 system bus: 没有它 accountsservice 之类的调用会抛异常把 shell 打死
        "mkdir -p \"\$HOME/.airko\" /run/dbus",
        "cat > \"\$HOME/.airko/airko-system-bus.conf\" <<'AIRKO_SYSBUS_EOF'",
        "<busconfig>",
        "  <type>system</type>",
        "  <listen>unix:path=/run/dbus/system_bus_socket</listen>",
        "  <auth>EXTERNAL</auth>",
        "  <policy context=\"default\">",
        "    <allow send_destination=\"*\" eavesdrop=\"true\"/>",
        "    <allow eavesdrop=\"true\"/>",
        // own 类与 user 类属性必须分开写: 合并到同一个 <allow> 会被 dbus-daemon 判为
        // "Invalid combination of attributes" 直接拒载 (probe25e 验证过的合法形态)
        "    <allow own=\"*\"/>",
        "    <allow user=\"*\"/>",
        "  </policy>",
        "</busconfig>",
        "AIRKO_SYSBUS_EOF",
        "if ! dbus-send --system --dest=org.freedesktop.DBus --type=method_call --print-reply / " +
            "org.freedesktop.DBus.ListNames >/dev/null 2>&1; then",
        "  rm -f /run/dbus/system_bus_socket",
        "  dbus-daemon --config-file=\"\$HOME/.airko/airko-system-bus.conf\" --fork >> $LOG_PATH 2>&1 || true",
        "  sleep 1",
        "fi",
        // 总线起不来 shell 必崩 (endSessionDialog -> AccountsService 连不上系统总线), 明确报错退出
        "if ! dbus-send --system --dest=org.freedesktop.DBus --type=method_call --print-reply / " +
            "org.freedesktop.DBus.ListNames >/dev/null 2>&1; then",
        "  echo 'system bus is not reachable; gnome-shell would crash on startup (see dbus-daemon output above)' >> $LOG_PATH",
        "  exit 1",
        "fi",
        "VNCBIN=\$(command -v Xvnc || command -v Xtigervnc)",
        "[ -n \"\$VNCBIN\" ] || { echo 'tigervnc server binary not found' >&2; exit 1; }",
        "nohup \"\$VNCBIN\" :1 -geometry 1280x800 -depth 24 -SecurityTypes None " +
            "-rfbport $VNC_RAW_PORT -localhost -AlwaysShared >> $LOG_PATH 2>&1 &",
        "sleep 3",
        "eval \"\$(dbus-launch --sh-syntax)\"",
        "echo \"\$DBUS_SESSION_BUS_PID\" > /tmp/airko-gnome-bus.pid",
        "(export XDG_SESSION_TYPE=x11 LIBGL_ALWAYS_SOFTWARE=1; nohup gnome-shell --x11 >> $LOG_PATH 2>&1 &)",
        "sleep 5",
        // 起完就死要当失败报出去, 而不是让用户对着黑屏等端口探测超时
        "pgrep -f -- 'gnome-shell --x11' >/dev/null 2>&1 || { echo 'gnome-shell did not stay up' >> $LOG_PATH; exit 1; }",
        vncWebFrontendStartLines(),
    )

    private fun gnomeStopScript(): String = script(
        "pkill -f -- 'airko-ws.py' >/dev/null 2>&1 || true",
        "pkill -f -- 'gnome-shell' >/dev/null 2>&1 || true",
        "pkill -f -- 'Xtigervnc' >/dev/null 2>&1 || true",
        "pkill -f -- 'Xvnc' >/dev/null 2>&1 || true",
        "[ -f /tmp/airko-gnome-bus.pid ] && kill -9 \"\$(cat /tmp/airko-gnome-bus.pid)\" >/dev/null 2>&1 || true",
        // pidfile 每次启动都会被覆盖, 兜底杀掉上次崩溃留下的孤立 session bus (dbus-launch 起的都是这个命令行)
        "pkill -f -- 'dbus-daemon --syslog' >/dev/null 2>&1 || true",
        "pkill -f -- 'airko-system-bus.conf' >/dev/null 2>&1 || true",
        *stopSweep(
            listOf(
                "airko-ws.py", "gnome-shell", "Xtigervnc", "Xvnc",
                "dbus-daemon --syslog", "airko-system-bus.conf",
            ),
        ).toTypedArray(),
    )

    // ---------------------------------------------------------------- Niri（Wayland + wayvnc）

    /**
     * niri 是滚动平铺的 Wayland 合成器，配 wayvnc 出远程桌面。包体量小，但需要较新的发行版
     * （Ubuntu 25.04+ / Fedora 42+ / Arch），老发行版装不上时给出明确提示。
     */
    private fun niriInstallScript(packageManager: WorkspacePackageManager): String = when (packageManager) {
        WorkspacePackageManager.PACMAN -> script(
            "set -e",
            "pacman -Sy --noconfirm cage niri wayvnc wlr-randr foot python python-pip curl ca-certificates",
            pythonPipInstall("websockify"),
            novncFrontendFromGithub(),
        )

        WorkspacePackageManager.DNF -> script(
            "set -e",
            "dnf install -y cage niri wayvnc wlr-randr foot novnc python3-websockify procps-ng",
        )

        WorkspacePackageManager.APT -> script(
            "set -e",
            "export DEBIAN_FRONTEND=noninteractive",
            "apt-get update",
            "apt-get install -y cage niri wayvnc wlr-randr foot novnc websockify procps || {",
            "  echo 'niri/wayvnc/cage 不在当前发行版的仓库里（需要 Ubuntu 25.04+ 或 Debian trixie+）' >&2",
            "  echo '可以改用 Arch/Fedora，或换 GNOME/code-server 方案' >&2",
            "  exit 1",
            "}",
        )

        WorkspacePackageManager.APK, WorkspacePackageManager.UNKNOWN ->
            unavailableScript("niri", "请换 Arch/Fedora/较新的 Ubuntu rootfs")
    }

    private const val NIRI_PROBE =
        "command -v niri >/dev/null 2>&1 && command -v wayvnc >/dev/null 2>&1 && " +
            "command -v cage >/dev/null 2>&1 && command -v websockify >/dev/null 2>&1"

    /**
     * niri 至今没有无头后端（TTY 后端在 proot 环境里直接 panic, 因为它只认 DRM/TTY）。
     * 方案: 用 cage（wlroots 系 kiosk 合成器）开一个 headless 输出当"父母", niri 走 winit
     * 后端作为它的普通客户端嵌套在里面; wayvnc 直接挂 niri —— niri 自带 wlr-screencopy +
     * 虚拟指针/键盘, 输入也能用。cage 的客户应用是 sleep（保活）, 和 niri 生命周期解耦。
     *
     * socket 目录 (XDG_RUNTIME_DIR=/run) 由 App 重挂到 cache 短路径, 见 WorkspaceRuntimeBinds。
     *
     * 真机踩坑记录（Fedora 44, niri 26.04）:
     * · cage 的 headless 输出必须显式 --custom-mode + --on: 输出处于 disabled 时帧回调/截屏
     *   链路永远等不到帧, wayvnc 和 grim 会直接挂死（开个终端窗口就"卡死"的假象就是这么来的）;
     * · 不要在 rootfs 内用 strace -p 探 proot 里的进程: ptrace attach 不被支持, 进程会挂住。
     */
    private fun niriStartScript(): String = script(
        "export XDG_RUNTIME_DIR=/run",
        "mkdir -p \"\$XDG_RUNTIME_DIR\" /tmp/.X11-unix",
        "chmod 700 \"\$XDG_RUNTIME_DIR\"",
        "# 清掉上次残留的 socket 与 X 锁, 保证 cage 稳定拿到 wayland-0, niri 拿到 wayland-1",
        "rm -f \"\$XDG_RUNTIME_DIR\"/wayland-* \"\$XDG_RUNTIME_DIR\"/niri.* /tmp/.X11-unix/X* /tmp/.X*-lock",
        "(WLR_BACKENDS=headless WLR_HEADLESS_OUTPUTS=1 WLR_LIBINPUT_NO_DEVICES=1 " +
            "nohup cage -- sleep infinity > $LOG_PATH 2>&1 &)",
        "# 等 cage 的 wayland socket 就绪",
        "for i in \$(seq 1 20); do [ -S \"\$XDG_RUNTIME_DIR/wayland-0\" ] && break; sleep 0.5; done",
        "(WAYLAND_DISPLAY=wayland-0 wlr-randr --output HEADLESS-1 --custom-mode 1280x800 >/dev/null 2>&1 || true)",
        "(WAYLAND_DISPLAY=wayland-0 wlr-randr --output HEADLESS-1 --on >/dev/null 2>&1 || true)",
        "(WAYLAND_DISPLAY=wayland-0 nohup niri > /tmp/airko-niri.log 2>&1 &)",
        "# 等 niri 的 wayland socket (cage 占了 wayland-0, niri 是 wayland-1)",
        "for i in \$(seq 1 40); do [ -S \"\$XDG_RUNTIME_DIR/wayland-1\" ] && break; sleep 0.5; done",
        // niri 起不来时报失败比让用户对着黑屏等超时好: App 会把日志尾巴显示出来
        "[ -S \"\$XDG_RUNTIME_DIR/wayland-1\" ] || { echo 'niri did not start (wayland-1 socket missing)' >> $LOG_PATH; exit 1; }",
        "(WAYLAND_DISPLAY=wayland-1 nohup wayvnc -o winit 127.0.0.1 $VNC_RAW_PORT >> $LOG_PATH 2>&1 &)",
        "# 触屏用户没有 Super 键, 主动开一个终端窗口, 不然进来只能看到黑屏",
        "(command -v foot >/dev/null 2>&1 && WAYLAND_DISPLAY=wayland-1 XDG_RUNTIME_DIR=/run nohup foot >/dev/null 2>&1 &)",
        "sleep 2",
        vncWebFrontendStartLines(),
    )

    private fun niriStopScript(): String = script(
        "pkill -f -- 'airko-ws.py' >/dev/null 2>&1 || true",
        "pkill -f -- 'wayvnc' >/dev/null 2>&1 || true",
        "pkill -f -- 'niri' >/dev/null 2>&1 || true",
        "pkill -f -- 'cage' >/dev/null 2>&1 || true",
        // cage 保活用的客户进程 (cage 退出后它会变成孤儿继续睡)
        "pkill -f -- 'sleep infinity' >/dev/null 2>&1 || true",
        // 启动时主动开的终端窗口也要收掉, 否则残留进程会让 leader proot 一直留守
        "pkill -x foot >/dev/null 2>&1 || true",
        *stopSweep(
            fPatterns = listOf("airko-ws.py", "wayvnc", "niri", "cage", "sleep infinity"),
            xPatterns = listOf("foot"),
        ).toTypedArray(),
    )

    /** 该发行版装不了这个桌面时的明确提示（而不是让包管理器报一堆找不到的包名）。 */
    private fun unavailableScript(scheme: String, hint: String): String = script(
        "echo \"cannot install $scheme on this rootfs: $hint\" >&2",
        "exit 1",
    )

    // ---------------------------------------------------------------- custom

    private fun customStartScript(command: String): String =
        "nohup sh -c ${shellSingleQuote(command)} > $LOG_PATH 2>&1 &"

    // 自定义命令的停止只能靠命令文本本身匹配: 启动时用 sh -c '<命令>' 包了一层,
    // 所以 pkill -f 能同时命中包装壳和真正的服务进程
    private fun customStopScript(command: String): String = script(
        "pkill -f -- ${shellSingleQuote(command)} >/dev/null 2>&1 || true",
        // 收尾兜底同 stopSweep: 最多等 10 秒让进程自己退, 再强杀
        "for __airko_i in 1 2 3 4 5 6 7 8 9 10; do",
        "  pgrep -f -- ${shellSingleQuote(command)} >/dev/null 2>&1 || break",
        "  sleep 1",
        "done",
        "pkill -9 -f -- ${shellSingleQuote(command)} >/dev/null 2>&1 || true",
    )

    /**
     * 停止脚本的公共收尾: 最多等 10 秒让进程优雅退出, 然后对同一批模式补一轮 SIGKILL。
     *
     * 真机踩坑 (probe25h): websockify 退出要等 WebSocket 连接关闭、cage 偶发不收 SIGTERM,
     * SIGTERM 发出后 10 秒端口还挂着, App 的停止等待窗口一到就误报「停止失败」——桌面其实停了,
     * 还会留下吃端口的残党。钉死这段收尾后停止就是确定性的: 慢的话 10 秒后强杀, 端口随即释放。
     */
    private fun stopSweep(fPatterns: List<String>, xPatterns: List<String> = emptyList()): List<String> {
        val joined = fPatterns.joinToString("|")
        return buildList {
            add("for __airko_i in 1 2 3 4 5 6 7 8 9 10; do")
            add("  pgrep -f -- '$joined' >/dev/null 2>&1 || break")
            add("  sleep 1")
            add("done")
            fPatterns.forEach { add("pkill -9 -f -- '$it' >/dev/null 2>&1 || true") }
            xPatterns.forEach { add("pkill -9 -x $it >/dev/null 2>&1 || true") }
        }
    }

    // ---------------------------------------------------------------- 共用片段

    /**
     * pip 安装。Ubuntu 24.04 / Debian 12 / Arch 都把系统 python 标记成 externally-managed,
     * 需要 --break-system-packages; 老版本 pip 不认这个参数, 所以留一次不带参数的兜底。
     */
    private fun pythonPipInstall(packageName: String): String = script(
        "python3 -m pip install --no-cache-dir --break-system-packages $packageName ||",
        "  python3 -m pip install --no-cache-dir $packageName",
    )

    /**
     * Arch 官方仓库没有 novnc, 用官方 tarball 把前端铺到 ~/.airko/novnc。
     * 版本同样可以先用 `AIRKO_DESKTOP_NOVNC_VERSION` 指定, 避免 api.github.com 不可达时卡住。
     */
    private fun novncFrontendFromGithub(): String {
        val dollar = '$'
        return script(
            // 下载同样走带进度输出的函数 (这里跑在 pacman 分支, curl 已经由上面的包安装补齐)
            *curlDownloadHelperLines(indent = ""),
            "noVNC_version=\"${dollar}{AIRKO_DESKTOP_NOVNC_VERSION:-}\"",
            "noVNC_version=\"${dollar}{noVNC_version#v}\"",
            "if [ -z \"${dollar}noVNC_version\" ]; then",
            "  noVNC_tag=\"${dollar}(curl -fsSL https://api.github.com/repos/novnc/noVNC/releases/latest | " +
                "sed -n 's/.*\"tag_name\": *\"\\([^\"]*\\)\".*/\\1/p' | head -n 1)\"",
            "  noVNC_version=\"${dollar}{noVNC_tag#v}\"",
            "fi",
            "if [ -z \"${dollar}noVNC_version\" ]; then",
            "  echo \"cannot resolve the latest noVNC version; set AIRKO_DESKTOP_NOVNC_VERSION\"",
            "  exit 1",
            "fi",
            "mirror=\"${dollar}{AIRKO_DESKTOP_DOWNLOAD_MIRROR:-}\"",
            "work=\"${dollar}HOME/.airko/.novnc-download\"",
            "rm -rf \"${dollar}work\" \"${dollar}HOME/.airko/novnc\"",
            "mkdir -p \"${dollar}work\"",
            "airko_download " +
                "\"${dollar}{mirror}https://github.com/novnc/noVNC/archive/refs/tags/v${dollar}noVNC_version.tar.gz\" " +
                "\"${dollar}work/novnc.tar.gz\"",
            "tar -xzf \"${dollar}work/novnc.tar.gz\" -C \"${dollar}work\"",
            "mv \"${dollar}work/noVNC-${dollar}noVNC_version\" \"${dollar}HOME/.airko/novnc\"",
            "rm -rf \"${dollar}work\"",
        )
    }

    /** 探测不到包管理器时的安装脚本: 明确报错, 而不是拼一堆必然 command not found 的命令 */
    private fun unknownDistroScript(scheme: String): String = script(
        "echo \"cannot install $scheme: this rootfs has no apk, apt-get, pacman or dnf\" >&2",
        "echo \"install it manually from the workspace terminal, or use another rootfs image\" >&2",
        "exit 1",
    )

    private fun script(vararg lines: String): String = lines.joinToString("\n")
}
