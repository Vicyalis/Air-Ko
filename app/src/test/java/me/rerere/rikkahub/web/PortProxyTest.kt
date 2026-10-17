package me.rerere.rikkahub.web

import me.rerere.rikkahub.data.datastore.WebServerPortMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortProxyTest {

    @Test
    fun `only unprivileged ports are allowed`() {
        assertFalse(PortProxy.isValidPort(80))
        assertFalse(PortProxy.isValidPort(1023))
        assertTrue(PortProxy.isValidPort(1024))
        assertTrue(PortProxy.isValidPort(8081))
        assertTrue(PortProxy.isValidPort(65535))
        assertFalse(PortProxy.isValidPort(65536))
        assertFalse(PortProxy.isValidPort(0))
        assertFalse(PortProxy.isValidPort(-1))
    }

    @Test
    fun `allow list needs a mapping and the mapping must be enabled`() {
        val mappings = listOf(
            WebServerPortMapping(name = "code-server", port = 8081),
            WebServerPortMapping(name = "jupyter", port = 8888, enabled = false),
        )
        assertTrue(PortProxy.isAllowedPort(mappings, 8081))
        assertFalse("禁用的映射不能被代理", PortProxy.isAllowedPort(mappings, 8888))
        assertFalse("没配置的端口不能被代理", PortProxy.isAllowedPort(mappings, 8080))
        assertFalse(PortProxy.isAllowedPort(emptyList(), 8081))
    }

    @Test
    fun `target path strips the proxy prefix`() {
        assertEquals("/", PortProxy.targetPath(8081, "/p/8081"))
        assertEquals("/", PortProxy.targetPath(8081, "/p/8081/"))
        assertEquals("/vscode", PortProxy.targetPath(8081, "/p/8081/vscode"))
        assertEquals("/static/main.js", PortProxy.targetPath(8081, "/p/8081/static/main.js"))
        // 只是去掉开头的前缀，路径里剩下的原样保留
        assertEquals("/p/9090/x", PortProxy.targetPath(9090, "/p/9090/p/9090/x"))
    }

    @Test
    fun `mapping url keeps one slash between base and prefix`() {
        assertEquals("http://localhost:8080/p/8081/", PortProxy.urlFor("http://localhost:8080", 8081))
        assertEquals("http://localhost:8080/p/8081/", PortProxy.urlFor("http://localhost:8080/", 8081))
        assertEquals("http://192.168.1.5:8080/p/8888/", PortProxy.urlFor("http://192.168.1.5:8080", 8888))
    }

    @Test
    fun `token url encodes the token`() {
        assertEquals(
            "http://localhost:8080/p/8081/",
            PortProxy.urlWithToken("http://localhost:8080", 8081, null),
        )
        assertEquals(
            "http://localhost:8080/p/8081/?access_token=a.b%2Fc%3D",
            PortProxy.urlWithToken("http://localhost:8080", 8081, "a.b/c="),
        )
    }

    @Test
    fun `hop by hop request headers are dropped`() {
        listOf("Host", "Connection", "Upgrade", "Keep-Alive", "Transfer-Encoding", "Content-Length", "Accept-Encoding", "TE", "Trailer")
            .forEach { assertFalse(it, PortProxy.shouldForwardRequestHeader(it)) }

        listOf("Authorization", "Cookie", "User-Agent", "Accept", "Content-Type", "Origin", "X-Requested-With")
            .forEach { assertTrue(it, PortProxy.shouldForwardRequestHeader(it)) }
    }

    @Test
    fun `response keeps cookies and redirects but drops framing headers`() {
        listOf("Set-Cookie", "Location", "Cache-Control", "ETag", "Content-Encoding", "Last-Modified", "X-Custom")
            .forEach { assertTrue(it, PortProxy.shouldForwardResponseHeader(it)) }

        listOf("Connection", "Transfer-Encoding", "Content-Length", "Content-Type", "Upgrade", "Keep-Alive")
            .forEach { assertFalse(it, PortProxy.shouldForwardResponseHeader(it)) }
    }
}
