package me.rerere.workspace

/**
 * rootfs 下载源(镜像站)的稳定标识; 具体文案在 app 层按语言映射, 这里不存文案。
 */
enum class RootfsMirror {
    TUNA,
    USTC,
    ALIYUN,
    OFFICIAL,
}

/**
 * 发行版资料页链接类型。
 */
enum class RootfsLinkKind {
    WEBSITE,
    DOCS,
    BROWSE,

    /** 软件商店（默认星火商店；没有星火支持的发行版用各自的原生商店） */
    STORE,
}

data class RootfsDistroLink(
    val kind: RootfsLinkKind,
    val url: String,
)

/**
 * @param urlTemplate 以 {arch} 作为架构占位符, 由 [RootfsDistroCatalog.urlFor] 按 ABI 替换
 */
data class RootfsDistroSource(
    val mirror: RootfsMirror,
    val urlTemplate: String,
)

/**
 * @param id 稳定标识, 介绍文案在 app 层按 id 映射
 * @param sizeHintBytes arm64 下的实测体积(来自 Content-Length), null 表示未知
 * @param recommended 是否为默认推荐项(体积/速度最适合 proot)
 * @param archMap Android ABI -> 上游架构名; 未收录的 ABI 表示该发行版没有对应镜像
 */
data class RootfsDistro(
    val id: String,
    val name: String,
    val sizeHintBytes: Long?,
    val recommended: Boolean,
    val archMap: Map<String, String>,
    val sources: List<RootfsDistroSource>,
    val links: List<RootfsDistroLink>,
)

/**
 * 推荐 rootfs 目录。
 *
 * 全部地址在 2026-10 实测可用(Range 请求返回 206 且体积与上游一致), 不要凭记忆写版本号:
 * Alpine 版本号出现在文件名里, 升级时需要同步 [ALPINE_VERSION] 与各镜像路径。
 * Alpine/Ubuntu 的镜像站都完整同步上游目录树, 因此四个源共用同一路径结构, 只有官方源域名不同。
 */
object RootfsDistroCatalog {
    const val ID_ALPINE = "alpine"
    const val ID_UBUNTU = "ubuntu"
    const val ID_DEBIAN = "debian"
    const val ID_ARCH = "arch"
    const val ID_FEDORA = "fedora"
    const val ID_NIX = "nix"

    private const val ALPINE_VERSION = "3.24.2"
    private const val UBUNTU_VERSION = "24.04.5"
    private const val UBUNTU_SERIES = "24.04"
    private const val FEDORA_VERSION = "44"
    private const val FEDORA_POINT = "1.7"
    private const val ARCH_PLACEHOLDER = "{arch}"

    // Android 侧只可能出现这四种 ABI; 未收录时 UI 会提示"该架构无官方镜像"
    private val ALPINE_ARCH = mapOf(
        "arm64-v8a" to "aarch64",
        "armeabi-v7a" to "armv7",
        "x86_64" to "x86_64",
        "x86" to "x86",
    )
    private val UBUNTU_ARCH = mapOf(
        "arm64-v8a" to "arm64",
        "armeabi-v7a" to "armhf",
        "x86_64" to "amd64",
    )
    private val DEBIAN_ARCH = mapOf(
        "arm64-v8a" to "arm64",
        "x86_64" to "amd64",
    )
    private val ARCH_ARCH = mapOf(
        "arm64-v8a" to "aarch64",
        "armeabi-v7a" to "armv7",
    )

    // Fedora 官方只发布 OCI 容器归档（aarch64 / x86_64）
    private val FEDORA_ARCH = mapOf(
        "arm64-v8a" to "aarch64",
        "x86_64" to "x86_64",
    )

    // NixOS-WSL 的 rootfs：arm64 是 nixos.aarch64.wsl，x86_64 是 nixos.wsl（空后缀）
    private val NIX_ARCH = mapOf(
        "arm64-v8a" to "aarch64",
        "x86_64" to "",
    )

    private fun alpineUrl(host: String) =
        "https://$host/alpine/latest-stable/releases/$ARCH_PLACEHOLDER/alpine-minirootfs-$ALPINE_VERSION-$ARCH_PLACEHOLDER.tar.gz"

    private fun ubuntuUrl(host: String) =
        "https://$host/ubuntu-cdimage/ubuntu-base/releases/$UBUNTU_SERIES/release/ubuntu-base-$UBUNTU_VERSION-base-$ARCH_PLACEHOLDER.tar.gz"

    /** Arch Linux ARM 的 rootfs（只有 aarch64/armv7）。 */
    private fun archUrl(host: String) =
        "https://$host/archlinuxarm/os/ArchLinuxARM-$ARCH_PLACEHOLDER-latest.tar.gz"

    /** Fedora 的 OCI 容器归档（安装时会把 layers 解出来当 rootfs）。 */
    private fun fedoraUrl(prefix: String) =
        "${prefix}releases/$FEDORA_VERSION/Container/$ARCH_PLACEHOLDER/images/" +
            "Fedora-Container-Base-Generic-$FEDORA_VERSION-$FEDORA_POINT.$ARCH_PLACEHOLDER.oci.tar.xz"

    /** NixOS-WSL 的 rootfs（x86_64 的文件名没有架构后缀，靠 urlFor 里的 ".." 归一化处理）。 */
    private fun nixUrl(host: String) =
        "https://$host/nix-community/NixOS-WSL/releases/latest/download/nixos$ARCH_PLACEHOLDER.wsl"

    private val alpine = RootfsDistro(
        id = ID_ALPINE,
        name = "Alpine Linux $ALPINE_VERSION",
        sizeHintBytes = 4_028_030L,
        recommended = true,
        archMap = ALPINE_ARCH,
        sources = listOf(
            RootfsDistroSource(RootfsMirror.TUNA, alpineUrl("mirrors.tuna.tsinghua.edu.cn")),
            RootfsDistroSource(RootfsMirror.USTC, alpineUrl("mirrors.ustc.edu.cn")),
            RootfsDistroSource(RootfsMirror.ALIYUN, alpineUrl("mirrors.aliyun.com")),
            RootfsDistroSource(RootfsMirror.OFFICIAL, alpineUrl("dl-cdn.alpinelinux.org")),
        ),
        links = listOf(
            RootfsDistroLink(RootfsLinkKind.WEBSITE, "https://alpinelinux.org/"),
            RootfsDistroLink(
                RootfsLinkKind.BROWSE,
                "https://mirrors.tuna.tsinghua.edu.cn/alpine/latest-stable/releases/",
            ),
        ),
    )

    private val ubuntu = RootfsDistro(
        id = ID_UBUNTU,
        name = "Ubuntu $UBUNTU_VERSION Base",
        sizeHintBytes = 29_936_675L,
        recommended = false,
        archMap = UBUNTU_ARCH,
        sources = listOf(
            RootfsDistroSource(RootfsMirror.TUNA, ubuntuUrl("mirrors.tuna.tsinghua.edu.cn")),
            RootfsDistroSource(RootfsMirror.USTC, ubuntuUrl("mirrors.ustc.edu.cn")),
            RootfsDistroSource(RootfsMirror.ALIYUN, ubuntuUrl("mirrors.aliyun.com")),
            RootfsDistroSource(
                RootfsMirror.OFFICIAL,
                "https://cdimage.ubuntu.com/ubuntu-base/releases/$UBUNTU_SERIES/release/" +
                    "ubuntu-base-$UBUNTU_VERSION-base-$ARCH_PLACEHOLDER.tar.gz",
            ),
        ),
        links = listOf(
            RootfsDistroLink(RootfsLinkKind.WEBSITE, "https://ubuntu.com/"),
            RootfsDistroLink(
                RootfsLinkKind.BROWSE,
                "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/$UBUNTU_SERIES/release/",
            ),
        ),
    )

    // Debian 官方没有提供 lxc-images/debuerreotype 那样的静态 rootfs 地址(路径带时间戳或已 404),
    // 这里用 cloud 镜像的 nocloud 变体: 地址稳定、可直接解包, 但包含内核, 体积约为 Alpine 的 60 倍。
    private val debian = RootfsDistro(
        id = ID_DEBIAN,
        name = "Debian 12 (bookworm)",
        sizeHintBytes = 247_150_864L,
        recommended = false,
        archMap = DEBIAN_ARCH,
        sources = listOf(
            RootfsDistroSource(
                RootfsMirror.OFFICIAL,
                "https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-nocloud-$ARCH_PLACEHOLDER.tar.xz",
            ),
        ),
        links = listOf(
            RootfsDistroLink(RootfsLinkKind.WEBSITE, "https://www.debian.org/"),
            RootfsDistroLink(RootfsLinkKind.BROWSE, "https://cloud.debian.org/images/cloud/bookworm/latest/"),
        ),
    )

    // Arch Linux ARM 的滚动 rootfs（只有 aarch64/armv7；体积较大）
    private val arch = RootfsDistro(
        id = ID_ARCH,
        name = "Arch Linux ARM",
        sizeHintBytes = 829_367_415L,
        recommended = false,
        archMap = ARCH_ARCH,
        sources = listOf(
            RootfsDistroSource(RootfsMirror.TUNA, archUrl("mirrors.tuna.tsinghua.edu.cn")),
            RootfsDistroSource(RootfsMirror.USTC, archUrl("mirrors.ustc.edu.cn")),
        ),
        links = listOf(
            RootfsDistroLink(RootfsLinkKind.WEBSITE, "https://archlinuxarm.org/"),
            RootfsDistroLink(RootfsLinkKind.DOCS, "https://archlinuxarm.org/platforms/"),
            RootfsDistroLink(
                RootfsLinkKind.BROWSE,
                "https://mirrors.tuna.tsinghua.edu.cn/archlinuxarm/os/",
            ),
            RootfsDistroLink(RootfsLinkKind.STORE, "https://aur.archlinux.org/"),
            RootfsDistroLink(RootfsLinkKind.STORE, "https://spark-app.store/"),
        ),
    )

    // Fedora 只发布 OCI 容器归档（安装时会解出 layers 当 rootfs，约 63MB 压缩）
    private val fedora = RootfsDistro(
        id = ID_FEDORA,
        name = "Fedora $FEDORA_VERSION (Container Base)",
        sizeHintBytes = 66_000_000L,
        recommended = false,
        archMap = FEDORA_ARCH,
        sources = listOf(
            RootfsDistroSource(
                RootfsMirror.TUNA,
                fedoraUrl("https://mirrors.tuna.tsinghua.edu.cn/fedora/"),
            ),
            RootfsDistroSource(
                RootfsMirror.OFFICIAL,
                fedoraUrl("https://download.fedoraproject.org/pub/fedora/linux/"),
            ),
        ),
        links = listOf(
            RootfsDistroLink(RootfsLinkKind.WEBSITE, "https://fedoraproject.org/"),
            RootfsDistroLink(
                RootfsLinkKind.BROWSE,
                "https://download.fedoraproject.org/pub/fedora/linux/releases/$FEDORA_VERSION/Container/",
            ),
            RootfsDistroLink(RootfsLinkKind.STORE, "https://flathub.org/"),
        ),
    )

    // NixOS：用 NixOS-WSL 的 rootfs（latest 地址稳定；x86_64 文件名无架构后缀）
    private val nix = RootfsDistro(
        id = ID_NIX,
        name = "NixOS (WSL rootfs)",
        sizeHintBytes = 550 * 1024 * 1024L,
        recommended = false,
        archMap = NIX_ARCH,
        sources = listOf(
            RootfsDistroSource(RootfsMirror.OFFICIAL, nixUrl("github.com")),
        ),
        links = listOf(
            RootfsDistroLink(RootfsLinkKind.WEBSITE, "https://nixos.org/"),
            RootfsDistroLink(RootfsLinkKind.DOCS, "https://nixos.org/manual/nixos/stable/"),
            RootfsDistroLink(
                RootfsLinkKind.BROWSE,
                "https://github.com/nix-community/NixOS-WSL/releases/latest",
            ),
            RootfsDistroLink(RootfsLinkKind.STORE, "https://search.nixos.org/packages"),
        ),
    )

    /** 展示顺序: 推荐项在最前 */
    val entries: List<RootfsDistro> = listOf(alpine, ubuntu, debian, arch, fedora, nix)

    fun find(id: String): RootfsDistro? = entries.firstOrNull { it.id == id }

    /** 设备 ABI 对应的上游架构名, null 表示该发行版没有这个架构的镜像 */
    fun archFor(distro: RootfsDistro, abi: String): String? = distro.archMap[abi]

    /** 按 ABI 展开某个源的下载地址 */
    fun urlFor(distro: RootfsDistro, mirror: RootfsMirror, abi: String): String? {
        val arch = archFor(distro, abi) ?: return null
        val source = distro.sources.firstOrNull { it.mirror == mirror } ?: return null
        // arch 为空串时（如 NixOS x86_64）会出现 "nixos..wsl"，归一化掉重复的点
        return source.urlTemplate.replace(ARCH_PLACEHOLDER, arch).replace("..", ".")
    }

    /** 取该发行版在当前设备上的首选地址(按 sources 顺序) */
    fun preferredUrl(distro: RootfsDistro, abi: String): String? =
        distro.sources.firstNotNullOfOrNull { urlFor(distro, it.mirror, abi) }

    /**
     * 安装对话框的默认地址: 优先最小最快的 Alpine 国内镜像, 设备架构不受支持时退化为 Ubuntu,
     * 都没有镜像时返回空串(UI 会禁用安装按钮, 用户仍可从推荐列表里取官方源或自行粘贴地址)。
     */
    fun defaultUrl(abi: String): String =
        preferredUrl(alpine, abi) ?: preferredUrl(ubuntu, abi) ?: ""
}
