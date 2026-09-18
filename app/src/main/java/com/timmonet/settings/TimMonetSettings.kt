package com.timmonet.settings

import android.content.Context
import android.content.SharedPreferences
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.timmonet.ui.theme.AppSettings
import com.timmonet.ui.theme.ColorMode

/**
 * 模块设置（颜色模式 / 种子色 / 色彩风格 / 色彩标准）。
 *
 * 模块自己的进程读写 SharedPreferences；TIM 进程通过 LSPosed 的
 * `XposedModule.getRemotePreferences([REMOTE_PREFS_NAME])` 读到同一份数据
 * （见 `core/SettingsBridge`），实现"改完直接生效"。
 *
 * ⚠️ 文件里**没有** ContentProvider：历史上曾用 `serialize()` / `parse()` 走
 * provider 传文本，那条路径连同 `PROVIDER_AUTHORITY` 一起删掉了（清单里从没有
 * 过 provider 声明）。不要再把文本序列化加回来。
 */
object TimMonetSettings {

    const val PREFS_NAME = "tim_monet"

    private const val KEY_COLOR_MODE = "colorMode"
    private const val KEY_KEY_COLOR = "keyColor"
    private const val KEY_PALETTE_STYLE = "paletteStyle"
    private const val KEY_COLOR_SPEC = "colorSpec"
    private const val KEY_REVISION = "revision"
    private const val KEY_AMOLED = "amoledBlack"

    /** 远端 SharedPreferences 名（TIM 进程与模块 UI 共用）。 */
    const val REMOTE_PREFS_NAME = "tim_monet_settings"

    fun defaults(): AppSettings = AppSettings(
        colorMode = ColorMode.SYSTEM,
        keyColor = 0,
        paletteStyle = PaletteStyle.TonalSpot,
        colorSpec = ColorSpec.SpecVersion.SPEC_2025,
        amoledBlack = false,
    )

    fun read(context: Context): AppSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return read(prefs)
    }

    /** 从任意 SharedPreferences 读取（TIM 侧读远端 prefs 也走这里）。 */
    fun read(prefs: SharedPreferences): AppSettings {
        // 兼容旧版本：AMOLED 以前是颜色模式里的一个选项(DARK_AMOLED=6)，
        // 现在是"深色模式时应用"的独立开关 —— 读到旧值就迁移成 深色 + 开。
        var mode = ColorMode.fromValue(
            prefs.getInt(KEY_COLOR_MODE, defaults().colorMode.value)
        )
        var amoled = prefs.getBoolean(KEY_AMOLED, false)
        if (mode == ColorMode.DARK_AMOLED) {
            mode = ColorMode.DARK
            amoled = true
        }
        return AppSettings(
            colorMode = mode,
            keyColor = prefs.getInt(KEY_KEY_COLOR, 0),
            paletteStyle = runCatching {
                PaletteStyle.valueOf(
                    prefs.getString(KEY_PALETTE_STYLE, PaletteStyle.TonalSpot.name)
                        ?: PaletteStyle.TonalSpot.name
                )
            }.getOrDefault(PaletteStyle.TonalSpot),
            colorSpec = runCatching {
                ColorSpec.SpecVersion.valueOf(
                    prefs.getString(KEY_COLOR_SPEC, ColorSpec.SpecVersion.SPEC_2025.name)
                        ?: ColorSpec.SpecVersion.SPEC_2025.name
                )
            }.getOrDefault(ColorSpec.SpecVersion.SPEC_2025),
            amoledBlack = amoled,
        )
    }

    fun write(context: Context, settings: AppSettings) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        write(prefs, settings)
    }

    fun write(prefs: SharedPreferences, settings: AppSettings) {
        prefs.edit()
            .putInt(KEY_COLOR_MODE, settings.colorMode.value)
            .putInt(KEY_KEY_COLOR, settings.keyColor)
            .putString(KEY_PALETTE_STYLE, settings.paletteStyle.name)
            .putString(KEY_COLOR_SPEC, settings.colorSpec.name)
            .putBoolean(KEY_AMOLED, settings.amoledBlack)
            .putLong(KEY_REVISION, System.currentTimeMillis())
            .apply()
    }

    /**
     * 设置修订时间戳（每次写入刷新）。
     *
     * 模块设置页可能在两处被打开（TIM 进程内的入口 / 模块 app 自己的 Activity），
     * 两边共用远端 prefs，靠这个时间戳决定谁是最新、避免用旧值把新值推回去。
     */
    fun revision(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_REVISION, 0L)

    fun revision(prefs: SharedPreferences): Long = prefs.getLong(KEY_REVISION, 0L)

}
