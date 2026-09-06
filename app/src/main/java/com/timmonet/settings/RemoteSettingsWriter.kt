package com.timmonet.settings

import android.util.Log
import com.timmonet.ui.theme.AppSettings
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块 UI 写入 LSPosed 远端 SharedPreferences（TIM 进程通过 getRemotePreferences 读取）。
 */
object RemoteSettingsWriter {

    private const val TAG = "TimMonet"

    private val initialized = AtomicBoolean(false)

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var pending: AppSettings? = null

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    Log.i(TAG, "xposed service bound")
                    RemoteSettingsWriter.service = service
                    pending?.let { push(it) }
                }

                override fun onServiceDied(service: XposedService) {
                    if (RemoteSettingsWriter.service === service) {
                        RemoteSettingsWriter.service = null
                    }
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "register xposed service listener failed", t)
        }
    }

    fun push(settings: AppSettings) {
        val current = service
        if (current == null) {
            pending = settings
            init()
            return
        }
        try {
            TimMonetSettings.write(
                current.getRemotePreferences(TimMonetSettings.REMOTE_PREFS_NAME),
                settings
            )
            Log.i(TAG, "remote settings pushed: ${settings.paletteStyle}/${settings.colorSpec}")
        } catch (t: Throwable) {
            Log.e(TAG, "push remote settings failed", t)
            pending = settings
        }
    }
}
