package com.yulin.rider.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * 动态代理监听器能不能从 SDK 的监听器列表里摘掉。
 *
 * 摘不掉不会报任何错，只会表现成「每次算路的路线越来越乱」，
 * 排查时几乎不会怀疑到 equals 上，所以这里把语义钉死。
 */
class NaviListenerProxyTest {

    private interface Listener {
        fun onEvent()
    }

    @Test
    fun `代理能从列表里按引用移除`() {
        val listeners = mutableListOf<Listener>()
        val first = newListener()
        val second = newListener()
        listeners += first
        listeners += second

        assertTrue(listeners.remove(first))
        assertEquals(listOf(second), listeners)
    }

    @Test
    fun `不同代理互不相等`() {
        val first = newListener()
        val second = newListener()

        assertFalse(first == second)
        assertTrue(first == first)
    }

    @Test
    fun `hashCode 跟着 proxy 而不是外层单例`() {
        val first = newListener()
        val second = newListener()

        assertEquals(System.identityHashCode(first), first.hashCode())
        assertFalse(first.hashCode() == second.hashCode())
    }

    @Test
    fun `业务方法不被 Object 分支截走`() {
        assertNull(objectMethodOnProxy(Any(), "onCalculateRouteSuccess", null))
    }

    private fun newListener(): Listener {
        val handler = InvocationHandler { proxy, method, args ->
            objectMethodOnProxy(proxy, method.name, args)
        }
        return Proxy.newProxyInstance(
            Listener::class.java.classLoader,
            arrayOf(Listener::class.java),
            handler,
        ) as Listener
    }
}
