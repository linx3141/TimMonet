package com.timmonet.core

import android.content.SharedPreferences
import android.util.Log
import com.timmonet.settings.TimMonetSettings
import com.timmonet.ui.theme.AppSettings
import io.github.libxposed.api.XposedModule

/**
 * TIM 进程侧：通过 LSPosed 远端 SharedPreferences 读取/写入模块设置。
 *
 * 历史坑：`attach()` 一旦抛异常，`prefs` 就停在 null，而 `write()` 是
 * `val prefs = prefs ?: return` —— 于是"在 TIM 进程内改设置"这件事**永久静默失效**：
 * Compose 界面照常显示新值（本地 state 已更新），远端一个字节都没写，
 * TIM 也永远不会按新配色重启，日志里只有 attach 那一刻的一行错误。
 * 现在改成惰性重试：任何一次访问发现还没接上，就再试一次（失败有退避）。
 */
object SettingsBridge {

    private const val TAG = "TimMonet"

    /** 远端偏好接不上时的重试间隔。 */
    private const val ATTACH_RETRY_MS = 5000L

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var module: XposedModule? = null

    /** 是否已注册过变更监听（监听只注册一次，附着本身可重试）。 */
    @Volatile
    private var listenerRegistered = false

    @Volatile
    private var lastAttachAttemptAt = 0L

    /** readCurrent 连续失败计数（只用于日志节流，避免热路径刷屏）。 */
    @Volatile
    private var readFailureCount = 0

    @Volatile
    var current: AppSettings = TimMonetSettings.defaults()
        private set

    fun attach(module: XposedModule) {
        this.module = module
        ensureRemote()
    }

    /**
     * 惰性附着（幂等、带退避）。返回是否拿到了可用的远端偏好。
     *
     * 成功后的调用只是一次 volatile 读，热路径开销可忽略。
     */
    private fun ensureRemote(): SharedPreferences? {
        prefs?.let { return it }
        val m = module ?: return null
        val now = System.currentTimeMillis()
        if (lastAttachAttemptAt != 0L && now - lastAttachAttemptAt < ATTACH_RETRY_MS) return null
        lastAttachAttemptAt = now
        return try {
            val remotePrefs = m.getRemotePreferences(TimMonetSettings.REMOTE_PREFS_NAME)
            prefs = remotePrefs
            current = TimMonetSettings.read(remotePrefs)
            if (!listenerRegistered) {
                listenerRegistered = true
                remotePrefs.registerOnSharedPreferenceChangeListener { _, _ ->
                    val next = TimMonetSettings.read(remotePrefs)
                    // 颜色相关设置(色彩标准/深浅模式/色彩风格/色域)变化时，热刷新
                    // 无法覆盖底栏/顶栏等已绘制区域 → 直接强停 TIM，重启后全量
                    // 按新配色构建。
                    val paletteChanged = next.colorMode != current.colorMode ||
                        next.paletteStyle != current.paletteStyle ||
                        next.colorSpec != current.colorSpec ||
                        next.keyColor != current.keyColor ||
                        next.amoledBlack != current.amoledBlack
                    current = next
                    if (paletteChanged) {
                        killTimProcess()
                    }
                    MonetPalette.onSettingsChanged()
                }
            }
            Log.i(TAG, "remote settings attached: ${current.paletteStyle}/${current.colorSpec}")
            remotePrefs
        } catch (t: Throwable) {
            Log.e(TAG, "attach remote settings failed (will retry)", t)
            prefs = null
            null
        }
    }

    /** 颜色相关设置变更 → 结束 TIM 进程（仅在 TIM 进程内执行；用户重开即全量新配色）。
     *  系统深浅色切换（自动模式下）也走这里，见 hookSystemNightChange。 */
    internal fun killTimProcess() {
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
                    // 强停失败不是致命错误：设置已经写进远端，下次冷启动才全量生效。
                    Log.w(TAG, "killProcess failed, palette applies on next cold start", t)
                }
            }, 300L)
        } catch (t: Throwable) {
            Log.w(TAG, "killTimProcess unavailable, palette applies on next cold start", t)
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
            // 不能再静默了：这里一旦开始抛，readCurrent 会永远返回旧快照，表现为
            // "改了设置没反应"且没有任何线索。第一次和之后每 50 次各留一条。
            readFailureCount++
            if (readFailureCount == 1 || readFailureCount % 50 == 0) {
                Log.w(TAG, "readCurrent failed (#$readFailureCount), serving stale settings", t)
            }
            current
        }
    }

    /**
     * TIM 进程侧写远端 SharedPreferences（模块设置页在 TIM 进程内显示时用）。
     * 改动会触发上面注册的监听器：配色相关改动按既有设计重启 TIM。
     *
     * @return 是否写入成功。调用方可以据此提示用户，而不是"看起来成功了"。
     */
    fun write(settings: AppSettings): Boolean {
        // 惰性附着：早期 attach 失败过也要能继续写，不能就此永久静音。
        val target = prefs ?: ensureRemote() ?: run {
            Log.w(TAG, "write skipped: remote preferences unavailable")
            return false
        }
        return try {
            TimMonetSettings.write(target, settings)
            current = settings
            true
        } catch (t: Throwable) {
            Log.e(TAG, "write remote settings failed", t)
            false
        }
    }
}
