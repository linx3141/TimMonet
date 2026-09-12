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

    /** 服务绑定后要跑一次的同步任务（见 [syncOnBind]）。 */
    private val onBindTasks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    Log.i(TAG, "xposed service bound")
                    RemoteSettingsWriter.service = service
                    pending?.let { push(it) }
                    onBindTasks.forEach { task -> runCatching { task() } }
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

    /**
     * 服务可用后同步一次：远端更新（用户可能是在 TIM 进程内的入口改的）就用远端，
     * 否则把本地值推上去（首次安装远端还是空的）。少了这一步，用本地旧值打开
     * 设置页会把 TIM 侧刚改的设置推回去。
     */
    fun syncOnBind(
        localSettings: AppSettings,
        localRevision: Long,
        onAdoptRemote: (AppSettings) -> Unit
    ) {
        val task = {
            val current = service
            if (current != null) {
                runCatching {
                    val remotePrefs =
                        current.getRemotePreferences(TimMonetSettings.REMOTE_PREFS_NAME)
                    if (TimMonetSettings.revision(remotePrefs) >= localRevision) {
                        Log.i(TAG, "adopt newer remote settings")
                        onAdoptRemote(TimMonetSettings.read(remotePrefs))
                    } else {
                        Log.i(TAG, "remote settings older, pushing local")
                        push(localSettings)
                    }
                }
            }
            Unit
        }
        if (service != null) {
            task()
        } else {
            onBindTasks.add(task)
            init()
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
