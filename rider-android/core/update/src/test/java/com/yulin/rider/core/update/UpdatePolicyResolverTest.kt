package com.yulin.rider.core.update

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
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

class UpdatePromptPolicyTest {
    @Test
    fun hidesOptionalDialogWhileOnDuty() {
        assertFalse(
            UpdatePromptPolicy.shouldShowOptionalDialog(
                onDuty = true,
                policy = UpdatePolicyResolver.OPTIONAL,
                showOptional = true,
            )
        )
    }

    @Test
    fun showsOptionalDialogWhenOffDuty() {
        assertTrue(
            UpdatePromptPolicy.shouldShowOptionalDialog(
                onDuty = false,
                policy = UpdatePolicyResolver.OPTIONAL,
                showOptional = true,
            )
        )
        assertFalse(
            UpdatePromptPolicy.shouldShowOptionalDialog(
                onDuty = false,
                policy = UpdatePolicyResolver.OPTIONAL,
                showOptional = false,
            )
        )
    }
}

class RangePlanTest {
    @Test
    fun splitsWholeFileIntoFourContiguousSpans() {
        val spans = RangePlan.spans(1_000L, 4)
        assertEquals(4, spans.size)
        assertEquals(0L, spans.first().start)
        assertEquals(999L, spans.last().endInclusive)
        assertEquals(1_000L, spans.sumOf { it.length })
        spans.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endInclusive + 1L, right.start)
        }
    }

    @Test
    fun usesSingleSpanWhenAskedForOnePart() {
        val spans = RangePlan.spans(1024L, 1)
        assertEquals(1, spans.size)
        assertEquals(1024L, spans.single().length)
    }

    @Test
    fun parallelRequiresBytesRangeAndLargeFile() {
        assertTrue(RangePlan.supportsParallel("bytes", RangePlan.MIN_PARALLEL_BYTES))
        assertFalse(RangePlan.supportsParallel("none", RangePlan.MIN_PARALLEL_BYTES))
        assertFalse(RangePlan.supportsParallel("bytes", RangePlan.MIN_PARALLEL_BYTES - 1))
    }
}

class ApkDownloadClientTest {
    @Test
    fun parsesContentRangeTotal() {
        assertEquals(104498438L, ApkDownloadClient.contentRangeTotal("bytes 0-0/104498438"))
        assertEquals(null, ApkDownloadClient.contentRangeTotal("bytes 0-0/*"))
    }

    @Test
    fun downloadsParallelRangesAndMatchesChecksum() = runBlocking {
        val payload = ByteArray(RangePlan.MIN_PARALLEL_BYTES.toInt()) { index -> (index % 251).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        val sha = digest.joinToString("") { "%02x".format(it) }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range") ?: return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Accept-Ranges", "bytes")
                    .setBody(Buffer().write(payload))
                val match = Regex("bytes=(\\d+)-(\\d+)").find(range)
                    ?: return MockResponse().setResponseCode(400)
                val start = match.groupValues[1].toInt()
                val endInclusive = match.groupValues[2].toInt()
                val slice = payload.copyOfRange(start, endInclusive + 1)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes $start-$endInclusive/${payload.size}")
                    .setBody(Buffer().write(slice))
            }
        }
        server.start()
        val target = File.createTempFile("rider-apk", ".bin")
        target.delete()
        try {
            val client = ApkDownloadClient(ApkDownloadClient.createIsolatedClient())
            client.download(server.url("/app.apk").toString(), target, sha) { _, _ -> }
            assertEquals(payload.size.toLong(), target.length())
            assertTrue(ApkChecksum.matches(target, sha))
        } finally {
            target.delete()
            server.shutdown()
        }
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
