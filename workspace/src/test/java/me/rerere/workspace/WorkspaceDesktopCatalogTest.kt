package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 桌面方案脚本生成的纯逻辑测试: 不发任何网络请求, 也不起 proot。
 */
class WorkspaceDesktopCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------------------------------------------------------- 标识解析

    @Test
    fun `fromId falls back to none`() {
        assertEquals(WorkspaceDesktop.CODE_SERVER, WorkspaceDesktop.fromId("code-server"))
        assertEquals(WorkspaceDesktop.NOVNC, WorkspaceDesktop.fromId("novnc"))
        assertEquals(WorkspaceDesktop.JUPYTER, WorkspaceDesktop.fromId("jupyter"))
        assertEquals(WorkspaceDesktop.CUSTOM, WorkspaceDesktop.fromId("custom"))
        assertEquals(WorkspaceDesktop.NONE, WorkspaceDesktop.fromId("CODE_SERVER"))
        assertEquals(WorkspaceDesktop.NONE, WorkspaceDesktop.fromId(null))
        assertEquals(WorkspaceDesktop.NONE, WorkspaceDesktop.fromId(""))
    }

    @Test
    fun `desktop ids are the values stored in the database`() {
        // 这些字符串会原样落进 Room 的 workspaces.desktop 列, 改名等于破坏已存库的数据
        assertEquals("none", WorkspaceDesktop.NONE.id)
        assertEquals("code-server", WorkspaceDesktop.CODE_SERVER.id)
        assertEquals("novnc", WorkspaceDesktop.NOVNC.id)
        assertEquals("jupyter", WorkspaceDesktop.JUPYTER.id)
        assertEquals("custom", WorkspaceDesktop.CUSTOM.id)
        assertEquals(WorkspaceDesktop.entries.size, WorkspaceDesktop.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `fromDistro maps known distro ids`() {
        assertEquals(
            WorkspacePackageManager.APK,
            WorkspacePackageManager.fromDistro(RootfsDistroCatalog.ID_ALPINE),
        )
        assertEquals(
            WorkspacePackageManager.APT,
            WorkspacePackageManager.fromDistro(RootfsDistroCatalog.ID_UBUNTU),
        )
        assertEquals(
            WorkspacePackageManager.APT,
            WorkspacePackageManager.fromDistro(RootfsDistroCatalog.ID_DEBIAN),
        )
        assertEquals(
            WorkspacePackageManager.PACMAN,
            WorkspacePackageManager.fromDistro(RootfsDistroCatalog.ID_ARCH),
        )
        assertEquals(WorkspacePackageManager.UNKNOWN, WorkspacePackageManager.fromDistro("gentoo"))
        assertEquals(WorkspacePackageManager.UNKNOWN, WorkspacePackageManager.fromDistro(null))
    }

    @Test
    fun `detectPackageManager reads real binaries from the rootfs`() {
        val alpine = tmp.newFolder("alpine")
        File(alpine, "sbin").mkdirs()
        File(alpine, "sbin/apk").writeText("")
        assertEquals(WorkspacePackageManager.APK, WorkspaceDesktopCatalog.detectPackageManager(alpine))

        val ubuntu = tmp.newFolder("ubuntu")
        File(ubuntu, "usr/bin").mkdirs()
        File(ubuntu, "usr/bin/apt-get").writeText("")
        assertEquals(WorkspacePackageManager.APT, WorkspaceDesktopCatalog.detectPackageManager(ubuntu))

        val arch = tmp.newFolder("arch")
        File(arch, "usr/bin").mkdirs()
        File(arch, "usr/bin/pacman").writeText("")
        assertEquals(WorkspacePackageManager.PACMAN, WorkspaceDesktopCatalog.detectPackageManager(arch))

        // 目录存在但没有包管理器 (老 rootfs / 手工粘贴的镜像)
        assertEquals(
            WorkspacePackageManager.UNKNOWN,
            WorkspaceDesktopCatalog.detectPackageManager(tmp.newFolder("bare")),
        )
        // 目录根本不存在
        assertEquals(
            WorkspacePackageManager.UNKNOWN,
            WorkspaceDesktopCatalog.detectPackageManager(File(tmp.root, "nope")),
        )
    }

    // ---------------------------------------------------------------- 端口与地址

    @Test
    fun `plans never use the port of the app web server`() {
        WorkspaceDesktop.entries.forEach { desktop ->
            val plan = WorkspaceDesktopCatalog.plan(desktop, WorkspacePackageManager.APK)
            assertTrue(
                "$desktop must not bind 8080",
                plan == null || plan.port != 8080,
            )
        }
        assertEquals(8081, WorkspaceDesktopCatalog.PORT_CODE_SERVER)
        assertEquals(6080, WorkspaceDesktopCatalog.PORT_NOVNC)
        assertEquals(8888, WorkspaceDesktopCatalog.PORT_JUPYTER)
    }

    @Test
    fun `urlFor only accepts valid ports`() {
        assertEquals("http://127.0.0.1:8081/", WorkspaceDesktopCatalog.urlFor(8081))
        assertNull(WorkspaceDesktopCatalog.urlFor(0))
        assertNull(WorkspaceDesktopCatalog.urlFor(-1))
        assertNull(WorkspaceDesktopCatalog.urlFor(65536))
        assertTrue(WorkspaceDesktopCatalog.isValidPort(65535))
        assertFalse(WorkspaceDesktopCatalog.isValidPort(0))
    }

    @Test
    fun `isLocalUrl only trusts loopback hosts`() {
        assertTrue(WorkspaceDesktopCatalog.isLocalUrl("http://127.0.0.1:8081/"))
        assertTrue(WorkspaceDesktopCatalog.isLocalUrl("http://localhost:6080/vnc.html"))
        assertTrue(WorkspaceDesktopCatalog.isLocalUrl("http://[::1]:8888/lab"))
        assertFalse(WorkspaceDesktopCatalog.isLocalUrl("http://192.168.1.5:8081/"))
        assertFalse(WorkspaceDesktopCatalog.isLocalUrl("https://example.com/"))
        // 127.0.0.1 后面跟别的数字不是回环地址
        assertFalse(WorkspaceDesktopCatalog.isLocalUrl("http://127.0.0.10:8081/"))
        assertFalse(WorkspaceDesktopCatalog.isLocalUrl(null))
        assertFalse(WorkspaceDesktopCatalog.isLocalUrl(""))
        assertFalse(WorkspaceDesktopCatalog.isLocalUrl("not a url"))
    }

    @Test
    fun `shellSingleQuote escapes embedded quotes`() {
        assertEquals("'ls -a'", WorkspaceDesktopCatalog.shellSingleQuote("ls -a"))
        assertEquals("'it'\\''s here'", WorkspaceDesktopCatalog.shellSingleQuote("it's here"))
        assertEquals("''", WorkspaceDesktopCatalog.shellSingleQuote(""))
    }

    // ---------------------------------------------------------------- 状态推导

    @Test
    fun `statusOf walks from not configured to running`() {
        val desktop = WorkspaceDesktop.CODE_SERVER
        assertEquals(
            WorkspaceDesktopStatus.NOT_CONFIGURED,
            WorkspaceDesktopCatalog.statusOf(WorkspaceDesktop.NONE, true, true, true),
        )
        assertEquals(
            WorkspaceDesktopStatus.ROOTFS_MISSING,
            WorkspaceDesktopCatalog.statusOf(desktop, rootfsReady = false, installed = false, running = false),
        )
        assertEquals(
            WorkspaceDesktopStatus.NOT_INSTALLED,
            WorkspaceDesktopCatalog.statusOf(desktop, rootfsReady = true, installed = false, running = false),
        )
        assertEquals(
            WorkspaceDesktopStatus.INSTALLED,
            WorkspaceDesktopCatalog.statusOf(desktop, rootfsReady = true, installed = true, running = false),
        )
        assertEquals(
            WorkspaceDesktopStatus.RUNNING,
            WorkspaceDesktopCatalog.statusOf(desktop, rootfsReady = true, installed = true, running = true),
        )
    }

    @Test
    fun `statusOf treats missing probe as installed`() {
        // 自定义方案没有可探测的安装结果 (installed == null), 不该显示成「未安装」
        assertEquals(
            WorkspaceDesktopStatus.INSTALLED,
            WorkspaceDesktopCatalog.statusOf(
                WorkspaceDesktop.CUSTOM,
                rootfsReady = true,
                installed = null,
                running = false,
            ),
        )
        // 正在运行时, 即使探测说没装 (例如用户自己起的服务), 也以运行状态为准
        assertEquals(
            WorkspaceDesktopStatus.RUNNING,
            WorkspaceDesktopCatalog.statusOf(
                WorkspaceDesktop.CUSTOM,
                rootfsReady = true,
                installed = false,
                running = true,
            ),
        )
    }

    // ---------------------------------------------------------------- 方案生成

    @Test
    fun `none has no plan`() {
        assertNull(WorkspaceDesktopCatalog.plan(WorkspaceDesktop.NONE, WorkspacePackageManager.APK))
    }

    @Test
    fun `code-server plan downloads the official tarball inside the rootfs`() {
        val plan = WorkspaceDesktopCatalog.plan(
            WorkspaceDesktop.CODE_SERVER,
            WorkspacePackageManager.APK,
        )!!
        assertNotNull(plan.installScript)
        assertEquals(8081, plan.port)
        assertEquals("http://127.0.0.1:8081/", plan.url)
        assertNotNull(plan.sizeHintBytes)
        assertNotNull(plan.installMinutes)

        val install = plan.installScript!!
        assertTrue(install.contains("api.github.com/repos/coder/code-server/releases/latest"))
        assertTrue(install.contains("code-server-\$version-linux-\$asset.tar.gz"))
        assertTrue(install.contains("AIRKO_DESKTOP_DOWNLOAD_MIRROR"))
        assertTrue(install.contains("AIRKO_DESKTOP_VERSION"))
        // 架构按 uname 选择, 不是按 App 的 ABI 猜
        assertTrue(install.contains("uname -m"))
        // Alpine 是 musl, 必须装兼容层
        assertTrue(install.contains("gcompat"))
        // 装完的落点在 rootfs 的 $HOME 下
        assertTrue(install.contains("\$HOME/.airko/code-server"))
        assertTrue(install.contains("ln -sf"))

        // 启动: 只绑回环 + 无认证 + 关遥测; 日志重定向到固定路径
        val start = plan.startScript!!
        assertTrue(start.contains("nohup"))
        assertTrue(start.contains("--bind-addr 127.0.0.1:8081"))
        assertTrue(start.contains("--auth none"))
        assertTrue(start.contains(WorkspaceDesktopCatalog.LOG_PATH))
        assertFalse(start.contains("0.0.0.0"))
        assertFalse(start.contains("8080"))

        val stop = plan.stopScript!!
        assertTrue(stop.contains("pkill -f"))
        assertTrue(stop.contains("code-server"))

        val probe = plan.probeScript!!
        assertTrue(probe.contains("code-server --version"))
    }

    @Test
    fun `novnc install uses the distro package manager`() {
        val apk = WorkspaceDesktopCatalog.plan(WorkspaceDesktop.NOVNC, WorkspacePackageManager.APK)!!
        assertTrue(apk.installScript!!.contains("apk add --no-cache xvfb x11vnc fluxbox xterm novnc websockify"))

        val apt = WorkspaceDesktopCatalog.plan(WorkspaceDesktop.NOVNC, WorkspacePackageManager.APT)!!
        assertTrue(apt.installScript!!.contains("apt-get update"))
        assertTrue(
            apt.installScript!!.contains(
                "apt-get install -y --no-install-recommends xvfb x11vnc fluxbox xterm novnc websockify",
            ),
        )

        // Arch 官方仓库没有 novnc/websockify: pip + 官方 tarball 兜底
        val pacman = WorkspaceDesktopCatalog.plan(WorkspaceDesktop.NOVNC, WorkspacePackageManager.PACMAN)!!
        val pacmanInstall = pacman.installScript!!
        assertTrue(pacmanInstall.contains("pacman -Sy --noconfirm xorg-server-xvfb x11vnc fluxbox xterm"))
        assertTrue(pacmanInstall.contains("pip install"))
        assertTrue(pacmanInstall.contains("websockify"))
        assertTrue(pacmanInstall.contains("novnc/noVNC/archive/refs/tags/v\$noVNC_version.tar.gz"))
        assertTrue(pacmanInstall.contains("\$HOME/.airko/novnc"))

        // 探测不到包管理器: 明确报错, 不能拼一堆必然 command not found 的命令
        val unknown = WorkspaceDesktopCatalog.plan(WorkspaceDesktop.NOVNC, WorkspacePackageManager.UNKNOWN)!!
        assertTrue(unknown.installScript!!.contains("exit 1"))
        assertTrue(unknown.installScript!!.contains("no apk, apt-get, pacman or dnf"))
    }

    @Test
    fun `gnome and niri plans use vnc with per distro packages`() {
        val gnome = WorkspaceDesktopCatalog.plan(WorkspaceDesktop.GNOME, WorkspacePackageManager.APT)!!
        assertEquals(6080, gnome.port)
        assertEquals("http://127.0.0.1:6080/vnc.html?autoconnect=true&resize=scale", gnome.url)
        assertTrue(gnome.installScript!!.contains("gnome-shell"))
        // GNOME 49 起没有 X11 会话, 先查候选版本再决定是否下载
        assertTrue(gnome.installScript!!.contains("-ge 49"))
        assertTrue(gnome.installScript!!.contains("tigervnc-standalone-server"))
        assertTrue(gnome.installScript!!.contains("novnc"))
        assertTrue(gnome.installScript!!.contains("websockify"))
        // 直起 gnome-shell --x11 (gnome-session 在非 systemd 环境里起不来)
        assertTrue(gnome.startScript!!.contains("gnome-shell --x11"))
        // /run/systemd 存在会让 gnome-shell 去找 logind 然后崩, 每次启动都要清
        assertTrue(gnome.startScript!!.contains("rm -rf \"\$XDG_RUNTIME_DIR/systemd\""))
        // 没有系统总线时 accountsservice 会抛异常把 shell 打死
        assertTrue(gnome.startScript!!.contains("airko-system-bus.conf"))
        assertTrue(gnome.startScript!!.contains("dbus-send --system"))
        // own 与 user 属性必须分成两个 <allow>: 合并会被 dbus-daemon 拒载 (真机踩过)
        assertTrue(gnome.startScript!!.contains("<allow own=\"*\"/>"))
        assertTrue(gnome.startScript!!.contains("<allow user=\"*\"/>"))
        assertFalse(gnome.startScript!!.contains("own=\"*\" user=\"*\""))
        // 总线复核失败要明确退出, 不能把 shell 放出去崩
        assertTrue(gnome.startScript!!.contains("system bus is not reachable"))
        assertTrue(gnome.startScript!!.contains("dbus-launch --sh-syntax"))
        assertTrue(gnome.startScript!!.contains("-localhost"))
        assertTrue(gnome.stopScript!!.contains("gnome-shell"))
        assertTrue(gnome.stopScript!!.contains("airko-system-bus.conf"))

        // Fedora/Arch 的 gnome-shell 已 >=49: 直接明确报错而不是白下一趟
        val gnomeDnf = WorkspaceDesktopCatalog.plan(WorkspaceDesktop.GNOME, WorkspacePackageManager.DNF)!!
        assertTrue(gnomeDnf.installScript!!.contains("exit 1"))

        val niri = WorkspaceDesktopCatalog.plan(WorkspaceDesktop.NIRI, WorkspacePackageManager.PACMAN)!!
        assertTrue(niri.installScript!!.contains("cage"))
        assertTrue(niri.installScript!!.contains("niri wayvnc"))
        assertTrue(niri.installScript!!.contains("wlr-randr"))
        assertTrue(niri.installScript!!.contains("foot"))
        assertTrue(niri.installScript!!.contains("novnc/noVNC/archive"))
        assertTrue(niri.startScript!!.contains("WLR_BACKENDS=headless"))
        assertTrue(niri.startScript!!.contains("cage -- sleep infinity"))
        assertTrue(niri.startScript!!.contains("XDG_RUNTIME_DIR=/run"))
        assertTrue(niri.startScript!!.contains("wayvnc"))
        // 无头输出必须显式打开: disabled 的输出会让 screencopy 挂死
        assertTrue(niri.startScript!!.contains("--custom-mode 1280x800"))
        assertTrue(niri.startScript!!.contains("--on"))
        // 触屏上没有 Super 键, 进来要先有一个终端窗口
        assertTrue(niri.startScript!!.contains("foot"))
        assertTrue(niri.startScript!!.contains("127.0.0.1:6080"))
        assertTrue(niri.startScript!!.contains("127.0.0.1:5900"))
        assertTrue(niri.stopScript!!.contains("wayvnc"))
        assertTrue(niri.stopScript!!.contains("cage"))

        // Alpine 装不了 GNOME/niri：脚本要直接报错退出，而不是让包管理器乱报
        val gnomeApk = WorkspaceDesktopCatalog.plan(WorkspaceDesktop.GNOME, WorkspacePackageManager.APK)!!
        assertTrue(gnomeApk.installScript!!.contains("exit 1"))
        val niriApk = WorkspaceDesktopCatalog.plan(WorkspaceDesktop.NIRI, WorkspacePackageManager.APK)!!
        assertTrue(niriApk.installScript!!.contains("exit 1"))
    }

    @Test
    fun `vnc schemes start websockify through the fork wrapper`() {
        listOf(WorkspaceDesktop.NOVNC, WorkspaceDesktop.GNOME, WorkspaceDesktop.NIRI).forEach { desktop ->
            val plan = WorkspaceDesktopCatalog.plan(desktop, WorkspacePackageManager.APT)!!
            val start = plan.startScript!!
            // Python 3.14 的 forkserver 在 proot 里绑不上临时 socket, 必须强制 fork
            assertTrue("$desktop must write the wrapper", start.contains("\$HOME/.airko/airko-ws.py"))
            assertTrue("$desktop must force fork", start.contains("set_start_method(\"fork\", force=True)"))
            assertTrue("$desktop must use the real entrypoint", start.contains("websockify_init"))
            // 包装文件不能叫 websockify.py, 否则遮蔽同名包
            assertTrue("$desktop must remove the shadowing file", start.contains("rm -f \"\$HOME/.airko/websockify.py\""))
            assertTrue("$desktop must expose the web port", start.contains("127.0.0.1:6080"))
            assertTrue("$desktop must forward to the raw vnc port", start.contains("127.0.0.1:5900"))
            assertEquals(
                "http://127.0.0.1:6080/vnc.html?autoconnect=true&resize=scale",
                plan.url,
            )
        }
    }

    @Test
    fun `novnc plan uses the web socket port and binds loopback only`() {
        val plan = WorkspaceDesktopCatalog.plan(WorkspaceDesktop.NOVNC, WorkspacePackageManager.APK)!!
        assertEquals(6080, plan.port)
        assertEquals("http://127.0.0.1:6080/vnc.html?autoconnect=true&resize=scale", plan.url)

        val start = plan.startScript!!
        assertTrue(start.contains("Xvfb :99"))
        assertTrue(start.contains("-nolisten tcp"))
        assertTrue(start.contains("x11vnc"))
        assertTrue(start.contains("-localhost"))
        assertTrue(start.contains("-noshm"))
        // 自动开一个终端窗口, 空桌面看起来像坏了
        assertTrue(start.contains("xterm"))
        assertTrue(start.contains("airko-ws.py"))
        assertTrue(start.contains("127.0.0.1:6080"))
        assertTrue(start.contains("127.0.0.1:5900"))
        assertTrue(start.contains(WorkspaceDesktopCatalog.LOG_PATH))
        assertFalse(start.contains("0.0.0.0"))

        assertTrue(plan.probeScript!!.contains("/usr/share/novnc/vnc.html"))
        assertTrue(plan.stopScript!!.contains("airko-ws.py"))
    }

    @Test
    fun `jupyter install and start use loopback without token`() {
        val apk = WorkspaceDesktopCatalog.plan(WorkspaceDesktop.JUPYTER, WorkspacePackageManager.APK)!!
        assertTrue(apk.installScript!!.contains("apk add --no-cache python3 py3-pip"))
        assertTrue(apk.installScript!!.contains("--break-system-packages"))

        val apt = WorkspaceDesktopCatalog.plan(WorkspaceDesktop.JUPYTER, WorkspacePackageManager.APT)!!
        assertTrue(apt.installScript!!.contains("python3-pip"))
        assertTrue(apt.installScript!!.contains("jupyterlab"))

        val pacman = WorkspaceDesktopCatalog.plan(WorkspaceDesktop.JUPYTER, WorkspacePackageManager.PACMAN)!!
        assertTrue(pacman.installScript!!.contains("pacman -Sy --noconfirm python python-pip"))

        val unknown = WorkspaceDesktopCatalog.plan(WorkspaceDesktop.JUPYTER, WorkspacePackageManager.UNKNOWN)!!
        assertTrue(unknown.installScript!!.contains("exit 1"))

        val plan = apk
        assertEquals(8888, plan.port)
        val start = plan.startScript!!
        assertTrue(start.contains("--ServerApp.ip=127.0.0.1"))
        assertTrue(start.contains("--ServerApp.port=8888"))
        assertTrue(start.contains("--ServerApp.token="))
        assertTrue(start.contains("--allow-root"))
        assertTrue(start.contains(WorkspaceDesktopCatalog.LOG_PATH))
        assertFalse(start.contains("0.0.0.0"))
    }

    @Test
    fun `custom plan wraps the user command and keeps the configured port`() {
        val plan = WorkspaceDesktopCatalog.plan(
            WorkspaceDesktop.CUSTOM,
            WorkspacePackageManager.UNKNOWN,
            customCommand = "python3 -m http.server '9000'",
            customPort = 9000,
        )!!
        // 自定义方案没有 App 能代劳的安装动作
        assertNull(plan.installScript)
        assertNull(plan.probeScript)
        assertNull(plan.sizeHintBytes)
        assertEquals(9000, plan.port)
        assertEquals("http://127.0.0.1:9000/", plan.url)
        assertTrue(plan.startScript!!.startsWith("nohup sh -c 'python3 -m http.server '\\''9000'\\'''"))
        assertTrue(plan.stopScript!!.contains("pkill -f"))
    }

    @Test
    fun `custom plan without command or port degrades safely`() {
        val empty = WorkspaceDesktopCatalog.plan(WorkspaceDesktop.CUSTOM, WorkspacePackageManager.APK)!!
        assertNull(empty.startScript)
        assertNull(empty.stopScript)
        assertEquals(0, empty.port)
        assertNull(empty.url)

        val badPort = WorkspaceDesktopCatalog.plan(
            WorkspaceDesktop.CUSTOM,
            WorkspacePackageManager.APK,
            customCommand = "  nc -l 1234  ",
            customPort = 70000,
        )!!
        assertEquals(0, badPort.port)
        assertNull(badPort.url)
        // 命令两边的空白要去掉, 否则 pkill -f 匹配不上包装后的进程
        assertTrue(badPort.startScript!!.endsWith("> /tmp/airko-desktop.log 2>&1 &"))
        assertTrue(badPort.startScript!!.contains("'nc -l 1234'"))
    }

    @Test
    fun `log paths stay inside the rootfs tmp`() {
        assertEquals("/tmp/airko-desktop.log", WorkspaceDesktopCatalog.LOG_PATH)
        assertEquals("/tmp/airko-desktop-install.log", WorkspaceDesktopCatalog.INSTALL_LOG_PATH)
        assertTrue(WorkspaceDesktopCatalog.LOG_TAIL_BYTES > 0)
    }
}
