package com.yulin.rider.core.location

import com.yulin.rider.core.model.LocationBatchResponse
import com.yulin.rider.core.model.LocationPoint
import com.yulin.rider.core.model.RejectReason
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationUploaderBatchTest {

    @Test
    fun batchSizeMatchesServerLimit() {
        assertEquals(100, LocationUploader.MAX_BATCH_SIZE)
        assertEquals(10, LocationUploader.DELIVERING_INTERVAL_SECONDS)
        assertEquals(20, LocationUploader.BASE_INTERVAL_SECONDS)
    }

    @Test
    fun batchLimitRejectsAreNotMarkedUploaded() {
        val context = LocationCaptureContext(shiftId = 1, taskId = 9, waveId = 3)
        val point = LocationPoint(lat = 38.38, lng = 106.09, locatedAt = "2026-08-11T15:40:00")
        val batch = listOf(
            BufferedPoint(1, point, context),
            BufferedPoint(2, point, context),
            BufferedPoint(3, point, context),
        )
        val response = LocationBatchResponse(
            accepted = 2,
            rejected = 1,
            rejectReasons = listOf(RejectReason(index = 2, reason = "BATCH_LIMIT")),
        )

        assertEquals(listOf(1L, 2L), LocationUploader.idsToMarkUploaded(batch, response))
        assertEquals(listOf(1L, 2L, 3L), LocationUploader.idsToMarkUploaded(batch, null))
    }
}
