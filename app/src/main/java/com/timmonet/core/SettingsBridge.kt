package com.timmonet.core

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.timmonet.settings.SettingsWriteReceiver
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

    /** 模块自己的包名（广播目标）。 */
    private const val MODULE_PACKAGE = "io.github.linx3141.timmonet"

    private const val SETTINGS_WRITE_RECEIVER = "com.timmonet.settings.SettingsWriteReceiver"

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
                    // 无法覆盖底栏/顶栏等已绘制区域 → 需要强停 TIM 全量重建。
                    // ⚠️ 面板正在显示时必须**推迟到退出面板再重启**（否则用户刚点
                    // 一个开关，整个面板连同界面一起被重启掉，看起来像"点了没反应"）。
                    val paletteChanged = paletteDiffers(current, next)
                    current = next
                    if (paletteChanged) requestTimRestart()
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

    /** 配色相关字段是否有变化（监听器与 write() 共用同一份判据）。 */
    private fun paletteDiffers(a: AppSettings, b: AppSettings): Boolean =
        a.colorMode != b.colorMode ||
            a.paletteStyle != b.paletteStyle ||
            a.colorSpec != b.colorSpec ||
            a.keyColor != b.keyColor ||
            a.amoledBlack != b.amoledBlack

    /** 模块设置面板是否正在 TIM 进程里显示（面板期间的配色改动推迟到退出再重启）。 */
    @Volatile
    private var panelOpen = false

    /** 有配色改动等待生效（面板退出时执行）。 */
    @Volatile
    private var pendingRestart = false

    /**
     * 面板开/关。关闭时若攒了配色改动就重启 TIM —— 这样"改完设置退出面板"
     * 才生效，而不是在用户还在面板里点的时候把进程杀掉。
     */
    fun setPanelOpen(open: Boolean) {
        panelOpen = open
        if (!open) applyPendingRestart()
    }

    /** 退出面板/设置页时调用：有待生效的配色改动就重启 TIM。 */
    fun applyPendingRestart() {
        if (!pendingRestart) return
        pendingRestart = false
        Log.i(TAG, "pending palette change -> restart TIM on panel exit")
        // 广播是异步的：先确认模块 App 已经落盘（最多 2s），再重启 ——
        // 否则可能"重启了但读到的还是旧值"。等待放在后台线程，别卡住收尾动画。
        if (awaitingWrite == null) {
            killTimProcess()
            return
        }
        Thread {
            awaitWriteLanded(2000)
            killTimProcess()
        }.apply { isDaemon = true }.start()
    }

    /** 需要重启 TIM：面板开着就先记账，等退出面板再执行。 */
    private fun requestTimRestart() {
        if (panelOpen) {
            pendingRestart = true
            Log.i(TAG, "palette changed while panel open -> defer restart to panel exit")
        } else {
            killTimProcess()
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

    /** 最近一次"发出去让模块 App 落盘"的值；退出面板重启前用它确认真的写进去了。 */
    @Volatile
    private var awaitingWrite: AppSettings? = null

    /**
     * TIM 进程侧保存设置（模块设置面板在 TIM 进程内显示时用）。
     *
     * ⚠️ **不能直接写远端 prefs**：宿主拿到的 `getRemotePreferences()` 是**只读**
     * 实现，`edit()` 会抛 `UnsupportedOperationException: Read only implementation`
     * —— 旧实现就是这么静默失败的（表现为"面板里改了、退出再进又变回原样，
     * 而且 TIM 也不会重启"，因为写失败抛异常，后面的重启逻辑根本没执行）。
     *
     * 现在改成把新值用**显式广播**发给模块 App 的 [SettingsWriteReceiver]，
     * 由它在模块进程写远端 prefs（那里才是可写句柄）。写完宿主的监听器/下次读取
     * 就能看到新值。
     *
     * @return 是否已把写请求发出（不代表模块 App 已经写完，见 [applyPendingRestart]）。
     */
    fun write(context: Context, settings: AppSettings): Boolean {
        if (prefs == null) ensureRemote()
        val paletteChanged = paletteDiffers(current, settings)
        val sent = try {
            val intent = Intent(SettingsWriteReceiver.ACTION_WRITE).apply {
                setClassName(MODULE_PACKAGE, SETTINGS_WRITE_RECEIVER)
                putExtra(SettingsWriteReceiver.EXTRA_PROTOCOL, SettingsWriteReceiver.PROTOCOL)
                putExtra(SettingsWriteReceiver.EXTRA_COLOR_MODE, settings.colorMode.value)
                putExtra(SettingsWriteReceiver.EXTRA_KEY_COLOR, settings.keyColor)
                putExtra(
                    SettingsWriteReceiver.EXTRA_PALETTE_STYLE,
                    settings.paletteStyle.name
                )
                putExtra(SettingsWriteReceiver.EXTRA_COLOR_SPEC, settings.colorSpec.name)
                putExtra(SettingsWriteReceiver.EXTRA_AMOLED, settings.amoledBlack)
            }
            context.sendBroadcast(intent)
            Log.i(TAG, "settings write broadcast sent: ${settings.paletteStyle}/${settings.colorSpec}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "send settings write broadcast failed", t)
            false
        }
        current = settings
        if (sent) awaitingWrite = settings
        if (paletteChanged) requestTimRestart()
        return sent
    }

    /**
     * 等模块 App 把设置写进远端 prefs（广播是异步的，进程可能还要冷启动）。
     *
     * 退出面板时会先等这一步再重启 TIM —— 否则"重启"可能发生在落盘之前，
     * 重启后读到的还是旧值，用户看到的就是"改了没用"。
     */
    private fun awaitWriteLanded(timeoutMs: Long): Boolean {
        val expect = awaitingWrite ?: return true
        val m = module ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: AppSettings? = null
        while (System.currentTimeMillis() < deadline) {
            // RemotePreferences 是获取时的快照 → 每次都重新取一份
            last = runCatching {
                TimMonetSettings.read(m.getRemotePreferences(TimMonetSettings.REMOTE_PREFS_NAME))
            }.getOrNull()
            if (last == expect) {
                awaitingWrite = null
                return true
            }
            runCatching { Thread.sleep(80) }
        }
        Log.w(TAG, "settings write not visible in remote prefs yet: $last (expect $expect)")
        return false
    }
}
