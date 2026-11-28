package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshHostTest {
    @Test
    fun address_uses_default_port_format() {
        assertEquals("me@host", SshHost(user = "me", host = "host").address)
        assertEquals("me@host:2222", SshHost(user = "me", host = "host", port = 2222).address)
    }

    @Test
    fun display_name_falls_back_to_address() {
        val host = SshHost(user = "me", host = "host")
        assertEquals("me@host", host.displayName)
        assertEquals("my box", host.copy(name = "my box").displayName)
    }

    @Test
    fun forwarding_requires_both_ports() {
        assertFalse(SshHost(forwardLocalPort = 18080).forwardingEnabled)
        assertFalse(SshHost(forwardRemotePort = 8000).forwardingEnabled)
        assertTrue(SshHost(forwardLocalPort = 18080, forwardRemotePort = 8000).forwardingEnabled)
        assertFalse(SshHost(forwardLocalPort = 70000, forwardRemotePort = 8000).forwardingEnabled)
    }

    @Test
    fun trust_requires_fingerprint_and_can_be_cleared() {
        val host = SshHost(hostKeyType = "ssh-ed25519", hostKeyFingerprint = "SHA256:abc")
        assertTrue(host.trusted)
        assertFalse(host.withoutPinnedKey().trusted)
        val pinned = host.withoutPinnedKey().withKeyPinned("ssh-ed25519", "SHA256:def")
        assertEquals("SHA256:def", pinned.hostKeyFingerprint)
    }

    @Test
    fun secret_is_configured_only_with_credentials() {
        assertFalse(SshSecret().isConfigured)
        assertTrue(SshSecret(password = "pw").isConfigured)
        assertTrue(SshSecret(privateKeyPem = "-----BEGIN-----").isConfigured)
    }
}
