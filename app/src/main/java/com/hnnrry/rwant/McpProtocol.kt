package com.hnnrry.rwant

import android.content.Context
import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP 协议层（给 AI 的「嘴」的协议接口，沿用 Ridea 已验证的 JSON-RPC 2.0 + SSE 骨架）。
 *
 * 传输：MCP Streamable HTTP（POST /mcp 请求响应 + GET /mcp SSE 推送）
 * 鉴权：除 /health 外都要 Bearer 令牌；initialize 需用户在手机上点「同意」。
 *
 * Rwant 暴露的工具（AI 用这些把话说出来 / 把用户的话收回来）：
 *   speak / speak_quiet / listen_start / listen_stop / get_transcript /
 *   clear_bubble / set_mood / emergency_stop / get_logs / get_status
 *
 * 嘴永远不思考：所有内容来自 AI 或用户，本层只做转发与推送。
 */
class McpProtocol(private val context: Context) : MiniHttpServer.Handler {

    companion object {
        private const val TAG = "RwantMcp"
        const val MCP_PATH = "/mcp"
        const val SERVER_NAME = "rwant"
        const val SERVER_VERSION = "0.1.0"
        private val SUPPORTED_PROTOCOL_VERSIONS = listOf("2025-06-18", "2025-03-26")
        private const val LATEST_PROTOCOL_VERSION = "2025-06-18"

        private const val CODE_PARSE_ERROR = -32700
        private const val CODE_INVALID_REQUEST = -32600
        private const val CODE_METHOD_NOT_FOUND = -32601
        private const val CODE_INVALID_PARAMS = -32602
        const val CODE_AUTH_PENDING = -32002

        /** 当前运行实例（FloatingService 用它推 transcript） */
        @Volatile
        var current: McpProtocol? = null
    }

    private val sessions = ConcurrentHashMap<String, String>()
    private val sseHub = SseHub()
    private val requestCounter = AtomicInteger(0)

    private val operationListener: (String) -> Unit = { _ ->
        sseHub.broadcast(
            "operation",
            MiniJson.write(
                linkedMapOf(
                    "jsonrpc" to "2.0",
                    "method" to "notifications/rwant/operation",
                    "params" to linkedMapOf(
                        "text" to LogStore.lastOperation,
                        "at" to LogStore.lastOperationAt,
                        "rejected" to LogStore.lastOperationRejected
                    )
                )
            )
        )
    }

    private val secureRandom = SecureRandom()

    init {
        LogStore.addOperationListener(operationListener)
        current = this
    }

    fun shutdown() {
        LogStore.removeOperationListener(operationListener)
        current = null
        sseHub.closeAll()
    }

    /** 用户说完一句话，推给所有 SSE 连接的 AI（AI 据此拿到用户输入） */
    fun pushTranscript(text: String) {
        sseHub.broadcast(
            "transcript",
            MiniJson.write(
                linkedMapOf(
                    "jsonrpc" to "2.0",
                    "method" to "notifications/rwant/transcript",
                    "params" to linkedMapOf("text" to text)
                )
            )
        )
    }

    // ---------------------------------------------------------------- HTTP 路由

    override fun handle(request: MiniHttpServer.HttpRequest): MiniHttpServer.HttpResponse {
        LogStore.init(context)
        EmergencyStop.init(context)
        TrustCenter.init(context)
        requestCounter.incrementAndGet()

        return try {
            when {
                request.path == "/health" && request.method == "GET" -> handleHealth()
                request.method == "POST" && request.path == MCP_PATH -> handlePost(request)
                request.method == "GET" && request.path == MCP_PATH -> handleSseGet(request)
                request.method == "DELETE" && request.path == MCP_PATH -> handleSessionClose(request)
                else -> MiniHttpServer.HttpResponse.json(404, "Not Found", errorJson("未知路径：${request.path}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP 处理异常：${e.javaClass.simpleName}: ${e.message}")
            MiniHttpServer.HttpResponse.json(500, "Internal Server Error", errorJson("服务器内部错误"))
        }
    }

    private fun handleHealth(): MiniHttpServer.HttpResponse = MiniHttpServer.HttpResponse.json(
        200, "OK",
        MiniJson.write(
            linkedMapOf(
                "status" to "ok", "server" to SERVER_NAME,
                "version" to SERVER_VERSION, "protocol" to LATEST_PROTOCOL_VERSION
            )
        )
    )

    private fun handlePost(request: MiniHttpServer.HttpRequest): MiniHttpServer.HttpResponse {
        val token = request.header("authorization")?.removePrefix("Bearer")?.trim()
        if (!TrustCenter.isTokenValid(context, token)) {
            return MiniHttpServer.HttpResponse.json(401, "Unauthorized", errorJson("缺少或错误的令牌：请带 Authorization: Bearer <令牌>"))
        }
        val text = request.bodyText()
        if (text.isBlank()) return MiniHttpServer.HttpResponse.json(400, "Bad Request", errorJson("请求体为空"))
        val message = MiniJson.parseOrNull(text) ?: return MiniHttpServer.HttpResponse.json(
            400, "Bad Request",
            MiniJson.write(linkedMapOf("jsonrpc" to "2.0", "id" to null, "error" to linkedMapOf("code" to CODE_PARSE_ERROR, "message" to "JSON 解析失败")))
        )
        val map = message as? Map<*, *> ?: return MiniHttpServer.HttpResponse.json(400, "Bad Request", errorJson("JSON-RPC 消息必须是对象"))
        val method = map["method"]?.toString()
        if (method == null) return MiniHttpServer.HttpResponse.json(400, "Bad Request", errorJson("缺少 method 字段"))

        val id = map["id"]
        if (id == null) {
            handleNotification(method)
            return MiniHttpServer.HttpResponse.json(202, "Accepted", "")
        }
        val (result, newSessionId) = dispatch(method, (map["params"] as? Map<*, *>) ?: emptyMap<Any, Any>(), request)
        val headers = if (method == "initialize" && newSessionId != null) mapOf("Mcp-Session-Id" to newSessionId) else emptyMap()
        return MiniHttpServer.HttpResponse.json(200, "OK", MiniJson.write(responseBody(id, result)), headers)
    }

    private fun handleNotification(method: String) {
        when (method) {
            "notifications/initialized" -> LogStore.event("MCP 客户端已完成初始化（initialized）")
            "notifications/cancelled" -> Unit
            else -> Log.d(TAG, "忽略通知：$method")
        }
    }

    private fun dispatch(method: String, params: Map<*, *>, request: MiniHttpServer.HttpRequest): Pair<Map<String, Any?>, String?> =
        when (method) {
            "initialize" -> handleInitialize(params)
            "ping" -> Pair(emptyMap(), null)
            "tools/list" -> Pair(linkedMapOf("tools" to toolSpecs.map { it.toJson() }), null)
            "tools/call" -> handleToolsCall(params, request)
            else -> errorResult(CODE_METHOD_NOT_FOUND, "未知方法：$method（支持 initialize / ping / tools/list / tools/call）")
        }

    // ---------------------------------------------------------------- 握手

    private fun handleInitialize(params: Map<*, *>): Pair<Map<String, Any?>, String?> {
        val clientInfo = params["clientInfo"] as? Map<*, *>
        val clientName = clientInfo?.get("name")?.toString()?.trim().orEmpty().ifEmpty { "未命名 AI" }
        val clientVersion = clientInfo?.get("version")?.toString() ?: ""

        val aiId = TrustCenter.identityFor(clientName)
        val denyReason = TrustCenter.requestConnection(context, aiId, clientName, clientVersion)
        if (denyReason != null) {
            return errorResult(CODE_AUTH_PENDING, denyReason, linkedMapOf("aiId" to aiId, "aiName" to clientName))
        }

        val protocolVersion = params["protocolVersion"]?.toString()
            ?.takeIf { SUPPORTED_PROTOCOL_VERSIONS.contains(it) } ?: LATEST_PROTOCOL_VERSION
        val sessionId = newSessionId()
        sessions[sessionId] = aiId
        TrustCenter.recordActivity(aiId)
        LogStore.operation("指令", "AI「$clientName」initialize（握手成功）", "成功：会话已建立")

        val result = linkedMapOf<String, Any?>(
            "protocolVersion" to protocolVersion,
            "capabilities" to linkedMapOf<String, Any?>("tools" to linkedMapOf<String, Any?>("listChanged" to false)),
            "serverInfo" to linkedMapOf("name" to SERVER_NAME, "version" to SERVER_VERSION),
            "instructions" to
                "Rwant 是给 AI 的嘴：它把你说的话用悬浮球播出来（TTS），并把用户说的话收回来。" +
                "先 tools/list 看工具；用 speak 朗读、speak_quiet 只显示不发声、listen_start 开始听、get_transcript 取用户的话。" +
                "急停期间所有操作被拒绝。"
        )
        return Pair(result, sessionId)
    }

    private fun newSessionId(): String {
        val bytes = ByteArray(12)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { String.format(java.util.Locale.US, "%02x", it) }
    }

    // ---------------------------------------------------------------- 工具调用

    private fun handleToolsCall(params: Map<*, *>, request: MiniHttpServer.HttpRequest): Pair<Map<String, Any?>, String?> {
        val toolName = params["name"]?.toString()
        if (toolName.isNullOrBlank()) return errorResult(CODE_INVALID_PARAMS, "缺少 name 参数")
        val spec = toolSpecs.firstOrNull { it.name == toolName } ?: return errorResult(CODE_INVALID_PARAMS, "未知工具：$toolName（tools/list 查看全部）")
        @Suppress("UNCHECKED_CAST")
        val args = (params["arguments"] as? Map<String, Any?>) ?: emptyMap()

        val sessionId = request.header("mcp-session-id")
        val aiId = sessionId?.let { sessions[it] }
        if (spec.needsApprovedAi && aiId == null) {
            return errorResult(CODE_INVALID_PARAMS, "会话未建立：请先 initialize 并等待用户在手机上点「同意」")
        }
        val aiName = aiId?.let { TrustCenter.profile(it)?.name } ?: "未知AI"

        if (EmergencyStop.isActive()) {
            return Pair(
                linkedMapOf("content" to listOf(linkedMapOf("type" to "text", "text" to EmergencyStop.REJECTED_MESSAGE)), "isError" to true),
                null
            )
        }

        return try {
            Pair(spec.handler(ToolContext(aiId, aiName, args)), null)
        } catch (e: Exception) {
            Log.e(TAG, "工具 $toolName 执行异常：${e.javaClass.simpleName}: ${e.message}")
            Pair(linkedMapOf("content" to listOf(linkedMapOf("type" to "text", "text" to "工具执行异常：${e.message}")), "isError" to true), null)
        }
    }

    // ---------------------------------------------------------------- SSE

    private fun handleSseGet(request: MiniHttpServer.HttpRequest): MiniHttpServer.HttpResponse {
        val token = request.header("authorization")?.removePrefix("Bearer")?.trim()
        if (!TrustCenter.isTokenValid(context, token)) {
            return MiniHttpServer.HttpResponse.json(401, "Unauthorized", errorJson("缺少或错误的令牌：请带 Authorization: Bearer <令牌>"))
        }
        val accept = request.header("accept") ?: ""
        if (!accept.contains("text/event-stream")) {
            return MiniHttpServer.HttpResponse.json(406, "Not Acceptable", errorJson("GET /mcp 需要 Accept: text/event-stream（SSE 推送通道）"))
        }
        val sessionHeader = request.header("mcp-session-id") ?: newSessionId()
        return MiniHttpServer.HttpResponse(
            status = 200, reason = "OK",
            headers = mapOf("Mcp-Session-Id" to sessionHeader),
            streamWriter = { handle ->
                LogStore.event("MCP SSE 通道已建立（服务端推送 transcript）")
                sseHub.register(handle)
                while (!handle.isClosed()) {
                    try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
                }
            }
        )
    }

    private fun handleSessionClose(request: MiniHttpServer.HttpRequest): MiniHttpServer.HttpResponse {
        val token = request.header("authorization")?.removePrefix("Bearer")?.trim()
        if (!TrustCenter.isTokenValid(context, token)) {
            return MiniHttpServer.HttpResponse.json(401, "Unauthorized", errorJson("缺少或错误的令牌：请带 Authorization: Bearer <令牌>"))
        }
        request.header("mcp-session-id")?.let { sessions.remove(it) }
        return MiniHttpServer.HttpResponse.json(200, "OK", "{}")
    }

    // ---------------------------------------------------------------- JSON-RPC 组装

    private fun responseBody(id: Any?, resultOrError: Map<String, Any?>): Map<String, Any?> {
        val body = linkedMapOf<String, Any?>("jsonrpc" to "2.0", "id" to id)
        body.putAll(resultOrError)
        return body
    }

    private fun errorResult(code: Int, message: String, data: Map<String, Any?>? = null): Pair<Map<String, Any?>, String?> {
        val error = linkedMapOf<String, Any?>("code" to code, "message" to message)
        if (data != null) error["data"] = data
        return Pair(linkedMapOf("error" to error), null)
    }

    private fun errorJson(message: String): String = MiniJson.write(linkedMapOf("error" to message))

    // ---------------------------------------------------------------- 工具定义

    class ToolContext(val aiId: String?, val aiName: String, val args: Map<String, Any?>)

    private class ToolSpec(
        val name: String, val description: String,
        val inputSchema: Map<String, Any?>, val needsApprovedAi: Boolean,
        val handler: (ToolContext) -> Map<String, Any?>
    ) {
        fun toJson(): Map<String, Any?> = linkedMapOf("name" to name, "description" to description, "inputSchema" to inputSchema)
    }

    private fun tool(name: String, description: String, needsApprovedAi: Boolean = true,
                    required: List<String> = emptyList(),
                    properties: Map<String, Map<String, Any?>> = emptyMap(),
                    handler: (ToolContext) -> Map<String, Any?>) =
        ToolSpec(name, description,
            linkedMapOf("type" to "object", "properties" to properties, "required" to required),
            needsApprovedAi, handler)

    private fun ok(text: String): Map<String, Any?> = linkedMapOf(
        "content" to listOf(linkedMapOf("type" to "text", "text" to text)), "isError" to false
    )

    private fun err(text: String): Map<String, Any?> = linkedMapOf(
        "content" to listOf(linkedMapOf("type" to "text", "text" to text)), "isError" to true
    )

    private val toolSpecs: List<ToolSpec> by lazy {
        listOf(
            tool("speak", "让 AI 说的话通过悬浮球朗读并显示出来（TTS）。参数 text 为要说的文字；quiet=true 时只显示气泡不发声。返回结果真实反映是否出声。",
                required = listOf("text"),
                properties = mapOf(
                    "text" to mapOf("type" to "string", "description" to "AI 要「说」出来的话"),
                    "quiet" to mapOf("type" to "boolean", "description" to "true=静音模式，只显示气泡不发声（默认 false）")
                )
            ) { ctx ->
                val text = requiredString(ctx, "text")
                val quiet = (ctx.args["quiet"] as? Boolean) ?: false
                val fs = FloatingService.instance ?: return@tool ok("（悬浮球未运行，已记录）")
                val spoken = fs.speak(text, quiet)
                when {
                    quiet -> ok("已静默显示：$text")
                    spoken -> ok("已朗读：$text")
                    else -> err("失败：TTS 未就绪，请稍后重试")
                }
            },

            tool("speak_quiet", "只把文字显示在悬浮气泡里，不发声（等价于 speak 的静音模式）。",
                required = listOf("text"),
                properties = mapOf("text" to mapOf("type" to "string", "description" to "要显示的话"))
            ) { ctx ->
                val text = requiredString(ctx, "text")
                FloatingService.instance?.speak(text, true)
                ok("已静默显示：$text")
            },

            tool("listen_start", "开始听用户说话。mode=push 为按住说话（松手即停）；mode=auto 为自动倾听（说完自动续听，直到 listen_stop 或急停）。识别结果会通过 SSE 推回 AI。",
                required = listOf("mode"),
                properties = mapOf("mode" to mapOf("type" to "string", "description" to "push 或 auto（默认 push）"))
            ) { ctx ->
                val mode = (ctx.args["mode"] as? String)?.lowercase()?.takeIf { it == "auto" || it == "push" } ?: "push"
                if (!SpeechAvailable) return@tool ok("设备不支持语音识别：listen_start 不可用")
                FloatingService.instance?.startListen(mode)
                ok("已开始倾听（mode=$mode）")
            },

            tool("listen_stop", "停止倾听。",
                properties = emptyMap()
            ) { _ ->
                FloatingService.instance?.stopListen()
                ok("已停止倾听")
            },

            tool("get_transcript", "取回最近一次用户说的话（ASR 识别结果）。",
                properties = emptyMap()
            ) { _ ->
                val t = FloatingService.instance?.lastTranscript ?: ""
                ok(if (t.isNotEmpty()) "用户刚才说：$t" else "还没有收到用户的话")
            },

            tool("clear_bubble", "清空悬浮球上的对话气泡。",
                properties = emptyMap()
            ) { _ ->
                FloatingService.instance?.clearBubbles()
                ok("已清空对话气泡")
            },

            tool("set_mood", "设置悬浮球状态色。state=idle（空闲·半透明）/ thinking（思考·呼吸）/ speaking（说话·高亮）。",
                required = listOf("state"),
                properties = mapOf("state" to mapOf("type" to "string", "description" to "idle / thinking / speaking"))
            ) { ctx ->
                val state = (ctx.args["state"] as? String)?.lowercase() ?: "idle"
                FloatingService.instance?.setMood(state)
                ok("悬浮球状态已设为：$state")
            },

            tool("emergency_stop", "立刻急停：停止朗读、停止倾听，后续所有操作被拒绝，直到用户在手机上解除。",
                properties = emptyMap()
            ) { ctx ->
                EmergencyStop.trigger(context)
                LogStore.operation("指令", "AI「${ctx.aiName}」emergency_stop", "成功：已急停")
                ctx.aiId?.let { TrustCenter.recordActivity(it) }
                ok("已急停：所有操作将被拒绝，直到用户解除。")
            },

            tool("get_logs", "读对话日志（敏感内容已打码）。每行：时间 | 类型 | 详情 | 结果。",
                properties = mapOf(
                    "limit" to mapOf("type" to "number", "description" to "返回最近多少条，默认 50，上限 300"),
                    "day" to mapOf("type" to "string", "description" to "日志日期 yyyy-MM-dd，默认今天")
                )
            ) { ctx ->
                val limit = (numArg(ctx.args, "limit") ?: 50.0).toInt().coerceIn(1, 300)
                val day = strArg(ctx.args, "day")?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: LogStore.today()
                val lines = LogStore.readLog(context, day)
                val tail = if (lines.size > limit) lines.subList(lines.size - limit, lines.size) else lines
                ok("共 ${lines.size} 条，返回最近 ${tail.size} 条：\n" + tail.joinToString("\n"))
            },

            tool("get_status", "Rwant 当前状态：急停是否生效、TTS 是否就绪、悬浮球状态、授权 AI、通道地址、版本。",
                properties = emptyMap()
            ) { _ ->
                val metrics = context.resources.displayMetrics
                ok(MiniJson.write(linkedMapOf(
                    "server" to SERVER_NAME, "version" to SERVER_VERSION, "protocol" to LATEST_PROTOCOL_VERSION,
                    "emergencyStop" to EmergencyStop.isActive(),
                    "ttsReady" to (FloatingService.instance?.ttsReady() ?: false),
                    "mood" to (FloatingService.instance?.mood ?: "n/a"),
                    "floatingRunning" to FloatingService.isRunning,
                    "authorizedAis" to TrustCenter.allProfiles().count { TrustCenter.connectionGate(it) == null },
                    "endpoint" to McpServerService.endpointUrl(context),
                    "screen" to linkedMapOf("width" to metrics.widthPixels, "height" to metrics.heightPixels)
                )))
            }
        )
    }

    private val SpeechAvailable: Boolean
        get() = android.speech.SpeechRecognizer.isRecognitionAvailable(context)

    private fun strArg(args: Map<String, Any?>, key: String): String? = args[key]?.toString()
    private fun numArg(args: Map<String, Any?>, key: String): Double? {
        val v = args[key] ?: return null
        return when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }
    }
    private fun requiredString(ctx: ToolContext, key: String): String {
        val v = strArg(ctx.args, key)
        if (v.isNullOrEmpty()) throw IllegalArgumentException("缺少参数 $key（String）")
        return v
    }

    // ---------------------------------------------------------------- SSE 枢纽

    private class SseHub {
        private val handles = CopyOnWriteArrayList<MiniHttpServer.StreamHandle>()
        private val handlesLock = Any()
        @Volatile private var heartbeatStarted = false

        fun register(handle: MiniHttpServer.StreamHandle) { handles.add(handle); startHeartbeat() }

        fun broadcast(event: String, data: String): Int {
            var delivered = 0
            for (h in handles) if (h.sendEvent(event, data)) delivered++
            cleanup()
            return delivered
        }

        fun closeAll() = synchronized(handlesLock) { for (h in handles) h.markClosed(); handles.clear() }

        private fun cleanup() = synchronized(handlesLock) { handles.removeAll { it.isClosed() } }

        private fun startHeartbeat() {
            synchronized(handlesLock) { if (heartbeatStarted) return; heartbeatStarted = true }
            val thread = Thread({
                while (true) {
                    try { Thread.sleep(30_000L) } catch (e: InterruptedException) {
                        synchronized(handlesLock) { heartbeatStarted = false }; break
                    }
                    for (h in handles) if (!h.sendComment("keepalive")) cleanup()
                    if (handles.isEmpty()) { synchronized(handlesLock) { heartbeatStarted = false }; break }
                }
            }, "RwantMcp-SseHeartbeat")
            thread.isDaemon = true; thread.start()
        }
    }
}
