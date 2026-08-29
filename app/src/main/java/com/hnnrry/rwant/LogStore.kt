package com.hnnrry.rwant

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 对话日志落盘（给 AI 的「嘴」专用）：
 *   - 所有对话（AI 说 / 用户说 / 指令 / 连接）写入应用私有目录 logs/rwant-YYYY-MM-DD.log，按天分文件
 *   - 行格式：ISO时间戳 | 类型 | 详情 | 结果
 *   - 单线程异步写盘，不阻塞主线程；写失败绝不抛异常打断业务
 *   - [lastOperation] 保存最近一次操作摘要，供常驻通知展示
 *   - 敏感内容（密码/金额/卡号/验证码等）写盘前一律打码成「***」
 *
 * 只记录，不思考：本类不做任何业务判断。
 */
object LogStore {

    private const val TAG = "RwantLogStore"
    private const val DIR_NAME = "logs"
    private const val FILE_PREFIX = "rwant-"
    private const val FILE_SUFFIX = ".log"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_PREVIEW_CHARS = 80

    @Volatile private var appContext: Context? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val formatLock = Any()

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var lastOperation: String = "尚无对话记录"
        private set
    @Volatile var lastOperationAt: String = "--:--:--"
        private set
    @Volatile var lastOperationRejected: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun init(context: Context) {
        val app = context.applicationContext
        if (appContext === app) return
        appContext = app
        runCatching { File(app.filesDir, DIR_NAME).mkdirs() }
    }

    fun addOperationListener(listener: (String) -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }
    fun removeOperationListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    /** 记一条「对话/操作」日志：刷新 [lastOperation] 并通知监听者 */
    fun operation(op: String, detail: String, result: String) {
        val safeDetail = sanitize(detail)
        lastOperation = "$op $safeDetail"
        lastOperationAt = clock()
        lastOperationRejected = result.contains(EmergencyStop.REJECTED_SHORT)
        write(op, safeDetail, result)
        for (listener in listeners) runCatching { listener(lastOperation) }
    }

    /** 记一条「事件」日志：只落盘，不进通知 */
    fun event(message: String) { write("事件", sanitize(message), "-") }

    private fun write(op: String, detail: String, result: String) {
        val line = "${timestamp()} | $op | $detail | $result"
        val context = appContext
        if (context == null) { Log.w(TAG, "LogStore 未初始化，仅输出 logcat：$line"); return }
        ioExecutor.execute { appendSafely(logFile(context, today()), line) }
        Log.d(TAG, line)
    }

    private fun appendSafely(file: File, line: String) {
        try {
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            if (file.length() > MAX_FILE_BYTES) {
                file.writeText("${timestamp()} | 系统 | 日志已达单文件上限，旧内容被清空 | -\n")
            }
            file.appendText(line + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "写日志失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun readLog(context: Context, day: String): List<String> =
        runCatching { logFile(context, day).readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    fun readToday(context: Context): List<String> = readLog(context, today())

    fun listLogDays(context: Context): List<String> {
        val dir = File(context.filesDir, DIR_NAME)
        return dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?.map { it.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX) }
            ?.sortedDescending() ?: emptyList()
    }

    fun logFile(context: Context, day: String): File =
        File(File(context.filesDir, DIR_NAME), "$FILE_PREFIX$day$FILE_SUFFIX")

    fun today(): String = synchronized(formatLock) { dayFormat.format(Date()) }
    private fun clock(): String = synchronized(formatLock) { clockFormat.format(Date()) }
    private fun timestamp(): String = synchronized(formatLock) { isoFormat.format(Date()) }

    // ---------------------------------------------------------------- 敏感内容打码

    private val sensitiveKeyValue = Regex(
        "(?i)((?:password|passwd|pwd|passcode|pin|密码|口令|支付密码|金额|amount|price|余额|balance|" +
            "卡号|银行卡|card|cvv|cvc|有效期|验证码|otp|token|secret)\\s*[:=：]\\s*)([^|,;&\\s]+)"
    )
    private val sensitiveContentHint = Regex(
        "(?i)(password|passwd|pwd|passcode|密码|口令|支付密码|验证码|otp|cvv|cvc|银行卡|卡号|金额|余额)"
    )

    fun sanitize(detail: String): String {
        if (detail.isEmpty()) return detail
        val masked = detail.replace(sensitiveKeyValue) { m -> m.groupValues[1] + "***" }
        return if (looksSensitive(masked)) "***" else preview(masked)
    }

    private fun looksSensitive(text: String): Boolean {
        if (sensitiveContentHint.containsMatchIn(text)) return true
        val digitsOnly = text.replace(Regex("[\\s\\-]"), "")
        return digitsOnly.length in 6..19 && digitsOnly.all { it.isDigit() }
    }

    private fun preview(text: String): String =
        if (text.length <= MAX_PREVIEW_CHARS) text else text.substring(0, MAX_PREVIEW_CHARS) + "…"
}
