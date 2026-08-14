package com.yulin.rider.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiderRoutesTest {

    @Test
    fun taskRoutesKeepTypedIdentifiers() {
        assertEquals("task_detail/42", RiderRoutes.taskDetail(42))
        assertEquals("wave_detail/9", RiderRoutes.waveDetail(9))
        assertEquals("deliver/42", RiderRoutes.deliver(42))
    }

    @Test
    fun protectedScreensAreNotBusinessDestinations() {
        assertTrue(RiderRoutes.LOGIN in RiderRoutes.PRE_BUSINESS_ROUTES)
        assertTrue(RiderRoutes.LOCATION_CONSENT in RiderRoutes.PRE_BUSINESS_ROUTES)
        assertTrue(RiderRoutes.APP_UPDATE !in RiderRoutes.PRE_BUSINESS_ROUTES)
    }
}
