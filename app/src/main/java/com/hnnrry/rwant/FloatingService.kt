package com.hnnrry.rwant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo

/**
 * 悬浮球 + 气泡面板（给 AI 的「嘴」的 UI 核心）。
 *
 * 职责：
 *   - 任意 App 上方常驻一个 48dp 悬浮球，三态变色：空闲(半透明) / 思考(呼吸) / 说话(高亮)；
 *   - 点球展开深色对话面板：AI 说话流式气泡、用户消息小气泡、最近对话可滚动、可清空；
 *   - 麦克风按住说话（push）/ 自动倾听（auto），识别结果回传 AI；
 *   - 持有 TTS / ASR 引擎，把 AI 的话播出来、把用户的话收回来。
 *
 * 它不思考：所有内容都来自 AI（通过 McpServerService 调进来）或用户（麦克风）。
 */
class FloatingService : Service() {

    companion object {
        private const val TAG = "RwantFloat"
        private const val CHANNEL_ID = "rwant_floating_v1"
        private const val NOTIFICATION_ID = 2001

        @Volatile
        var isRunning: Boolean = false
            private set

        /** McpProtocol 调用的入口（服务存活期间非空） */
        @Volatile
        var instance: FloatingService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var tts: TtsEngine? = null
    private var asr: AsrEngine? = null

    /** 最近一次用户说的话（get_transcript 工具取这个） */
    @Volatile var lastTranscript: String = ""
        private set

    /** 悬浮球状态：idle / thinking / speaking（get_status 工具读这个） */
    @Volatile var mood: String = "idle"
        private set

    // ---------------------------------------------------------------- 生命周期

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        LogStore.init(this)
        EmergencyStop.init(this)
        TrustCenter.init(this)

        tts = TtsEngine(this)
        tts?.onState = { speaking -> if (!speaking && mood == "speaking") setMood("idle") }

        asr = AsrEngine(this).apply {
            onPartial = { txt -> runOnUi { updateStatus("正在听：${txt.take(12)}…") } }
            onFinal = { txt ->
                lastTranscript = txt
                showUserBubble(txt)
                LogStore.operation("用户", "说", txt)
                McpProtocol.current?.pushTranscript(txt)
                if (!asr!!.listening) updateStatus("空闲")
            }
            onError = { msg -> runOnUi { updateStatus("听写出错：$msg") } }
            onStop = { if (mood != "speaking") updateStatus("空闲") }
        }

        EmergencyStop.addListener { stopped ->
            if (stopped) { tts?.stop(); asr?.stop() }
        }

        createNotificationChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else 0
        )
        attachBall()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { tts?.shutdown() }
        runCatching { asr?.destroy() }
        removeBall()
        removePanel()
        isRunning = false
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 悬浮球

    private fun attachBall() {
        val size = dp(48)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16); y = dp(160)
        }
        val ball = TextView(this).apply {
            text = "🗣"
            textSize = 22f
            gravity = Gravity.CENTER
            background = ballDrawable(moodColor(mood))
            setOnTouchListener(ballDragListener(params))
            setOnClickListener { togglePanel() }
        }
        runCatching {
            windowManager.addView(ball, params)
            ballView = ball; ballParams = params
        }
    }

    private fun removeBall() {
        ballView?.let { runCatching { windowManager.removeView(it) } }
        ballView = null; ballParams = null
    }

    private fun ballDragListener(params: WindowManager.LayoutParams): View.OnTouchListener {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    runCatching { windowManager.updateViewLayout(ballView, params) }
                    true
                }
                else -> false
            }
        }
    }

    private var pulseAnim: android.animation.ValueAnimator? = null

    /** 设置悬浮球状态色：idle / thinking / speaking */
    fun setMood(state: String) {
        mood = state
        runOnUi {
            (ballView?.background as? GradientDrawable)?.setColor(moodColor(state))
            when (state) {
                "thinking" -> startPulse()
                else -> stopPulse()
            }
            updateStatus(
                when (state) {
                    "thinking" -> "AI 思考中…"
                    "speaking" -> "AI 说话中…"
                    else -> "空闲"
                }
            )
        }
    }

    private fun startPulse() {
        stopPulse()
        val drawable = ballView?.background as? GradientDrawable ?: return
        pulseAnim = android.animation.ValueAnimator.ofFloat(0.55f, 1f).apply {
            duration = 900
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { anim ->
                val a = anim.animatedValue as Float
                drawable.alpha = (a * 255).toInt().coerceIn(0, 255)
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnim?.cancel(); pulseAnim = null
        (ballView?.background as? GradientDrawable)?.alpha = 255
    }

    private fun moodColor(state: String): Int = when (state) {
        "thinking" -> Color.argb(200, 83, 74, 183)
        "speaking" -> Color.argb(255, 130, 110, 245)
        else -> Color.argb(170, 90, 90, 110)
    }

    private fun ballDrawable(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2), Color.argb(120, 255, 255, 255))
        }

    // ---------------------------------------------------------------- 气泡面板

    private fun togglePanel() {
        if (panelView != null) removePanel() else attachPanel()
    }

    private fun attachPanel() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12); y = dp(90)
        }
        val panel = layoutInflater.inflate(R.layout.floating_panel, null)
        panel.findViewById<View>(R.id.btnCollapse).setOnClickListener { removePanel() }
        panel.findViewById<View>(R.id.btnClear).setOnClickListener { clearBubbles() }

        val mic = panel.findViewById<TextView>(R.id.btnMic)
        mic.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startListen("push"); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stopListen(); true }
                else -> false
            }
        }
        runCatching {
            windowManager.addView(panel, params)
            panelView = panel; panelParams = params
        }
    }

    private fun removePanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null; panelParams = null
    }

    private fun messageList(): LinearLayout? = panelView?.findViewById(R.id.messageList)
    private fun scrollView(): ScrollView? = panelView?.findViewById(R.id.scrollView)
    private fun updateStatus(text: String) {
        panelView?.findViewById<TextView>(R.id.tvStatus)?.text = text
    }

    /** AI 说话：流出气泡（quiet=true 时不发声，只显示） */
    fun speak(text: String, quiet: Boolean = false) {
        if (EmergencyStop.isActive()) return
        LogStore.operation("AI", "说", if (quiet) "（静音）$text" else text)
        runOnUi {
            ensurePanelForBubble()
            addBubble(text, isUser = false)
            setMood("speaking")
        }
        tts?.speak(text, quiet)
    }

    /** 用户说话：右侧气泡 */
    fun showUserBubble(text: String) {
        runOnUi {
            ensurePanelForBubble()
            addBubble(text, isUser = true)
        }
    }

    private fun ensurePanelForBubble() {
        if (panelView == null) attachPanel()
    }

    private fun addBubble(text: String, isUser: Boolean) {
        val list = messageList() ?: return
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(if (isUser) Color.argb(255, 70, 110, 200) else Color.argb(255, 60, 54, 110))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(6), 0, dp(6))
                gravity = if (isUser) Gravity.END else Gravity.START
                width = (resources.displayMetrics.widthPixels * 0.62).toInt().coerceAtMost(dp(220))
            }
            layoutParams = lp
        }
        list.addView(bubble)
        scrollView()?.post { scrollView()?.fullScroll(View.FOCUS_DOWN) }
    }

    fun clearBubbles() {
        runOnUi { messageList()?.removeAllViews() }
        LogStore.operation("指令", "清空对话气泡", "已清空")
    }

    // ---------------------------------------------------------------- 听（ASR）

    fun startListen(mode: String) {
        if (EmergencyStop.isActive()) { runOnUi { updateStatus("已急停：无法倾听") }; return }
        asr?.start(mode)
        updateStatus(if (mode == "auto") "自动倾听中…" else "按住说话中…")
    }

    fun stopListen() {
        asr?.stop()
    }

    /** 设置 TTS 音量（0.0 ~ 1.0） */
    fun setVolume(v: Float) { tts?.volume = v.coerceIn(0f, 1f) }

    /** 设置 TTS 语速（0.5 ~ 2.0） */
    fun setRate(r: Float) { tts?.rate = r.coerceIn(0.5f, 2f) }

    // ---------------------------------------------------------------- 工具

    private fun runOnUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ---------------------------------------------------------------- 通知

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Rwant 悬浮球", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Rwant 常驻悬浮球与对话状态"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("悬浮球常驻中 · 点按展开对话")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .build()
    }
}
