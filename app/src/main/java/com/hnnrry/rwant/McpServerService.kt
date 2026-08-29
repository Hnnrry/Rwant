package com.hnnrry.rwant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * MCP 通道服务（给 AI 的「嘴」的联网层）：前台常驻，跑 MiniHttpServer + McpProtocol。
 * 绑定全部网卡，AI 从局域网连进来；所有请求过 Bearer 令牌 + 连接审批。
 */
class McpServerService : Service() {

    companion object {
        private const val TAG = "RwantMcpService"
        private const val CHANNEL_ID = "rwant_mcp_channel"
        private const val NOTIFICATION_ID = 3001
        private const val ACTION_STOP_SERVER = "com.hnnrry.rwant.MCP_STOP"

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var lastError: String? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, McpServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) { context.stopService(Intent(context, McpServerService::class.java)) }

        fun endpointUrl(context: Context): String {
            val port = TrustCenter.getPort(context)
            val ip = localIpv4()
            return if (ip != null) "http://$ip:$port/mcp" else "http://<手机IP>:$port/mcp"
        }

        fun localIpv4(): String? {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
                for (ni in Collections.list(interfaces)) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (address in Collections.list(ni.inetAddresses)) {
                        if (address is InetAddress && !address.isLoopbackAddress &&
                            address.address.size == 4 && address.isSiteLocalAddress
                        ) return address.hostAddress
                    }
                }
                null
            } catch (e: Exception) {
                Log.w(TAG, "枚举网卡失败：${e.javaClass.simpleName}: ${e.message}"); null
            }
        }
    }

    private var server: MiniHttpServer? = null
    private var protocol: McpProtocol? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = false
        LogStore.init(this)
        EmergencyStop.init(this)
        TrustCenter.init(this)
        createNotificationChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("启动中…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVER) { stopSelf(); return START_NOT_STICKY }
        startServerIfNeeded()
        return START_STICKY
    }

    private fun startServerIfNeeded() {
        if (server != null) { refreshNotification(); return }
        val port = TrustCenter.getPort(this)
        val protocolHandler = McpProtocol(this)
        val httpServer = MiniHttpServer(port, protocolHandler)
        try {
            httpServer.start()
        } catch (e: Exception) {
            lastError = "端口 $port 启动失败：${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, lastError, e)
            LogStore.operation("指令", "启动 MCP 通道", "失败：$lastError")
            protocolHandler.shutdown()
            refreshNotification()
            return
        }
        server = httpServer
        protocol = protocolHandler
        lastError = null
        isRunning = true
        LogStore.operation("指令", "启动 MCP 通道", "成功：监听端口 $port（Bearer 令牌鉴权）")
        refreshNotification()
    }

    override fun onDestroy() {
        runCatching { protocol?.shutdown() }
        runCatching { server?.stop() }
        server = null; protocol = null
        isRunning = false
        LogStore.event("MCP 通道已停止")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Rwant MCP 通道", NotificationManager.IMPORTANCE_LOW).apply {
            description = "AI 连接 Rwant 的本地通道状态"; setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, McpServerService::class.java).setAction(ACTION_STOP_SERVER), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = "$statusText\n最近操作：${LogStore.lastOperation} @${LogStore.lastOperationAt}"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Rwant MCP 通道")
            .setContentText(text.split("\n").firstOrNull() ?: statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openIntent).addAction(0, "停止通道", stopIntent).build()
    }

    private fun refreshNotification() {
        runCatching {
            val status = if (isRunning) "已开放：${endpointUrl(this)}" else (lastError ?: "未开放")
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(status))
        }
    }
}
