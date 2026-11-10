package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolsApprovalTest {
    @Test
    fun `workspace tools are allowed by default`() {
        assertFalse(resolveWorkspaceToolApproval("workspace_read_file", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_write_file", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_edit_file", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_shell", emptyMap()))
    }

    @Test
    fun `individual override can require approval`() {
        assertTrue(
            resolveWorkspaceToolApproval(
                name = "workspace_shell",
                overrides = mapOf("workspace_shell" to true),
            )
        )
    }

    @Test
    fun `allow all bypasses every workspace approval`() {
        val overrides = mapOf(
            "workspace_read_file" to true,
            "workspace_write_file" to true,
            "workspace_edit_file" to true,
            "workspace_shell" to true,
        )
        overrides.keys.forEach { name ->
            assertFalse(resolveWorkspaceToolApproval(name, overrides, allowAll = true))
        }
    }
}
