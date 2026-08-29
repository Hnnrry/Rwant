package com.hnnrry.rwant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启：系统开机后把悬浮球拉起来（通道需用户主动开，这里只保活 UI） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            FloatingService.start(context)
        }
    }
}
