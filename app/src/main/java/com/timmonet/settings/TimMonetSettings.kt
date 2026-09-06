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
 * 模块自己的进程读写 SharedPreferences；TIM 进程通过 [SettingsProvider]
 * 读取同一份数据，实现“改完直接生效”。
 */
object TimMonetSettings {

    const val PREFS_NAME = "tim_monet"
    const val PROVIDER_AUTHORITY = "com.timmonet.settings"

    private const val KEY_COLOR_MODE = "colorMode"
    private const val KEY_KEY_COLOR = "keyColor"
    private const val KEY_PALETTE_STYLE = "paletteStyle"
    private const val KEY_COLOR_SPEC = "colorSpec"
    private const val KEY_REVISION = "revision"

    /** 远端 SharedPreferences 名（TIM 进程与模块 UI 共用）。 */
    const val REMOTE_PREFS_NAME = "tim_monet_settings"

    fun defaults(): AppSettings = AppSettings(
        colorMode = ColorMode.SYSTEM,
        keyColor = 0,
        paletteStyle = PaletteStyle.TonalSpot,
        colorSpec = ColorSpec.SpecVersion.SPEC_2025,
    )

    fun read(context: Context): AppSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return read(prefs)
    }

    /** 从任意 SharedPreferences 读取（TIM 侧读远端 prefs 也走这里）。 */
    fun read(prefs: SharedPreferences): AppSettings {
        return AppSettings(
            colorMode = ColorMode.fromValue(
                prefs.getInt(KEY_COLOR_MODE, defaults().colorMode.value)
            ),
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
            .putLong(KEY_REVISION, System.currentTimeMillis())
            .apply()
    }

    /** 序列化为 key=value 行文本，供 ContentProvider 跨进程传输。 */
    fun serialize(context: Context): String {
        val settings = read(context)
        return buildString {
            append(KEY_COLOR_MODE).append('=').append(settings.colorMode.value).append('\n')
            append(KEY_KEY_COLOR).append('=').append(settings.keyColor).append('\n')
            append(KEY_PALETTE_STYLE).append('=').append(settings.paletteStyle.name).append('\n')
            append(KEY_COLOR_SPEC).append('=').append(settings.colorSpec.name)
        }
    }

    /** TIM 进程解析 provider 返回的文本。 */
    fun parse(text: String?): AppSettings {
        if (text.isNullOrBlank()) return defaults()
        val map = HashMap<String, String>()
        for (line in text.lines()) {
            val index = line.indexOf('=')
            if (index > 0) map[line.substring(0, index).trim()] = line.substring(index + 1).trim()
        }
        return AppSettings(
            colorMode = ColorMode.fromValue(map[KEY_COLOR_MODE]?.toIntOrNull() ?: 0),
            keyColor = map[KEY_KEY_COLOR]?.toIntOrNull() ?: 0,
            paletteStyle = runCatching {
                PaletteStyle.valueOf(map[KEY_PALETTE_STYLE] ?: PaletteStyle.TonalSpot.name)
            }.getOrDefault(PaletteStyle.TonalSpot),
            colorSpec = runCatching {
                ColorSpec.SpecVersion.valueOf(
                    map[KEY_COLOR_SPEC] ?: ColorSpec.SpecVersion.SPEC_2025.name
                )
            }.getOrDefault(ColorSpec.SpecVersion.SPEC_2025),
        )
    }
}
