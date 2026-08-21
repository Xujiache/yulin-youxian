package com.yulin.rider.core.network.interceptor

import com.yulin.rider.core.network.RiderErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RefreshFailureDispositionTest {

    @Test
    fun `only explicit invalid token codes invalidate session`() {
        assertEquals(
            RefreshFailureDisposition.INVALIDATE,
            refreshFailureDisposition(responseCode = RiderErrorCodes.UNAUTHORIZED),
        )
        assertEquals(
            RefreshFailureDisposition.INVALIDATE,
            refreshFailureDisposition(responseCode = RiderErrorCodes.RIDER_UNAUTHENTICATED),
        )
        assertEquals(
            RefreshFailureDisposition.PRESERVE,
            refreshFailureDisposition(responseCode = RiderErrorCodes.FORBIDDEN),
        )
        assertEquals(
            RefreshFailureDisposition.PRESERVE,
            refreshFailureDisposition(responseCode = 500),
        )
    }

    @Test
    fun `network failures preserve session`() {
        assertEquals(
            RefreshFailureDisposition.PRESERVE,
            refreshFailureDisposition(error = SocketTimeoutException()),
        )
        assertEquals(
            RefreshFailureDisposition.PRESERVE,
            refreshFailureDisposition(error = UnknownHostException()),
        )
    }

    @Test
    fun `concurrent unauthorized responses perform one refresh`() {
        val gate = RefreshSingleFlight()
        val refreshCalls = AtomicInteger()
        val ready = CountDownLatch(12)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(12)
        val currentToken = AtomicReference("expired")

        val futures = (1..12).map {
            pool.submit<String> {
                ready.countDown()
                start.await()
                gate.run {
                    if (currentToken.get() != "expired") {
                        currentToken.get()
                    } else {
                        refreshCalls.incrementAndGet()
                        Thread.sleep(20)
                        "renewed".also(currentToken::set)
                    }
                }
            }
        }
        ready.await(2, TimeUnit.SECONDS)
        start.countDown()

        assertTrue(futures.all { it.get(2, TimeUnit.SECONDS) == "renewed" })
        assertEquals(1, refreshCalls.get())
        pool.shutdownNow()
    }
}
