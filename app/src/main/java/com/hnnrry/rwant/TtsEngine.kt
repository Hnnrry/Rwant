package com.hnnrry.rwant

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * TTS 封装（给 AI 的「嘴」的发声部分）。
 *
 * - speak(text, quiet)：quiet=true 时只把文字送进气泡显示、不发声（静音模式）；
 * - 语速 / 音调 / 音量可调，设置项实时生效；
 * - 急停期间直接拒读；
 * - 初始化失败会有限次重试，speak 在引擎未就绪时会等待就绪（最多 ~4 秒）再朗读；
 * - onDone 回调让悬浮球在朗读结束时切回「空闲」态；
 * - speak 返回本次是否真正出声（供 MCP 真实回报，不再谎报成功）。
 */
class TtsEngine(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null

    @Volatile var ready = false
    var pitch: Float = 1.0f
    var rate: Float = 1.0f
    var volume: Float = 1.0f

    /** 朗读进度：speaking / idle */
    var onState: ((Boolean) -> Unit)? = null

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var initAttempts = 0

    private val initListener = TextToSpeech.OnInitListener { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.SIMPLIFIED_CHINESE
            tts?.setPitch(pitch)
            tts?.setSpeechRate(rate)
            ready = true
        } else {
            // 初始化失败：有限次重试（间隔 500ms）
            initAttempts++
            if (initAttempts <= INIT_MAX_RETRIES) {
                try { Thread.sleep(INIT_RETRY_DELAY_MS) } catch (_: InterruptedException) { return@OnInitListener }
                runCatching { tts = TextToSpeech(appContext, initListener) }
            }
        }
    }

    init {
        runCatching { tts = TextToSpeech(appContext, initListener) }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { onState?.invoke(true) }
            override fun onDone(utteranceId: String?) { onState?.invoke(false) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onState?.invoke(false) }
        })
    }

    /**
     * 朗读文字，返回本次是否真正出声。
     * - 急停 / 空文本 → false
     * - quiet=true 只显示气泡 → true（由调用方把文字送进气泡）
     * - 引擎未就绪 → 轮询等待（最多 ~4s，每 100ms 一次），超时返回 false
     */
    fun speak(text: String, quiet: Boolean = false): Boolean {
        if (EmergencyStop.isActive()) return false
        if (text.isBlank()) return false
        if (quiet) return true
        // 等待引擎就绪：避免 TTS 初始化未完成时静默丢字（调用线程内，不阻塞过久）
        var waited = 0
        while (!ready && waited < TTS_READY_TIMEOUT_MS) {
            try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            waited += 100
        }
        if (!ready) return false
        applyVolume()
        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rwant_${System.currentTimeMillis()}")
        return true
    }

    fun stop() { runCatching { tts?.stop() } }

    private fun applyVolume() {
        runCatching {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (max * volume.coerceIn(0f, 1f)).toInt().coerceAtLeast(0)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    companion object {
        private const val INIT_MAX_RETRIES = 3
        private const val INIT_RETRY_DELAY_MS = 500L
        private const val TTS_READY_TIMEOUT_MS = 4000
    }
}
