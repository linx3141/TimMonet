package com.timmonet.core

import android.content.SharedPreferences
import android.util.Log
import com.timmonet.settings.TimMonetSettings
import com.timmonet.ui.theme.AppSettings
import io.github.libxposed.api.XposedModule

/**
 * TIM 进程侧：通过 LSPosed 远端 SharedPreferences 读取模块设置。
 */
object SettingsBridge {

    private const val TAG = "TimMonet"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var module: XposedModule? = null

    @Volatile
    var current: AppSettings = TimMonetSettings.defaults()
        private set

    fun attach(module: XposedModule) {
        this.module = module
        try {
            val remotePrefs = module.getRemotePreferences(TimMonetSettings.REMOTE_PREFS_NAME)
            prefs = remotePrefs
            current = TimMonetSettings.read(remotePrefs)
            remotePrefs.registerOnSharedPreferenceChangeListener { _, _ ->
                val next = TimMonetSettings.read(remotePrefs)
                // 颜色相关设置(色彩标准/深浅模式/色彩风格/色域)变化时，热刷新
                // 无法覆盖底栏/顶栏等已绘制区域 → 直接强停 TIM，重启后全量
                // 按新配色构建。
                val paletteChanged = next.colorMode != current.colorMode ||
                    next.paletteStyle != current.paletteStyle ||
                    next.colorSpec != current.colorSpec ||
                    next.keyColor != current.keyColor
                current = next
                if (paletteChanged) {
                    killTimProcess()
                }
                MonetPalette.onSettingsChanged()
            }
            Log.i(TAG, "remote settings attached: ${current.paletteStyle}/${current.colorSpec}")
        } catch (t: Throwable) {
            Log.e(TAG, "attach remote settings failed", t)
        }
    }

    /** 颜色相关设置变更 → 结束 TIM 进程（仅在 TIM 进程内执行；用户重开即全量新配色）。 */
    private fun killTimProcess() {
        try {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
                ?: return
            if (app.packageName != "com.tencent.tim") return
            Log.i(TAG, "palette settings changed, restarting TIM")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    android.os.Process.killProcess(android.os.Process.myPid())
                } catch (t: Throwable) {
                    // ignore
                }
            }, 300L)
        } catch (t: Throwable) {
            // ignore
        }
    }

    fun readCurrent(): AppSettings {
        val remoteModule = module ?: return current
        return try {
            // RemotePreferences 是获取时的快照，模块 UI 在另一进程写入后不会自动刷新，
            // 因此每次轮询都重新向框架请求最新数据。
            val next = TimMonetSettings.read(
                remoteModule.getRemotePreferences(TimMonetSettings.REMOTE_PREFS_NAME)
            )
            if (next != current) {
                current = next
            }
            current
        } catch (t: Throwable) {
            current
        }
    }
}
