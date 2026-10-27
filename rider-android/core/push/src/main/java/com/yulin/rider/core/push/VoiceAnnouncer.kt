package com.yulin.rider.core.push

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音播报(06 §3.4)。骑手在骑车,看不了屏幕,声音是唯一可靠的通道。
 *
 * 降级链:中文 TTS → 系统通知铃声 → ToneGenerator 蜂鸣。
 * 部分 ROM 缺中文引擎(06 §7 已知风险),此时至少要响,让骑手知道该看手机了。
 * 提示音用 ToneGenerator 合成而不是 res/raw/new_task.mp3:音频资源还没到位,
 * 合成音零素材依赖,拿到正式提示音后换成 MediaPlayer 播放即可。
 */
@Singleton
class VoiceAnnouncer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 设置页的语音开关;关掉后只响提示音不播报内容。 */
    @Volatile
    var speechEnabled: Boolean = true

    /** 设置页的提示音开关。关掉后只播语音,不再额外响一声。 */
    @Volatile
    var promptToneEnabled: Boolean = true

    /**
     * 播报语速倍率。
     * 电动车上风噪大,默认 1.0 有骑手反映听不清；调慢一档比调大音量更有用。
     */
    @Volatile
    var speechRate: Float = 1.0f
        set(value) {
            val clamped = value.coerceIn(MIN_RATE, MAX_RATE)
            field = clamped
            runCatching { tts?.setSpeechRate(clamped) }
        }

    private var tts: TextToSpeech? = null
    private var readyDeferred: CompletableDeferred<Boolean>? = null

    // ToneGenerator 持有原生 AudioTrack,复用一个实例,避免循环播报时反复申请音频资源
    private var toneGenerator: ToneGenerator? = null

    private val audioManager: AudioManager? by lazy {
        runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()
    }
    private var audioFocusRequest: AudioFocusRequest? = null

    /** 播放提示音。TTS 之前先响一声,骑手才会把注意力转过来。 */
    fun playPromptTone() {
        if (!promptToneEnabled) return
        if (playNotificationRingtone()) return
        playToneGenerator()
    }

    /**
     * 播报一段中文。TTS 不可用时回退到提示音,保证「至少响了」。
     *
     * @param interrupt true 表示打断当前播报(新单循环用),false 表示排队等前一句读完。
     *   服务端一次最多下发 5 条紧急消息,全用打断的话只有最后一条能被听到。
     * @return 是否真的用语音播出去了。
     */
    suspend fun speak(text: String, interrupt: Boolean = true): Boolean {
        if (!speechEnabled) return false
        val engine = awaitEngine() ?: run {
            playPromptTone()
            return false
        }
        // 抢一个瞬时焦点，导航和音乐会自动让音量，否则播报常被导航语音盖住
        requestAudioFocus()
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        return try {
            engine.speak(text, mode, null, UTTERANCE_ID) == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            Log.w(TAG, "TTS 播报失败,回退提示音", e)
            playPromptTone()
            false
        }
    }

    /**
     * 设置页「试听」。
     *
     * 这一项存在的意义是让骑手在上班前就能确认这台手机到底出不出声 ——
     * 缺中文引擎、被静音、被勿扰拦掉都只有真的响一次才发现得了。
     * @return 是否成功用语音播出（false 表示只响了提示音）。
     */
    suspend fun preview(text: String = PREVIEW_TEXT): Boolean {
        playPromptTone()
        return speak(text, interrupt = true)
    }

    /** 中文引擎是否可用。设置页据此提示骑手去装语音包。 */
    suspend fun speechAvailable(): Boolean = awaitEngine() != null

    fun stopSpeaking() {
        runCatching { tts?.stop() }
        abandonAudioFocus()
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        runCatching { toneGenerator?.release() }
        abandonAudioFocus()
        tts = null
        readyDeferred = null
        toneGenerator = null
    }

    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        if (audioFocusRequest != null) return
        runCatching {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            manager.requestAudioFocus(request)
            audioFocusRequest = request
        }.onFailure { Log.w(TAG, "申请音频焦点失败,继续播报", it) }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        val request = audioFocusRequest ?: return
        runCatching { manager.abandonAudioFocusRequest(request) }
        audioFocusRequest = null
    }

    private suspend fun awaitEngine(): TextToSpeech? {
        val existing = readyDeferred
        if (existing != null) {
            return if (withTimeoutOrNull(INIT_TIMEOUT_MILLIS) { existing.await() } == true) tts else null
        }

        val deferred = CompletableDeferred<Boolean>()
        readyDeferred = deferred
        val engine = try {
            TextToSpeech(context.applicationContext) { status ->
                deferred.complete(status == TextToSpeech.SUCCESS && applyChineseLocale())
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS 初始化异常", e)
            deferred.complete(false)
            null
        }
        tts = engine
        engine?.setOnUtteranceProgressListener(SilentProgressListener)

        val ok = withTimeoutOrNull(INIT_TIMEOUT_MILLIS) { deferred.await() } == true
        if (ok) runCatching { tts?.setSpeechRate(speechRate) }
        return if (ok) tts else null
    }

    private fun applyChineseLocale(): Boolean {
        val engine = tts ?: return false
        val result = runCatching { engine.setLanguage(Locale.CHINA) }.getOrNull()
        val usable = result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED &&
            result != null
        if (!usable) {
            Log.w(TAG, "TTS 缺少中文引擎(result=$result),回退提示音")
        }
        return usable
    }

    private fun playNotificationRingtone(): Boolean = runCatching {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return false
        val ringtone = RingtoneManager.getRingtone(context, uri) ?: return false
        ringtone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ringtone.play()
        true
    }.getOrDefault(false)

    private fun playToneGenerator() {
        runCatching {
            // 走 ALARM 通道:骑手常把媒体音量调到很低,通知音会被淹没
            val generator = toneGenerator
                ?: ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME).also { toneGenerator = it }
            generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, TONE_DURATION_MILLIS)
        }.onFailure { Log.w(TAG, "提示音播放失败", it) }
    }

    private object SilentProgressListener : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = Unit

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = Unit
    }

    private companion object {
        const val TAG = "VoiceAnnouncer"
        const val UTTERANCE_ID = "rider_new_task"
        const val INIT_TIMEOUT_MILLIS = 3_000L
        const val TONE_VOLUME = 100
        const val TONE_DURATION_MILLIS = 1200
        const val MIN_RATE = 0.6f
        const val MAX_RATE = 1.8f
        const val PREVIEW_TEXT = "您有 1 个新订单，兴庆区团结小区，1.2 公里"
    }
}
