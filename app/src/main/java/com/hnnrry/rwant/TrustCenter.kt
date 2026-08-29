package com.hnnrry.rwant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 信任中心（Rwant 的授权管理，沿用 Ridea 三层授权模型，但精简掉「手势/付款」相关部分）。
 *
 * 对 Rwant（嘴）来说，AI 只是来「说话 + 听」。授权模型：
 *   ① 总闸：按 AI 授权 + 期限（1 小时 ~ 4 个月，默认当天 24 点），到期自动失效；
 *      系统权限（悬浮窗/麦克风）开了不用关 —— AI 已失去使用资格，判权时直接拒绝。
 *   ② 连接令牌：SecureRandom 生成，Bearer 鉴权用；用户在控制台查看/复制/重置。
 *
 * 不做裁判：本类只回答「这个 AI 此刻有没有资格连」，不判断内容对错。
 * 持久化：SharedPreferences + MiniJson 序列化 AI 档案。到期是「判权时惰性检查」，不需要后台定时器。
 */
object TrustCenter {

    private const val TAG = "RwantTrust"
    private const val PREF_NAME = "rwant_trust"
    private const val KEY_TOKEN = "connection_token"
    private const val KEY_PORT = "mcp_port"
    private const val KEY_AI_PREFIX = "ai_"

    /** MCP 端口默认值（避开 Ridea 的 8765） */
    const val DEFAULT_PORT = 8766

    private const val REQUEST_NOTIFY_INTERVAL_MS = 5_000L

    // ------------------------------------------------------------ 期限预设（授权模型第一层）

    data class ExpiryPreset(val label: String, val durationMillis: Long?)

    val EXPIRY_PRESETS = listOf(
        ExpiryPreset("1 小时", 1L * 60 * 60 * 1000),
        ExpiryPreset("6 小时", 6L * 60 * 60 * 1000),
        ExpiryPreset("当天 24 点（默认）", null),
        ExpiryPreset("7 天", 7L * 24 * 60 * 60 * 1000),
        ExpiryPreset("30 天", 30L * 24 * 60 * 60 * 1000),
        ExpiryPreset("4 个月（最长）", 120L * 24 * 60 * 60 * 1000)
    )

    // ------------------------------------------------------------ AI 档案

    class AiProfile(
        val id: String,
        var name: String,
        var clientVersion: String = "",
        var approved: Boolean = false,
        var approvedAt: Long = 0,
        var expiresAt: Long = 0,
        var lastActiveAt: Long = 0,
        var opCount: Int = 0
    ) {
        fun toJson(): Map<String, Any?> = linkedMapOf(
            "id" to id,
            "name" to name,
            "clientVersion" to clientVersion,
            "approved" to approved,
            "approvedAt" to approvedAt,
            "expiresAt" to expiresAt,
            "lastActiveAt" to lastActiveAt,
            "opCount" to opCount
        )

        companion object {
            fun fromJson(map: Map<*, *>): AiProfile {
                fun longOf(v: Any?): Long = (v as? Number)?.toLong() ?: 0L
                fun intOf(v: Any?): Int = (v as? Number)?.toInt() ?: 0
                return AiProfile(
                    id = map["id"]?.toString() ?: "",
                    name = map["name"]?.toString() ?: "未命名 AI",
                    clientVersion = map["clientVersion"]?.toString() ?: "",
                    approved = map["approved"] as? Boolean == true,
                    approvedAt = longOf(map["approvedAt"]),
                    expiresAt = longOf(map["expiresAt"]),
                    lastActiveAt = longOf(map["lastActiveAt"]),
                    opCount = intOf(map["opCount"])
                )
            }
        }
    }

    // ------------------------------------------------------------ 状态

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var appContext: Context? = null
    private val profiles = ConcurrentHashMap<String, AiProfile>()
    private val lastNotifiedAt = ConcurrentHashMap<String, Long>()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** 主界面是否在前台（MainActivity onResume/onPause 维护）：决定弹窗还是发通知 */
    @Volatile var mainUiInForeground: Boolean = false

    // ------------------------------------------------------------ 初始化

    fun init(context: Context) {
        val app = context.applicationContext
        if (prefs == null) prefs = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        appContext = app
        if (profiles.isEmpty()) {
            val all = prefs?.all ?: return
            for ((key, value) in all) {
                if (!key.startsWith(KEY_AI_PREFIX)) continue
                val id = key.removePrefix(KEY_AI_PREFIX)
                val json = value as? String ?: continue
                runCatching {
                    val map = MiniJson.parse(json) as? Map<*, *> ?: return@runCatching
                    @Suppress("UNCHECKED_CAST")
                    val profile = AiProfile.fromJson(map as Map<*, *>)
                    if (profile.id.isNotEmpty()) profiles[profile.id] = profile
                }.onFailure { Log.w(TAG, "AI 档案解析失败（忽略该条）：$id") }
            }
        }
    }

    private fun requirePrefs(): SharedPreferences =
        prefs ?: throw IllegalStateException("TrustCenter 未初始化：请先调 init(context)")

    private fun persist(profile: AiProfile) {
        requirePrefs().edit().putString(KEY_AI_PREFIX + profile.id, MiniJson.write(profile.toJson())).apply()
    }

    // ------------------------------------------------------------ 连接令牌（Bearer 鉴权）

    fun getToken(context: Context): String {
        init(context)
        requirePrefs().getString(KEY_TOKEN, null)?.let { return it }
        val token = newToken()
        requirePrefs().edit().putString(KEY_TOKEN, token).apply()
        LogStore.event("已生成 MCP 连接令牌（Bearer 鉴权用）")
        return token
    }

    fun resetToken(context: Context): String {
        init(context)
        val token = newToken()
        requirePrefs().edit().putString(KEY_TOKEN, token).apply()
        LogStore.operation("授权", "重置 MCP 连接令牌", "已重置：旧令牌即刻作废")
        return token
    }

    fun isTokenValid(context: Context, token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return getToken(context) == token
    }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // ------------------------------------------------------------ 端口

    fun getPort(context: Context): Int {
        init(context)
        return requirePrefs().getInt(KEY_PORT, DEFAULT_PORT)
    }

    fun setPort(context: Context, port: Int) {
        init(context)
        requirePrefs().edit().putInt(KEY_PORT, port).apply()
        LogStore.operation("授权", "修改 MCP 端口为 $port", "已保存（重启 MCP 通道后生效）")
    }

    // ------------------------------------------------------------ 身份

    fun identityFor(clientName: String): String {
        val normalized = clientName.trim().lowercase(Locale.US).ifEmpty { "unknown" }
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format(Locale.US, "%02x", it) }.take(16)
    }

    // ------------------------------------------------------------ 连接审批（激活仪式的第一步）

    fun requestConnection(context: Context, aiId: String, name: String, clientVersion: String): String? {
        init(context)
        val profile = profiles[aiId]
        if (profile == null) {
            val fresh = AiProfile(id = aiId, name = name, clientVersion = clientVersion)
            profiles[aiId] = fresh
            persist(fresh)
            LogStore.operation("授权", "新 AI 请求连接：$name", "等待用户确认")
            notifyConnectionRequest(fresh)
            return "等待用户在手机上确认连接：请在 Rwant 控制台 / 通知里点「同意」，然后重新 initialize。"
        }
        if (profile.clientVersion != clientVersion) { profile.clientVersion = clientVersion; persist(profile) }
        val gate = connectionGate(profile)
        if (gate == null) return null
        // pending / 已到期：无条件重新触达用户（缺陷2-根因1：避免重复请求静默丢失入口）
        notifyConnectionRequest(profile)
        return gate
    }

    fun connectionGate(profile: AiProfile): String? {
        if (!profile.approved) return "等待用户在手机上确认连接：请在 Rwant 控制台 / 通知里点「同意」，然后重试。"
        if (profile.expiresAt != 0L && System.currentTimeMillis() >= profile.expiresAt) {
            return "授权已到期：请让用户在 Rwant 信任中心为「${profile.name}」重新授权，然后重试。"
        }
        return null
    }

    fun profile(aiId: String): AiProfile? = profiles[aiId]
    fun allProfiles(): List<AiProfile> = profiles.values.sortedByDescending { it.lastActiveAt }

    // ------------------------------------------------------------ 审批 / 撤销 / 期限

    fun approveAi(context: Context, aiId: String, preset: ExpiryPreset = EXPIRY_PRESETS[4]) {  // 默认 30 天（缺陷2-根因5：不再默认当天 24 点）
        init(context)
        val profile = profiles[aiId] ?: return
        profile.approved = true
        profile.approvedAt = System.currentTimeMillis()
        profile.expiresAt = resolveExpiry(preset)
        persist(profile)
        LogStore.operation("授权", "同意 AI「${profile.name}」连接，期限：${preset.label}", "已授权")
        cancelRequestNotification(context, aiId)
    }

    fun revokeAi(context: Context, aiId: String) {
        init(context)
        val profile = profiles[aiId] ?: return
        val wasApproved = profile.approved
        profile.approved = false
        profile.expiresAt = 0
        persist(profile)
        if (wasApproved) {
            LogStore.operation("授权", "撤销 AI「${profile.name}」的授权", "已撤销：该 AI 的所有操作将被拒绝")
        } else {
            LogStore.operation("授权", "拒绝 AI「${profile.name}」的连接请求", "已拒绝")
        }
        cancelRequestNotification(context, aiId)
    }

    fun setExpiry(context: Context, aiId: String, preset: ExpiryPreset) {
        init(context)
        val profile = profiles[aiId] ?: return
        profile.expiresAt = resolveExpiry(preset)
        profile.approved = true
        if (profile.approvedAt == 0L) profile.approvedAt = System.currentTimeMillis()
        persist(profile)
        LogStore.operation("授权", "调整 AI「${profile.name}」期限：${preset.label}", "已生效")
    }

    private fun resolveExpiry(preset: ExpiryPreset): Long {
        val duration = preset.durationMillis ?: run {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
            cal.set(java.util.Calendar.MINUTE, 59)
            cal.set(java.util.Calendar.SECOND, 59)
            cal.set(java.util.Calendar.MILLISECOND, 999)
            return cal.timeInMillis
        }
        return System.currentTimeMillis() + duration
    }

    fun recordActivity(aiId: String) {
        val profile = profiles[aiId] ?: return
        profile.lastActiveAt = System.currentTimeMillis()
        profile.opCount++
        persist(profile)
    }

    // ------------------------------------------------------------ 连接请求的用户触达

    private fun notifyConnectionRequest(profile: AiProfile) {
        val context = appContext ?: return
        val now = System.currentTimeMillis()
        val last = lastNotifiedAt[profile.id] ?: 0L
        if (now - last < REQUEST_NOTIFY_INTERVAL_MS) return
        lastNotifiedAt[profile.id] = now

        if (mainUiInForeground) {
            val intent = Intent(ACTION_CONNECT_REQUEST).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_AI_ID, profile.id)
                putExtra(EXTRA_AI_NAME, profile.name)
            }
            runCatching { context.sendBroadcast(intent) }
            return
        }

        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(manager)
            val approve = PendingIntent.getBroadcast(
                context, (profile.id.hashCode() and 0x7FFF),
                ConnectReceiver.approveIntent(context, profile.id, profile.name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val deny = PendingIntent.getBroadcast(
                context, (profile.id.hashCode() and 0x7FFF) + 1,
                ConnectReceiver.denyIntent(context, profile.id, profile.name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val open = PendingIntent.getActivity(
                context, (profile.id.hashCode() and 0x7FFF) + 2,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("有 AI 想连接 Rwant")
                .setContentText("「${profile.name}」请求把 Rwant 当作它的嘴，是否同意？")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("「${profile.name}」请求把 Rwant 当作它的嘴。\n同意后默认授权 30 天，可随时在信任中心撤销。")
                )
                .setAutoCancel(true)
                .setContentIntent(open)
                .addAction(0, "同意", approve)
                .addAction(0, "拒绝", deny)
                .build()
            manager.notify(notificationIdFor(profile.id), notification)
        }.onFailure { Log.e(TAG, "连接请求通知发送失败：${it.message}") }
    }

    fun cancelRequestNotification(context: Context, aiId: String) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(notificationIdFor(aiId))
        }
    }

    private fun notificationIdFor(aiId: String): Int = (aiId.hashCode() and 0x7FFFFFFF) % 100000 + 30000

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "AI 连接请求", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "有 AI 想连接 Rwant 时提醒用户确认（同意/拒绝）" }
        manager.createNotificationChannel(channel)
    }

    fun statusLine(profile: AiProfile): String {
        if (!profile.approved) return "待确认"
        val gate = connectionGate(profile)
        if (gate != null) return "已过期（等待重新授权）"
        val until = if (profile.expiresAt == 0L) "长期" else "至 " + synchronized(timeFormat) {
            timeFormat.format(Date(profile.expiresAt))
        }
        return "已授权 $until"
    }

    const val CHANNEL_ID = "rwant_trust_channel"
    const val ACTION_CONNECT_REQUEST = "com.hnnrry.rwant.AI_CONNECT_REQUEST"
    const val EXTRA_AI_ID = "ai_id"
    const val EXTRA_AI_NAME = "ai_name"
}
