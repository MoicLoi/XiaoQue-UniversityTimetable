package com.xiqueer.android.data

import android.content.Context

/**
 * 悬浮窗开关。
 *
 * **默认关闭**,而且要用户自己到系统设置里授权。理由见 DESIGN.md §10.3:
 * "在其他应用上层显示"是个很重的权限,一进 App 就弹出来会让人反感;
 * 而锁屏通知已经覆盖了"扫一眼知道现在上什么课"的主要场景。
 */
class OverlaySettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()
}
