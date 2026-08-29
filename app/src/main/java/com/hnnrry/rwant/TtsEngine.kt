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
 * - onDone 回调让悬浮球在朗读结束时切回「空闲」态。
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

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
                tts?.setPitch(pitch)
                tts?.setSpeechRate(rate)
                ready = true
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { onState?.invoke(true) }
            override fun onDone(utteranceId: String?) { onState?.invoke(false) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onState?.invoke(false) }
        })
    }

    fun speak(text: String, quiet: Boolean = false) {
        if (EmergencyStop.isActive()) return
        if (text.isBlank()) return
        if (quiet) return // 静音模式：只显示气泡，不发声（由调用方负责把文字送进气泡）
        if (!ready) return
        // 应用音量设置（TTS 路由到 STREAM_MUSIC 的可控范围内做近似调整）
        applyVolume()
        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rwant_${System.currentTimeMillis()}")
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
}
