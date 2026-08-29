package com.hnnrry.rwant

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 极简本地 HTTP 服务器（MCP 通道的传输层，沿用 Ridea 已验证实现）。
 *
 * 不引入重型依赖（NanoHTTPD / Ktor）—— 沙箱不能编译，少一个三方库少一分 Gradle 依赖风险；
 * MCP 只需要 POST + GET(SSE) 这点能力，几百行纯 Kotlin（java.net 标准库）够用。
 *
 * 能力：
 *   - HTTP/1.1 请求行 + 头 + Content-Length 体；keep-alive 复用连接
 *   - [HttpResponse.streamWriter] 非 null 时接管连接做流式输出（SSE：text/event-stream）
 *   - 请求头 / 请求体都有上限，超限直接 413/431 并断开
 *   - 全部异常吞掉记日志：服务不能因为一个坏请求垮掉
 */
class MiniHttpServer(
    private val port: Int,
    private val handler: Handler
) {

    interface Handler {
        fun handle(request: HttpRequest): HttpResponse
    }

    class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray
    ) {
        fun header(nameLower: String): String? = headers[nameLower]
        fun bodyText(): String = String(body, Charsets.UTF_8)
    }

    class HttpResponse(
        val status: Int = 200,
        val reason: String = "OK",
        val contentType: String = "application/json; charset=utf-8",
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
        val streamWriter: ((StreamHandle) -> Unit)? = null
    ) {
        companion object {
            fun json(status: Int, reason: String, jsonText: String, headers: Map<String, String> = emptyMap()): HttpResponse =
                HttpResponse(status, reason, "application/json; charset=utf-8", headers, jsonText.toByteArray(Charsets.UTF_8))

            fun text(status: Int, reason: String, contentType: String, text: String): HttpResponse =
                HttpResponse(status, reason, contentType, emptyMap(), text.toByteArray(Charsets.UTF_8))
        }
    }

    class StreamHandle(private val out: OutputStream) {
        private val lock = Any()
        @Volatile private var closed = false

        fun sendEvent(event: String, data: String): Boolean {
            if (closed) return false
            val payload = StringBuilder()
            payload.append("event: ").append(event).append('\n')
            for (line in data.split('\n')) payload.append("data: ").append(line).append('\n')
            payload.append('\n')
            return try {
                synchronized(lock) {
                    if (closed) return false
                    out.write(payload.toString().toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                true
            } catch (e: Exception) { closed = true; false }
        }

        fun sendComment(comment: String): Boolean {
            if (closed) return false
            return try {
                synchronized(lock) {
                    if (closed) return false
                    out.write(": $comment\n\n".toByteArray(Charsets.UTF_8)); out.flush()
                }
                true
            } catch (e: Exception) { closed = true; false }
        }

        fun isClosed(): Boolean = closed
        internal fun markClosed() { closed = true }
    }

    private val TAG = "RwantHttp"
    @Volatile private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val connectionSeq = AtomicLong()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Synchronized
    fun start() {
        if (isRunning) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "RwantHttp-${connectionSeq.incrementAndGet()}").apply { isDaemon = true }
        }
        isRunning = true
        val acceptThread = Thread({ acceptLoop(socket) }, "RwantHttp-accept")
        acceptThread.isDaemon = true
        acceptThread.start()
        Log.i(TAG, "HTTP 服务器已监听端口 $port")
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { executor?.shutdownNow() }
        executor = null
        Log.i(TAG, "HTTP 服务器已停止")
    }

    private fun acceptLoop(socket: ServerSocket) {
        val pool = executor ?: return
        while (isRunning) {
            val client = try { socket.accept() } catch (e: IOException) {
                if (isRunning) Log.w(TAG, "accept 结束：${e.javaClass.simpleName}")
                return
            }
            pool.execute { serve(client) }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), 8 * 1024)
            val output = socket.getOutputStream()
            while (isRunning && !socket.isClosed) {
                val request = try {
                    readRequest(input) ?: break
                } catch (e: RequestTooLargeException) {
                    writeSimple(output, e.status, e.reason, e.message ?: "请求过大"); break
                } catch (e: Exception) {
                    Log.w(TAG, "请求解析失败：${e.javaClass.simpleName}: ${e.message}"); break
                }
                val keepAlive = request.header("connection")?.lowercase() != "close"
                val response = try {
                    handler.handle(request)
                } catch (e: Exception) {
                    Log.e(TAG, "处理请求异常：${e.javaClass.simpleName}: ${e.message}")
                    HttpResponse.json(500, "Internal Server Error", errorJson("服务器内部错误：${e.javaClass.simpleName}"))
                }
                if (response.streamWriter != null) {
                    writeStreamHead(output, response)
                    val handle = StreamHandle(output)
                    try { response.streamWriter.invoke(handle) } catch (e: Exception) {
                        Log.w(TAG, "SSE 流结束：${e.javaClass.simpleName}: ${e.message}")
                    } finally { handle.markClosed() }
                    break
                }
                writeResponse(output, response, keepAlive)
                if (!keepAlive) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "连接异常结束：${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private class RequestTooLargeException(val status: Int, val reason: String, message: String) : RuntimeException(message)

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > MAX_LINE_BYTES) throw RequestTooLargeException(431, "Request Header Fields Too Large", "请求头单行过大")
        }
    }

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) throw IllegalArgumentException("请求行非法：$requestLine")
        val method = parts[0].uppercase()
        val target = parts[1]
        val path: String
        val rawQuery: String?
        val qIndex = target.indexOf('?')
        if (qIndex >= 0) { path = target.substring(0, qIndex); rawQuery = target.substring(qIndex + 1) }
        else { path = target; rawQuery = null }
        val headers = HashMap<String, String>()
        var lines = 0
        while (true) {
            val line = readLine(input) ?: throw IllegalArgumentException("请求头未结束就断开")
            if (line.isEmpty()) break
            lines++
            if (lines > MAX_HEADER_LINES) throw RequestTooLargeException(431, "Request Header Fields Too Large", "请求头行数过多")
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength > MAX_BODY_BYTES) throw RequestTooLargeException(413, "Payload Too Large", "请求体超过 ${MAX_BODY_BYTES / 1024 / 1024}MB 上限")
        val body = if (contentLength > 0) {
            ByteArray(contentLength.toInt()).also { buf ->
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) throw IllegalArgumentException("请求体不完整")
                    read += n
                }
            }
        } else ByteArray(0)
        return HttpRequest(method, path, parseQuery(rawQuery), headers, body)
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val key: String
            val value: String
            if (idx >= 0) { key = decodeComponent(pair.substring(0, idx)); value = decodeComponent(pair.substring(idx + 1)) }
            else { key = decodeComponent(pair); value = "" }
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun decodeComponent(text: String): String {
        if (!text.contains('%') && !text.contains('+')) return text
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '+' -> { bytes.write(' '.code); i++ }
                c == '%' -> {
                    if (i + 2 < text.length) {
                        val hex = text.substring(i + 1, i + 3)
                        val value = hex.toIntOrNull(16)
                        if (value != null) { bytes.write(value); i += 3 } else { bytes.write(c.code); i++ }
                    } else { bytes.write(c.code); i++ }
                }
                else -> {
                    val code = c.code
                    if (code < 0x80) bytes.write(code)
                    else bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
        }
        return bytes.toString("UTF-8")
    }

    private fun writeResponse(output: OutputStream, response: HttpResponse, keepAlive: Boolean) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(response.status).append(' ').append(response.reason).append("\r\n")
        head.append("Content-Type: ").append(response.contentType).append("\r\n")
        head.append("Content-Length: ").append(response.body.size).append("\r\n")
        head.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        for ((k, v) in response.headers) head.append(k).append(": ").append(v).append("\r\n")
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (response.body.isNotEmpty()) output.write(response.body)
        output.flush()
    }

    private fun writeStreamHead(output: OutputStream, response: HttpResponse) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(response.status).append(' ').append(response.reason).append("\r\n")
        head.append("Content-Type: text/event-stream; charset=utf-8\r\n")
        head.append("Cache-Control: no-cache\r\n")
        head.append("Connection: close\r\n")
        for ((k, v) in response.headers) head.append(k).append(": ").append(v).append("\r\n")
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun writeSimple(output: OutputStream, status: Int, reason: String, message: String) {
        runCatching { writeResponse(output, HttpResponse.json(status, reason, errorJson(message)), keepAlive = false) }
    }

    private fun errorJson(message: String): String = MiniJson.write(linkedMapOf("error" to message))

    companion object {
        private const val MAX_LINE_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
        private const val MAX_HEADER_LINES = 100
    }
}
