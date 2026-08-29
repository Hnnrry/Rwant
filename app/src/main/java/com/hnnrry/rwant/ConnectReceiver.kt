package com.hnnrry.rwant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 后台时 AI 连接请求的「同意 / 拒绝」广播接收器（前台走 MainActivity 弹窗）。
 * 通知栏按钮 → 这里 → TrustCenter 真正改授权状态。
 */
class ConnectReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_APPROVE = "com.hnnrry.rwant.CONNECT_APPROVE"
        const val ACTION_DENY = "com.hnnrry.rwant.CONNECT_DENY"
        const val EXTRA_AI_ID = "ai_id"
        const val EXTRA_AI_NAME = "ai_name"

        fun approveIntent(context: Context, aiId: String, aiName: String): Intent =
            Intent(context, ConnectReceiver::class.java).apply {
                action = ACTION_APPROVE
                putExtra(EXTRA_AI_ID, aiId)
                putExtra(EXTRA_AI_NAME, aiName)
            }

        fun denyIntent(context: Context, aiId: String, aiName: String): Intent =
            Intent(context, ConnectReceiver::class.java).apply {
                action = ACTION_DENY
                putExtra(EXTRA_AI_ID, aiId)
                putExtra(EXTRA_AI_NAME, aiName)
            }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val aiId = intent?.getStringExtra(EXTRA_AI_ID) ?: return
        when (intent.action) {
            ACTION_APPROVE -> TrustCenter.approveAi(context, aiId)
            ACTION_DENY -> TrustCenter.revokeAi(context, aiId)
        }
    }
}
