package me.rerere.rikkahub.web

import me.rerere.rikkahub.data.datastore.WebServerPortMapping
import java.net.URLEncoder

/**
 * 网页端口映射（`/p/{port}/` 反代）的纯逻辑部分。
 *
 * 只有出现在映射表里的端口才允许被代理：内置 WebServer 可能开在局域网上，
 * 不加白名单等于把设备本机所有端口都暴露出去。
 */
object PortProxy {
    const val PREFIX = "/p"

    /** 内置桌面方案用的端口，界面上给用户默认建议 */
    const val SUGGESTED_CODE_SERVER_PORT = 8081
    const val SUGGESTED_NOVNC_PORT = 6080
    const val SUGGESTED_JUPYTER_PORT = 8888

    fun isValidPort(port: Int): Boolean = port in 1024..65535

    /** 允许被代理的端口：在映射表里且已启用 */
    fun isAllowedPort(mappings: List<WebServerPortMapping>, port: Int): Boolean =
        mappings.any { it.enabled && it.port == port }

    /** `/p/{port}` 前缀（不带结尾斜杠） */
    fun prefixFor(port: Int): String = "$PREFIX/$port"

    /** 把 `/p/{port}/a/b` 还原成目标服务要的 `/a/b` */
    fun targetPath(port: Int, rawPath: String): String {
        val stripped = rawPath
            .removePrefix(prefixFor(port))
            .removePrefix("/")
        return "/$stripped"
    }

    /** 界面展示/复制的完整地址 */
    fun urlFor(baseUrl: String, port: Int): String = "${baseUrl.trimEnd('/')}${prefixFor(port)}/"

    /** 带 access_token 的地址：JWT 开着时网页端要用它换 token（query 形式认证） */
    fun urlWithToken(baseUrl: String, port: Int, accessToken: String?): String {
        val url = urlFor(baseUrl, port)
        if (accessToken.isNullOrBlank()) return url
        @Suppress("DEPRECATION")
        return "$url?access_token=${URLEncoder.encode(accessToken, "UTF-8")}"
    }

    /**
     * 逐跳头（Connection/Upgrade/…）由两端各自的 HTTP 栈管理，不能原样转发；
     * Host 必须由目标服务看到（有些服务拿它做校验）；Accept-Encoding 交给客户端引擎，
     * 免得拿到压缩字节又解不开。
     */
    private val droppedRequestHeaders = setOf(
        "host",
        "connection",
        "upgrade",
        "keep-alive",
        "proxy-connection",
        "transfer-encoding",
        "te",
        "trailer",
        "content-length",
        "accept-encoding",
    )

    fun shouldForwardRequestHeader(name: String): Boolean =
        name.lowercase() !in droppedRequestHeaders

    /** 响应侧同理：这两类头由 respond 自己写 */
    private val droppedResponseHeaders = setOf(
        "connection",
        "keep-alive",
        "transfer-encoding",
        "upgrade",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "content-length",
        "content-type",
    )

    fun shouldForwardResponseHeader(name: String): Boolean =
        name.lowercase() !in droppedResponseHeaders
}
