package me.rerere.rikkahub.web.routes

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.headers
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.options
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocketRaw
import io.ktor.utils.io.copyTo
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.web.PortProxy
import me.rerere.rikkahub.web.dto.ErrorResponse
import java.util.concurrent.TimeUnit

private const val LOOPBACK = "127.0.0.1"

/**
 * 端口映射反代：`/p/{port}/…` → `http://127.0.0.1:{port}/…`（含 WebSocket）。
 *
 * 白名单来自设置里的端口映射表；不在表里的端口一律 403，避免 WebServer 被拿来扫本机端口。
 *
 * ⚠️ 这些路由**不走 App 自己的密码校验**：被代理的服务（code-server / Jupyter / noVNC）
 * 是独立进程，它的前端会自己发一堆子请求，带不上我们的 token。所以安全前提是
 * 「目标服务自己有认证」+「端口只映射用户明确要暴露的服务」；WebServer 开在局域网上时
 * 设置页会显示醒目提示。
 */
fun Route.portProxyRoutes(settingsStore: SettingsStore) {
    val client = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        install(WebSockets)
        engine {
            config {
                followRedirects(false)
                connectTimeout(10, TimeUnit.SECONDS)
                // 反代的服务可能是长连接（events / streaming），不能按默认读超时掐掉
                readTimeout(0, TimeUnit.SECONDS)
            }
        }
    }

    route("/p/{port}") {
        // 所有方法 + 整棵子树都转发：Ktor 的 route(path) 本身不接管子路径，
        // 所以尾部通配和空尾部各注册一次
        val handler: suspend RoutingContext.() -> Unit = { proxyHttp(call, settingsStore, client) }
        val tail = "/{path...}"
        get(handler)
        get(tail, handler)
        post(handler)
        post(tail, handler)
        put(handler)
        put(tail, handler)
        patch(handler)
        patch(tail, handler)
        delete(handler)
        delete(tail, handler)
        head(handler)
        head(tail, handler)
        options(handler)
        options(tail, handler)
    }

    // WebSocket 透传：原样搬帧，不做内容解析
    webSocketRaw("/p/{port}/{path...}") {
        val server = this
        val port = resolveAllowedPort(settingsStore, call.parameters["port"])
        if (port == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Port is not in the mapping list"))
            return@webSocketRaw
        }
        val segments = call.parameters.getAll("path")?.joinToString("/").orEmpty()
        val query = call.request.queryString().takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()

        client.webSocket("ws://$LOOPBACK:$port/$segments$query") {
            val upstream = launch {
                for (frame in server.incoming) {
                    runCatching { send(frame) }
                }
            }
            val downstream = launch {
                for (frame in incoming) {
                    runCatching { server.send(frame) }
                }
            }
            upstream.join()
            downstream.cancel()
        }
    }

    webSocketRaw("/p/{port}") {
        val server = this
        val port = resolveAllowedPort(settingsStore, call.parameters["port"])
        if (port == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Port is not in the mapping list"))
            return@webSocketRaw
        }
        val query = call.request.queryString().takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()

        client.webSocket("ws://$LOOPBACK:$port/$query") {
            val upstream = launch {
                for (frame in server.incoming) {
                    runCatching { send(frame) }
                }
            }
            val downstream = launch {
                for (frame in incoming) {
                    runCatching { server.send(frame) }
                }
            }
            upstream.join()
            downstream.cancel()
        }
    }
}

private suspend fun proxyHttp(call: ApplicationCall, settingsStore: SettingsStore, client: HttpClient) {
    val port = resolveAllowedPort(settingsStore, call.parameters["port"])
    if (port == null) {
        call.respond(
            HttpStatusCode.Forbidden,
            ErrorResponse(
                "Port is not in the mapping list. Add it in Settings → Web Server → Port mappings.",
                HttpStatusCode.Forbidden.value,
            ),
        )
        return
    }

    // 升级请求由上面的 webSocketRaw 路由处理；走到这里说明路由没匹配上，明确报错而不是挂死
    if (call.request.headers[HttpHeaders.Upgrade]?.contains("websocket", ignoreCase = true) == true) {
        call.respond(
            HttpStatusCode.NotImplemented,
            ErrorResponse("WebSocket upgrade was not routed; reload the page.", HttpStatusCode.NotImplemented.value),
        )
        return
    }

    val targetPath = PortProxy.targetPath(port, call.request.path())
    val query = call.request.queryString().takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
    val target = "http://$LOOPBACK:$port$targetPath$query"

    client.prepareRequest(target) {
        method = call.request.httpMethod
        headers {
            call.request.headers.entries()
                .filter { PortProxy.shouldForwardRequestHeader(it.key) }
                .forEach { entry -> entry.value.forEach { append(entry.key, it) } }
        }
        setBody(call.request.receiveChannel())
    }.execute { response ->
        response.headers.entries()
            .filter { PortProxy.shouldForwardResponseHeader(it.key) }
            .forEach { entry -> entry.value.forEach { call.response.headers.append(entry.key, it) } }
        call.respondBytesWriter(
            status = response.status,
            contentType = response.contentType(),
        ) {
            response.bodyAsChannel().copyTo(this)
        }
    }
}

private fun resolveAllowedPort(settingsStore: SettingsStore, raw: String?): Int? {
    val port = raw?.toIntOrNull() ?: return null
    if (!PortProxy.isValidPort(port)) return null
    val mappings = settingsStore.settingsFlow.value.webServerPortMappings
    return port.takeIf { PortProxy.isAllowedPort(mappings, it) }
}
