package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootfsLogTailTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val root = "test-workspace"

    @Test
    fun `missing file reports no log yet`() {
        assertNull(RootfsLogTail.read(File(tmp.root, "nope.log")))
        assertNull(RootfsLogTail.read(tmp.newFolder("dir")))
        assertNull(RootfsLogTail.read(tmp.newFile("empty.log"), maxBytes = 0))
    }

    @Test
    fun `empty file reads as empty text`() {
        assertEquals("", RootfsLogTail.read(tmp.newFile("empty.log")))
    }

    @Test
    fun `short file is returned whole`() {
        val file = tmp.newFile("short.log")
        file.writeText("installing code-server\n", Charsets.UTF_8)
        assertEquals("installing code-server\n", RootfsLogTail.read(file, maxBytes = 4096))
    }

    @Test
    fun `long file keeps only the tail and drops the torn first line`() {
        val file = tmp.newFile("long.log")
        file.writeText("line1\nline2\nline3\n", Charsets.UTF_8)
        // length = 18, 取末尾 10 字节会切在 line2 中间
        assertEquals("line3\n", RootfsLogTail.read(file, maxBytes = 10))
    }

    @Test
    fun `torn tail without newline yields empty text`() {
        val file = tmp.newFile("oneline.log")
        file.writeText("0123456789", Charsets.UTF_8)
        assertEquals("", RootfsLogTail.read(file, maxBytes = 4))
    }

    @Test
    fun `ansi control sequences are stripped`() {
        val file = tmp.newFile("color.log")
        file.writeText("\u001B[1;32mDone\u001B[0m \u001B]0;title\u0007ok", Charsets.UTF_8)
        assertEquals("Done ok", RootfsLogTail.read(file, maxBytes = 4096))
        assertEquals("plain", RootfsLogTail.stripAnsi("plain"))
    }

    @Test
    fun `reads the tail through the workspace rootfs path`() {
        val manager = WorkspaceManager(baseDir = tmp.newFolder("workspaces"))
            .also { it.ensureWorkspace(root) }
        val log = File(manager.linuxDir(root), "tmp/airko-desktop.log")
        log.parentFile!!.mkdirs()
        log.writeText("boot\nready\n", Charsets.UTF_8)

        assertEquals("boot\nready\n", manager.readRootfsTextTail(root, "/tmp/airko-desktop.log"))
        // 还没有日志时返回 null, UI 才能区分「没日志」与「空日志」
        assertNull(manager.readRootfsTextTail(root, "/tmp/airko-desktop-install.log"))
    }

    @Test
    fun `refuses to read outside the workspace root`() {
        val manager = WorkspaceManager(baseDir = tmp.newFolder("workspaces"))
            .also { it.ensureWorkspace(root) }
        File(manager.linuxDir(root), "tmp").mkdirs()
        File(tmp.root, "secret.log").writeText("secret")

        val thrown = runCatching {
            manager.readRootfsTextTail(root, "/tmp/../../../../secret.log")
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }
}
