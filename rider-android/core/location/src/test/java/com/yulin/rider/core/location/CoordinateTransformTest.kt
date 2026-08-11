package com.yulin.rider.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateTransformTest {

    @Test
    fun `beijing WGS84 point is converted to GCJ02`() {
        val converted = CoordinateTransform.wgs84ToGcj02(39.908823, 116.397470)

        assertEquals(39.910226, converted.lat, 0.00002)
        assertEquals(116.403714, converted.lng, 0.00002)
        assertTrue(converted.lat != 39.908823)
    }

    @Test
    fun `coordinates outside mainland China remain WGS84`() {
        val converted = CoordinateTransform.wgs84ToGcj02(35.681236, 139.767125)

        assertEquals(35.681236, converted.lat, 0.0)
        assertEquals(139.767125, converted.lng, 0.0)
    }
}
