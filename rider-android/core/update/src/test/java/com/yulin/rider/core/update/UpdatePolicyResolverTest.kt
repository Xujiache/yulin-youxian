package com.yulin.rider.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UpdatePolicyResolverTest {
    @Test
    fun noneWhenCurrentIsLatestOrNewer() {
        assertEquals(UpdatePolicyResolver.NONE, UpdatePolicyResolver.resolve(10, 10, "OPTIONAL", 0))
        assertEquals(UpdatePolicyResolver.NONE, UpdatePolicyResolver.resolve(11, 10, "FORCE", 0))
        assertEquals(UpdatePolicyResolver.NONE, UpdatePolicyResolver.resolve(1, null, "FORCE", 0))
    }

    @Test
    fun optionalWhenBehindAndNotForced() {
        assertEquals(UpdatePolicyResolver.OPTIONAL, UpdatePolicyResolver.resolve(9, 10, "OPTIONAL", 0))
    }

    @Test
    fun forceWhenPolicyForceOrBelowMinSupported() {
        assertEquals(UpdatePolicyResolver.FORCE, UpdatePolicyResolver.resolve(9, 10, "FORCE", 0))
        assertEquals(UpdatePolicyResolver.FORCE, UpdatePolicyResolver.resolve(8, 10, "OPTIONAL", 9))
    }
}

class ApkChecksumTest {
    @Test
    fun hashesFileAndRejectsMismatch() {
        val file = File.createTempFile("apk", ".bin")
        file.writeBytes("rider-apk-bytes".toByteArray())
        val digest = MessageDigest.getInstance("SHA-256").digest("rider-apk-bytes".toByteArray())
        val expected = digest.joinToString("") { "%02x".format(it) }
        assertEquals(expected, ApkChecksum.sha256(file))
        assertTrue(ApkChecksum.matches(file, expected))
        assertFalse(ApkChecksum.matches(file, "ff".repeat(32)))
        file.delete()
    }
}
