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

    /**
     * 服务尚不可用时暂存的最新一次设置。
     *
     * ⚠️ 推送成功后**必须清空**：服务重绑（XposedService 断连重连、模块热重载）时
     * [onServiceBind] 会把 pending 再推一次，而 `TimMonetSettings.write` 会把
     * revision 刷成当前时间 —— 于是一个几分钟前的旧值会"后来居上"覆盖掉用户
     * 刚在 TIM 设置页里做的新修改（静默回滚，没有任何提示）。
     */
    private val pending = java.util.concurrent.atomic.AtomicReference<AppSettings?>(null)

    /** 服务绑定后要跑一次的同步任务（见 [syncOnBind]）。跑完即清，不重复执行。 */
    private val onBindTasks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    Log.i(TAG, "xposed service bound")
                    RemoteSettingsWriter.service = service
                    val queued = pending.get()
                    if (queued != null && push(queued)) {
                        clearPendingIfStill(queued)
                    }
                    // ⚠️ 必须"取出并清空"：以前是 forEach 而不移除，服务每次重绑都会
                    // 把全部历史任务重跑一遍（放大上面 pending 的回滚问题），
                    // 而且闭包会一直持有创建它的 Activity（Activity 泄漏）。
                    val tasks = onBindTasks.toList()
                    onBindTasks.clear()
                    tasks.forEach { task ->
                        runCatching { task() }
                            .onFailure { Log.w(TAG, "onBind task failed", it) }
                    }
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

    /** 只在 pending 仍是 [expected] 时清空 —— 避免清掉期间新写入的更新值。 */
    private fun clearPendingIfStill(expected: AppSettings) {
        pending.compareAndSet(expected, null)
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

    /**
     * 推送设置到远端。
     *
     * @return 是否已写入远端。`false` 表示服务还没绑上（值已暂存，绑定后自动补推）
     *   或写入抛异常。调用方据此决定是否提示用户，而不是无条件显示"已保存"。
     */
    fun push(settings: AppSettings): Boolean {
        val current = service
        if (current == null) {
            pending.set(settings)
            init()
            return false
        }
        return try {
            TimMonetSettings.write(
                current.getRemotePreferences(TimMonetSettings.REMOTE_PREFS_NAME),
                settings
            )
            Log.i(TAG, "remote settings pushed: ${settings.paletteStyle}/${settings.colorSpec}")
            clearPendingIfStill(settings)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "push remote settings failed (will retry on next bind)", t)
            pending.set(settings)
            false
        }
    }
}
