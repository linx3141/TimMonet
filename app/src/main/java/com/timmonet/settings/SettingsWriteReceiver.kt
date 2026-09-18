package com.timmonet.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.timmonet.ui.theme.AppSettings
import com.timmonet.ui.theme.ColorMode

/**
 * 宿主(TIM)进程里的模块设置面板改设置时，由宿主发广播到这里落盘。
 *
 * 为什么必须绕这一圈：宿主的 `XposedInterface.getRemotePreferences()` 是
 * **只读**实现（实测 `edit()` 抛 `UnsupportedOperationException: Read only
 * implementation`），所以 TIM 进程里改的设置**写不进远端 prefs** ——
 * 表现就是"面板里改了、退出再进又变回原样，而且 TIM 也不会重启"
 * （写失败 → 异常 → 后面的重启逻辑根本没走到）。
 *
 * 远端 prefs 的可写句柄只有模块 App 进程有（`XposedService` / 本进程的
 * `shared_prefs` 文件），所以宿主把新值用**显式广播**（指定组件，不受包可见性
 * 与隐式广播限制影响）发过来，这里写两个地方：
 *   1. `REMOTE_PREFS_NAME` —— 宿主读的就是它，下一次读取即生效；
 *   2. 模块 App 自己的 `PREFS_NAME` —— 模块界面显示同一份值。
 *
 * 组件在 manifest 里声明为 exported（无 intent-filter，只能显式调用）。
 */
class SettingsWriteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(EXTRA_PROTOCOL, -1) != PROTOCOL) {
            Log.w(TAG, "settings write broadcast: protocol mismatch, ignored")
            return
        }
        try {
            val settings = AppSettings(
                colorMode = ColorMode.fromValue(intent.getIntExtra(EXTRA_COLOR_MODE, 0)),
                keyColor = intent.getIntExtra(EXTRA_KEY_COLOR, 0),
                paletteStyle = runCatching {
                    PaletteStyle.valueOf(
                        intent.getStringExtra(EXTRA_PALETTE_STYLE) ?: PaletteStyle.TonalSpot.name
                    )
                }.getOrDefault(PaletteStyle.TonalSpot),
                colorSpec = runCatching {
                    ColorSpec.SpecVersion.valueOf(
                        intent.getStringExtra(EXTRA_COLOR_SPEC)
                            ?: ColorSpec.SpecVersion.SPEC_2025.name
                    )
                }.getOrDefault(ColorSpec.SpecVersion.SPEC_2025),
                amoledBlack = intent.getBooleanExtra(EXTRA_AMOLED, false),
            )
            // 1) 远端 prefs（宿主 getRemotePreferences 读的那份）
            TimMonetSettings.write(
                context.getSharedPreferences(
                    TimMonetSettings.REMOTE_PREFS_NAME,
                    Context.MODE_PRIVATE
                ),
                settings
            )
            // 2) 模块 App 本地 prefs（模块自己的界面用同一份值）
            TimMonetSettings.write(context, settings)
            Log.i(
                TAG,
                "settings written from host: ${settings.colorMode}/${settings.paletteStyle}/" +
                    "${settings.colorSpec}/key=${Integer.toHexString(settings.keyColor)}/" +
                    "amoled=${settings.amoledBlack}"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "settings write broadcast failed", t)
        }
    }

    companion object {
        private const val TAG = "TimMonet"

        /** 自定义 action（仅用于日志可读性；实际按组件显式投递）。 */
        const val ACTION_WRITE = "com.timmonet.action.WRITE_SETTINGS"

        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_COLOR_MODE = "colorMode"
        const val EXTRA_KEY_COLOR = "keyColor"
        const val EXTRA_PALETTE_STYLE = "paletteStyle"
        const val EXTRA_COLOR_SPEC = "colorSpec"
        const val EXTRA_AMOLED = "amoled"

        /** 协议版本：字段增删时 +1，旧宿主与新模块之间不会互相写坏。 */
        const val PROTOCOL = 1
    }
}
