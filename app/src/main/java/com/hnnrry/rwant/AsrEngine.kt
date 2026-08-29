package com.hnnrry.rwant

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * ASR 封装（给 AI 的「耳朵」）。
 *
 * 两种模式：
 *   - "push"：按住说话，松手即停（单次识别）；
 *   - "auto"：自动倾听，说完一句自动续听，直到显式 stop 或急停（静音检测靠 SpeechRecognizer 自身的句末判定）。
 *
 * 回调：
 *   onPartial   实时中间结果（用来做「正在听」的预览）
 *   onFinal    一句最终识别文本
 *   onError    出错
 *   onStop     本次（或连续轮次）停止
 */
class AsrEngine(context: Context) {

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    @Volatile var listening = false
    var autoStopOnSilence = true

    var onPartial: ((String) -> Unit)? = null
    var onFinal: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStop: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            val msg = errorCode(error)
            if (listening && autoStopOnSilence) {
                // 自动模式：句末/静音后自动续听
                restart()
            } else {
                listening = false
                onError?.invoke(msg)
                onStop?.invoke()
            }
        }
        override fun onResults(results: Bundle?) { deliver(results) }
        override fun onPartialResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) onPartial?.invoke(matches[0])
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    init {
        if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer?.setRecognitionListener(listener)
        }
    }

    private fun deliver(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull().orEmpty()
        listening = false
        if (text.isNotEmpty()) onFinal?.invoke(text) else onError?.invoke("未识别到内容")
        onStop?.invoke()
    }

    fun start(mode: String) {
        if (EmergencyStop.isActive()) { onError?.invoke("已急停：无法倾听"); return }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError?.invoke("设备不支持语音识别"); return
        }
        autoStopOnSilence = (mode == "auto")
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        val intent = listenIntent()
        listening = true
        runCatching { recognizer?.startListening(intent) }
    }

    /** 自动模式续听（带极短延迟，避免和上一段结果抢资源） */
    private fun restart() {
        if (!listening) return
        mainHandler.postDelayed({
            if (!listening) return@postDelayed
            runCatching { recognizer?.startListening(listenIntent()) }
        }, 300)
    }

    private fun listenIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    fun stop() {
        listening = false
        runCatching { recognizer?.stopListening() }
        onStop?.invoke()
    }

    fun destroy() {
        listening = false
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun errorCode(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没有匹配结果"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
        SpeechRecognizer.ERROR_SERVER -> "服务器错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到语音"
        else -> "识别错误（$error）"
    }
}
