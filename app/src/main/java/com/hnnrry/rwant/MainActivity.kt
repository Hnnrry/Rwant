package com.hnnrry.rwant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
 *   - AI 请求连接时前台弹「同意 / 拒绝」（后台走通知，由 ConnectReceiver 处理）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var connected = false

    private val connectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TrustCenter.ACTION_CONNECT_REQUEST) return
            val aiId = intent.getStringExtra(TrustCenter.EXTRA_AI_ID) ?: return
            val aiName = intent.getStringExtra(TrustCenter.EXTRA_AI_NAME) ?: "未命名 AI"
            promptConnectRequest(aiId, aiName)
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
        registerReceiver(connectReceiver, IntentFilter(TrustCenter.ACTION_CONNECT_REQUEST))
    }

    private fun applyVolume(v: Float) {
        // 通过 FloatingService 暴露的 tts 设置音量；tts 为私有，这里用 companion 的 setter
        FloatingService.instance?.let { setTtsVolume(it, v) }
    }
    private fun applyRate(r: Float) {
        FloatingService.instance?.let { setTtsRate(it, r) }
    }

    override fun onResume() {
        super.onResume()
        TrustCenter.mainUiInForeground = true
        refreshChannel()
        refreshPermissions()
        binding.tvLastOp.text = "最近操作：${LogStore.lastOperation}"
    }

    override fun onPause() {
        TrustCenter.mainUiInForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        LogStore.removeOperationListener(lastOpListener)
        runCatching { unregisterReceiver(connectReceiver) }
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
        binding.tvToken.text = "令牌：${TrustCenter.getToken(this).take(12)}…（设置页可见）"
        binding.btnToggleConnection.text = if (McpServerService.isRunning) "接收 AI 连接：开" else "接收 AI 连接：关"
        connected = McpServerService.isRunning
    }

    private fun applyVolume(v: Float) { FloatingService.instance?.setVolume(v) }
    private fun applyRate(r: Float) { FloatingService.instance?.setRate(r) }

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

    // ---------------------------------------------------------------- 连接请求弹窗

    private fun promptConnectRequest(aiId: String, aiName: String) {
        AlertDialog.Builder(this)
            .setTitle("有 AI 想连接 Rwant")
            .setMessage("「$aiName」请求把 Rwant 当作它的嘴。\n\n同意后默认授权到今天 24 点，可随时重置/撤销；每一步操作都会留日志；急停随时可用。")
            .setPositiveButton("同意") { _, _ ->
                TrustCenter.approveAi(this, aiId)
                Toast.makeText(this, "已同意「$aiName」连接 Rwant", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("拒绝") { _, _ -> TrustCenter.revokeAi(this, aiId) }
            .setCancelable(false)
            .show()
    }
}
