package com.hnnrry.rwant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hnnrry.rwant.databinding.ActivityMainBinding

/**
 * 设置页（给 AI 的「嘴」的授权中心 + 状态开关）。
 *
 * 职责：
 *   - 「接收 AI 连接」总开关：开 → 起 MCP 通道 + 悬浮球；关 → 停通道；
 *   - 展示通道地址与连接令牌，令牌可一键重置；
 *   - 权限引导：悬浮窗 / 麦克风 / 后台无限制；
 *   - 音色：音量、语速滑块，实时生效；自动倾听开关；
 *   - AI 请求连接时前台「连接请求卡片」+ 信任中心（轮询刷新，不依赖广播，避免 MIUI 丢广播导致入口丢失）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var connected = false

    /** 前台轮询刷新连接请求卡片 + 信任中心（不依赖广播，避免 MIUI 丢广播导致入口丢失） */
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshRequestCard()
            refreshTrustList()
            pollHandler.postDelayed(this, 1000)
        }
    }

    private val lastOpListener: (String) -> Unit = { runOnUiThread { binding.tvLastOp.text = "最近操作：$it" } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogStore.init(this)
        EmergencyStop.init(this)
        TrustCenter.init(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggleConnection.setOnClickListener { toggleConnection() }
        binding.btnResetToken.setOnClickListener {
            TrustCenter.resetToken(this)
            refreshChannel()
            Toast.makeText(this, "令牌已重置，旧令牌失效", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyAddr.setOnClickListener {
            val addr = if (McpServerService.isRunning) McpServerService.endpointUrl(this) else ""
            if (addr.isNotEmpty()) copyToClipboard("Rwant 通道地址", addr)
            else Toast.makeText(this, "通道未启动，请先开启连接", Toast.LENGTH_SHORT).show()
        }
        binding.btnCopyToken.setOnClickListener {
            val token = TrustCenter.getToken(this)
            copyToClipboard("Rwant 连接令牌", token)
        }

        binding.btnOverlay.setOnClickListener { requestOverlay() }
        binding.btnMic.setOnClickListener { requestMic() }
        binding.btnBattery.setOnClickListener { requestBattery() }
        binding.btnClearLogs.setOnClickListener { FloatingService.instance?.clearBubbles() }

        binding.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                FloatingService.instance?.setVolume(p / 100f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        binding.seekRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                FloatingService.instance?.setRate(p / 100f)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        binding.switchAutoListen.setOnCheckedChangeListener { _, isOn ->
            if (isOn) FloatingService.instance?.startListen("auto")
            else FloatingService.instance?.stopListen()
        }

        LogStore.addOperationListener(lastOpListener)
    }

    override fun onResume() {
        super.onResume()
        TrustCenter.mainUiInForeground = true
        refreshChannel()
        refreshPermissions()
        binding.tvLastOp.text = "最近操作：${LogStore.lastOperation}"
        pollHandler.post(pollRunnable)
    }

    override fun onPause() {
        TrustCenter.mainUiInForeground = false
        pollHandler.removeCallbacks(pollRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        LogStore.removeOperationListener(lastOpListener)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 连接

    private fun toggleConnection() {
        connected = !connected
        if (connected) {
            // 悬浮球是 UI 基础，先确保起来
            if (!FloatingService.isRunning) FloatingService.start(this)
            McpServerService.start(this)
            binding.btnToggleConnection.text = "接收 AI 连接：开"
        } else {
            McpServerService.stop(this)
            binding.btnToggleConnection.text = "接收 AI 连接：关"
        }
        refreshChannel()
    }

    private fun refreshChannel() {
        binding.tvChannel.text = if (McpServerService.isRunning) "通道地址：${McpServerService.endpointUrl(this)}" else "通道地址：未启动"
        binding.tvToken.text = "令牌：${TrustCenter.getToken(this)}"
        binding.btnToggleConnection.text = if (McpServerService.isRunning) "接收 AI 连接：开" else "接收 AI 连接：关"
        connected = McpServerService.isRunning
    }

    private fun applyVolume(v: Float) { FloatingService.instance?.setVolume(v) }
    private fun applyRate(r: Float) { FloatingService.instance?.setRate(r) }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "已复制：${text.take(16)}…", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- 权限

    private fun refreshPermissions() {
        val overlay = Settings.canDrawOverlays(this)
        binding.tvStatusOverlay.text = "悬浮窗：${if (overlay) "已授权" else "未授权"}"
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        binding.tvStatusMic.text = "麦克风：${if (mic) "已授权" else "未授权"}"
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        binding.tvStatusBattery.text = "后台无限制：${if (pm.isIgnoringBatteryOptimizations(packageName)) "已开启" else "建议开启"}"
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) { Toast.makeText(this, "悬浮窗已授权", Toast.LENGTH_SHORT).show(); return }
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun requestMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "麦克风已授权", Toast.LENGTH_SHORT).show(); return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 0x1002)
    }

    private fun requestBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { Toast.makeText(this, "已加入后台无限制", Toast.LENGTH_SHORT).show(); return }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    // ---------------------------------------------------------------- 连接请求卡片 + 信任中心（轮询刷新，不依赖广播）

    private fun refreshRequestCard() {
        val pending = TrustCenter.allProfiles().firstOrNull { !it.approved }
        if (pending != null) {
            binding.cardRequest.visibility = View.VISIBLE
            binding.tvNoRequest.visibility = View.GONE
            binding.tvRequestName.text = "「${pending.name}」请求连接 Rwant"
            binding.btnApproveRequest.text = "同意「${pending.name}」连接"
            binding.btnApproveRequest.setOnClickListener {
                TrustCenter.approveAi(this, pending.id)
                Toast.makeText(this, "已同意「${pending.name}」", Toast.LENGTH_SHORT).show()
            }
        } else {
            binding.cardRequest.visibility = View.GONE
            binding.tvNoRequest.visibility = View.VISIBLE
        }
    }

    private fun refreshTrustList() {
        val list = binding.trustList
        list.removeAllViews()
        val profiles = TrustCenter.allProfiles()
        binding.tvTrustEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        for (p in profiles) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#FFFFFF"))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 0, dp(8))
                layoutParams = lp
            }
            card.addView(TextView(this).apply {
                text = "${p.name} · ${TrustCenter.statusLine(p)}"
                setTextColor(Color.parseColor("#1A1A1A")); textSize = 14f
            })
            val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            if (!p.approved) {
                btnRow.addView(makeTrustBtn("同意") { TrustCenter.approveAi(this, p.id); Toast.makeText(this, "已同意「${p.name}」", Toast.LENGTH_SHORT).show() })
                btnRow.addView(makeTrustBtn("拒绝") { TrustCenter.revokeAi(this, p.id); Toast.makeText(this, "已拒绝「${p.name}」", Toast.LENGTH_SHORT).show() })
            } else {
                btnRow.addView(makeTrustBtn("撤销") { TrustCenter.revokeAi(this, p.id); Toast.makeText(this, "已撤销「${p.name}」", Toast.LENGTH_SHORT).show() })
                btnRow.addView(makeTrustBtn("延期30天") { TrustCenter.setExpiry(this, p.id, TrustCenter.EXPIRY_PRESETS[4]); Toast.makeText(this, "已延期「${p.name}」", Toast.LENGTH_SHORT).show() })
            }
            card.addView(btnRow)
            list.addView(card)
        }
    }

    private fun makeTrustBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE); textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#1A1A1A"))
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, dp(8), 0)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
