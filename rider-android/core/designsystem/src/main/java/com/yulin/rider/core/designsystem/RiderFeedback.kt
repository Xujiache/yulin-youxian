package com.yulin.rider.core.designsystem

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * 确认音。06 §3.5「双反馈」:每次确认都有震动 + 音效,骑手不看屏幕也知道成功了。
 *
 * 用 ToneGenerator 而不是音频资源,是为了在任何 ROM、任何静音策略下都能出声且不依赖打包资源;
 * 部分定制 ROM 上音频资源紧张时构造函数会抛异常,失败只降级为「无声」,绝不能影响状态流转。
 */
internal object RiderSound {

    private const val TONE_DURATION_MS = 180
    private const val RELEASE_DELAY_MS = 450L

    fun playConfirm(enabled: Boolean) {
        if (!enabled) return
        try {
            val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            generator.startTone(ToneGenerator.TONE_PROP_ACK, TONE_DURATION_MS)
            Handler(Looper.getMainLooper()).postDelayed(
                { runCatching { generator.release() } },
                RELEASE_DELAY_MS,
            )
        } catch (_: Throwable) {
            // 出不了声不影响业务
        }
    }
}
