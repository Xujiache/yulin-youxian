package com.yulin.rider.core.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缺库判定曾经只查 nativeLibraryDir，而 AGP 默认 extractNativeLibs=false，
 * .so 压在 APK 里由 linker 直接映射、根本不会解压到那个目录 ——
 * 结果每台真机都被判成缺库，底图、高德定位、SDK 导航一起失效。
 *
 * 这里用真实 APK 里的条目名锁住匹配规则。
 */
class AmapNativeLibDetectionTest {

    private val armDevice = setOf("arm64-v8a", "armeabi-v7a")
    private val x86Emulator = setOf("x86_64", "x86")

    @Test
    fun `真机 ABI 能认出打进包里的高德渲染库`() {
        assertTrue(
            AmapKeyState.isRendererEntry("lib/arm64-v8a/libAMapSDK_NAVI_v10_0_800.so", armDevice)
        )
        assertTrue(
            AmapKeyState.isRendererEntry("lib/armeabi-v7a/libAMapSDK_NAVI_v10_0_800.so", armDevice)
        )
    }

    @Test
    fun `SDK 升版本换了文件名也要认得`() {
        assertTrue(
            AmapKeyState.isRendererEntry("lib/arm64-v8a/libAMapSDK_MAP_v99_1_2.so", armDevice)
        )
    }

    @Test
    fun `x86 模拟器上确实没有对应 ABI 的渲染库`() {
        // 包里只有 arm 的 so，模拟器跑 x86_64，这才是真正该降级的情况
        assertFalse(
            AmapKeyState.isRendererEntry("lib/arm64-v8a/libAMapSDK_NAVI_v10_0_800.so", x86Emulator)
        )
    }

    @Test
    fun `其他 so 不能误认成高德渲染库`() {
        listOf(
            "lib/arm64-v8a/libandroidx.graphics.path.so",
            "lib/arm64-v8a/libimage_processing_util_jni.so",
            "lib/arm64-v8a/libneonui_shared.so",
        ).forEach { assertFalse(it, AmapKeyState.isRendererEntry(it, armDevice)) }
    }

    @Test
    fun `非 lib 目录下的同名文件不算`() {
        assertFalse(AmapKeyState.isRendererEntry("assets/libAMapSDK_NAVI.so", armDevice))
        assertFalse(AmapKeyState.isRendererEntry("lib/libAMapSDK_NAVI.so", armDevice))
        assertFalse(
            AmapKeyState.isRendererEntry("lib/arm64-v8a/nested/libAMapSDK_NAVI.so", armDevice)
        )
    }
}
