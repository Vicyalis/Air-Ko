package me.rerere.rikkahub.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 完整性校验的「安全闸门」。
 *
 * 安装前比对 sha256 是防篡改的第一道关：算出来的哈希必须与 index 声明逐字节一致，
 * 声明缺失时由调用方决定放行策略（这里匹配一律返回 false，宁严勿松）。
 */
class RecommendSha256Test {
    @Test
    fun `sha256 matches known vectors`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun `sha256 output is lowercase hex of correct length for unicode content`() {
        val bytes = "更新日志".toByteArray(Charsets.UTF_8)

        assertEquals(64, sha256Hex(bytes).length)
        assertTrue(sha256Hex(bytes).all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(matchesSha256(bytes, sha256Hex(bytes)))
    }

    @Test
    fun `normalize sha256 accepts messy but valid input`() {
        val canonical = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

        assertEquals(canonical, normalizeSha256(canonical))
        assertEquals(canonical, normalizeSha256("  BA7816BF8F01CFEA414140DE5D AE2223B00361A396177A9CB410FF61F20015AD  "))
        assertEquals(canonical, normalizeSha256(canonical.uppercase()))
    }

    @Test
    fun `normalize sha256 rejects malformed input`() {
        assertNull(normalizeSha256(""))
        assertNull(normalizeSha256("   "))
        assertNull(normalizeSha256("ba7816bf"))
        assertNull(normalizeSha256("a".repeat(63)))
        assertNull(normalizeSha256("a".repeat(65)))
        assertNull(normalizeSha256("g".repeat(64)))
        assertNull(normalizeSha256("z".repeat(64)))
    }

    @Test
    fun `matches sha256 compares canonical forms`() {
        val payload = "abc".toByteArray(Charsets.UTF_8)

        assertTrue(matchesSha256(payload, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertTrue(matchesSha256(payload, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
        assertFalse(matchesSha256(payload, "0".repeat(64)))
        assertFalse(matchesSha256(payload, ""))
        assertFalse(matchesSha256(payload, "not-a-hash"))
    }

    @Test
    fun `matches sha256 rejects tampered payload`() {
        val original = "trusted content".toByteArray(Charsets.UTF_8)
        val tampered = "trusted content!".toByteArray(Charsets.UTF_8)
        val expected = sha256Hex(original)

        assertTrue(matchesSha256(original, expected))
        assertFalse(matchesSha256(tampered, expected))
    }
}
