package com.hnnrry.rwant

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 急停（给 AI 的「嘴」的安全总闸）。
 *
 * 比 Ridea 的简单：Rwant 没有手势/截屏，急停只做三件事——
 *   ① 持久化急停状态（SharedPreferences，进程死掉再起来也还是急停）；
 *   ② 震动 80ms 给触觉反馈；
 *   ③ 通知监听者（TTS 停播、ASR 停听）。
 *
 * 与「手」同构：急停永远是用户的最后手段，AI 调用 emergency_stop 工具即触发。
 */
object EmergencyStop {

    const val REJECTED_MESSAGE = "已急停：所有操作被拒绝"
    const val REJECTED_SHORT = "急停"

    private const val PREF_NAME = "rwant_emergency"
    private const val KEY_ACTIVE = "active"

    @Volatile
    private var active: Boolean = false

    private var prefs: SharedPreferences? = null

    /** 状态变化监听（TTS/ASR 据此停掉自己），回调在调用线程上触发 */
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun init(context: Context) {
        val app = context.applicationContext
        if (prefs == null) {
            prefs = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
        active = prefs?.getBoolean(KEY_ACTIVE, false) ?: false
    }

    fun isActive(): Boolean = active

    /** 触发急停：持久化 + 震动 + 通知监听者 */
    fun trigger(context: Context) {
        active = true
        prefs?.edit()?.putBoolean(KEY_ACTIVE, true)?.apply()
        vibrate(context)
        for (l in listeners) runCatching { l(true) }
    }

    /** 解除急停 */
    fun resume(context: Context) {
        active = false
        prefs?.edit()?.putBoolean(KEY_ACTIVE, false)?.apply()
        for (l in listeners) runCatching { l(false) }
    }

    fun addListener(l: (Boolean) -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: (Boolean) -> Unit) {
        listeners.remove(l)
    }

    private fun vibrate(context: Context) {
        runCatching {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(80)
            }
        }
    }
}
