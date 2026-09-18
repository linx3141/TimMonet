package com.timmonet.core

import android.app.WallpaperManager
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeFruitSalad
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeRainbow
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import com.materialkolor.score.Score
import com.timmonet.settings.TimMonetSettings
import com.timmonet.ui.theme.AppSettings
import com.timmonet.ui.theme.ColorMode

/**
 * 从系统壁纸取色并生成 Material You（莫奈）调色板。
 *
 * 使用 WallpaperManager.getWallpaperColors()，与系统 Monet 同源，
 * 无需任何额外权限，也天然支持动态壁纸。
 */
object MonetPalette {

    private const val TAG = "TimMonet"
    private const val DEFAULT_SEED = 0xFF0099FF.toInt()

    @Volatile
    private var lightScheme: DynamicScheme? = null

    @Volatile
    private var darkScheme: DynamicScheme? = null

    @Volatile
    private var fallbackLightScheme: DynamicScheme? = null

    @Volatile
    private var fallbackDarkScheme: DynamicScheme? = null

    @Volatile
    private var paletteGeneration = 0L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var userSettings: AppSettings = TimMonetSettings.defaults()

    private var initialized = false
    private var listenerRegistered = false

    /**
     * 上次**尝试**构建调色板的时刻（无论成功还是失败）。
     *
     * ⚠️ 这个字段是"失败重试节流"的唯一依据，因此它必须与 [initialized] 解耦：
     * 历史上节流条件是 `if (initialized && now - lastAttemptAt < RETRY_THROTTLE_MS)`，
     * 而 [rebuild] 在取色失败时会把 `initialized` 置回 `false` —— 于是**失败后
     * 节流永不生效**，热路径（hook 住的 `Resources.getColor`）每调用一次就往
     * 单线程队列里塞一次注定失败的 SPEC_2025 重算，同时每次都抢全局锁。
     * 注释承诺的是"失败时最多每 5 秒重试一次"。
     */
    @Volatile
    private var lastAttemptAt = 0L

    /** 失败重试的最小间隔。 */
    private const val RETRY_THROTTLE_MS = 5000L

    /** 允许 `onSettingsChanged` 立刻触发一次重建（绕过节流），语义见该方法。 */
    private fun resetAttemptThrottle() {
        lastAttemptAt = 0L
    }

    private val rebuildThread by lazy {
        HandlerThread("TimMonetPalette").apply { start() }
    }

    private val rebuildHandler by lazy { Handler(rebuildThread.looper) }

    fun ensureInitialized(context: Context?) {
        if (context != null && appContext == null) {
            appContext = context.applicationContext ?: context
        }
        // 快速路径：初始化完成且调色板已就绪，只读 volatile，不抢锁、不读时钟。
        // 这条路径承担几乎所有取色调用，是 TIM 卡顿与否的关键。
        if (initialized && lightScheme != null) return
        synchronized(this) {
            if (context != null && appContext == null) {
                appContext = context.applicationContext ?: context
            }
            if (appContext == null) {
                appContext = try {
                    Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication")
                        .invoke(null) as? Context
                } catch (t: Throwable) {
                    null
                }
            }
            if (initialized && lightScheme != null) return
            val now = System.currentTimeMillis()
            // ⚠️ 节流只看 lastAttemptAt，**不能**再看 initialized：
            // rebuild() 失败时会把 initialized 置回 false，一带上它就等于
            // "失败后永不节流"，每次取色都重投一次 SPEC_2025 重算。
            if (lastAttemptAt != 0L && now - lastAttemptAt < RETRY_THROTTLE_MS) return
            initialized = true
            lastAttemptAt = now
            // 首次构建放在后台线程：SPEC_2025 + Vibrant 的 ColorSpec 计算非常重，
            // 一旦在主线程执行会直接冻结界面。构建完成前先用廉价的 TonalSpot 兜底。
            rebuildHandler.post {
                val current = SettingsBridge.readCurrent()
                if (current != userSettings) userSettings = current
                rebuild()
                // ⚠️ 注册壁纸监听必须在**构建之后**，且标志位只在真正成功时才保留：
                // 以前是"先置 listenerRegistered = true 再调用"，一旦
                // addOnColorsChangedListener 抛异常，标志位已经立住 —— 本进程内
                // 再也不会重试，换壁纸永远不再重建调色板，只留一行警告。
                if (!listenerRegistered && lightScheme != null) {
                    listenerRegistered = true
                    registerColorListener()
                }
            }
        }
    }

    /**
     * 模块设置变化（由 SettingsBridge 的跨进程 SharedPreferences 监听回调触发），
     * 在后台线程重建调色板，绝不在主线程做 IPC 轮询或昂贵的 ColorSpec 计算。
     */
    fun onSettingsChanged() {
        // 用户主动改了设置，应当**立刻**重建，不要被失败重试节流挡住。
        resetAttemptThrottle()
        rebuildHandler.post {
            val next = SettingsBridge.readCurrent()
            if (next != userSettings) {
                userSettings = next
                rebuild()
            }
        }
    }

    /** 调色板代次：壁纸变化时自增，供各缓存失效判断。 */
    fun generation(): Long = paletteGeneration

    /**
     * 当前调色板。深浅完全由模块设置（或系统）决定，见 [isDarkNow]。
     *
     * ⚠️ 这里**只有无参形式**：历史上还有一个 `palette(dark)` 重载，但那个参数
     * 早已被忽略（深浅改为完全跟随设置），调用方传的 TIM themeId / 硬编码
     * true-false / `isDarkNow()` 一律不影响结果 —— 它只会让 TokenMapper 的缓存键
     * 多一个与结果无关的位，并让人误以为"传 false 就是浅色档"从而写出依赖错误
     * 前提的分支（`bgColorForDrawable` 就曾真用调用方的 dark 判断"亮底压暗"，
     * 而颜色来自这里的 effectiveDark —— 判据与取色来自两个深浅源）。
     * 无参重载已删除，不要再加带参版本（见 AGENTS.md 坑 6）。
     */
    fun palette(): DynamicScheme {
        ensureInitialized(null)
        val effectiveDark = effectiveDark()
        val scheme = if (effectiveDark) darkScheme else lightScheme
        if (scheme != null) return scheme
        return fallbackScheme(effectiveDark)
    }

    /** 后台真实调色板就绪前的轻量兜底（SPEC_2021 + TonalSpot，构建快很多）。 */
    private fun fallbackScheme(dark: Boolean): DynamicScheme {
        val existing = if (dark) fallbackDarkScheme else fallbackLightScheme
        if (existing != null) return existing
        val built = buildScheme(
            DEFAULT_SEED,
            dark,
            PaletteStyle.TonalSpot,
            ColorSpec.SpecVersion.SPEC_2021
        )
        if (dark) fallbackDarkScheme = built else fallbackLightScheme = built
        return built
    }

    /** 当前是否深色 —— **唯一可信的深浅判据**。
     *
     *  ⚠️ 不要用 ThemeState.isNight()：它的数据源是 TIM 的
     *  QQTheme.isNowThemeIsNight()，而本模块的 hookForceLight 恰好把那个方法
     *  hook 成了永远返回 false（目的是让 TIM 走浅色资源、由我们统一染色），
     *  于是它恒为 false，结果还被永久缓存 —— 所有 `if (!isNight) return` 的
     *  分支都会变成死代码（曾导致一批"修了没效果"的 bug）。
     *  深浅完全由模块设置（固定深/浅）或系统决定，直接用 effectiveDark()。 */
    fun isDarkNow(): Boolean {
        ensureInitialized(null)
        return effectiveDark()
    }

    /** 是否使用 AMOLED 纯黑表面：**深色模式 + 独立开关**（旧版是颜色模式的一项）。 */
    fun isAmoled(): Boolean {
        ensureInitialized(null)
        return userSettings.amoledBlack && effectiveDark()
    }

    /**
     * AMOLED 纯黑：模块设为 AMOLED 黑时，把“表面/背景类”颜色压成纯黑
     * （只保留 alpha，文字/图标色不受影响）。非 AMOLED 模式原样返回。
     */
    fun amoledBlack(color: Int): Int {
        if (!userSettings.amoledBlack || !effectiveDark()) return color
        // 全透明：原样返回（约定见 AGENTS.md）；与 TokenMapper.resolve 的
        // AMOLED 分支保持同一语义。
        if (ColorMath.isTransparent(color)) return color
        return ColorMath.withAlpha(0xFF000000.toInt(), ColorMath.alpha(color))
    }

    /**
     * 深浅色完全由模块设置控制，与 TIM 原生的夜间模式无关：
     * 亮 → 浅色；暗/纯黑 → 深色；自动 → 跟随系统深色模式。
     */
    private fun effectiveDark(): Boolean = when (userSettings.colorMode) {
        ColorMode.LIGHT, ColorMode.MONET_LIGHT -> false
        ColorMode.DARK, ColorMode.MONET_DARK, ColorMode.DARK_AMOLED -> true
        else -> systemDark()
    }

    private fun systemDark(): Boolean {
        val ctx = appContext ?: return false
        return try {
            // TIM 用自有主题引擎，应用级 UiModeManager 常被强制为浅色
            // （night=NO），即使系统开了深色模式。自动模式应以系统全局
            // 配置为准，所以优先读 Resources.getSystem() 的 uiMode。
            val systemUiMode = Resources.getSystem().configuration.uiMode
            if ((systemUiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            ) {
                return true
            }
            val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            (ui?.nightMode ?: Configuration.UI_MODE_NIGHT_UNDEFINED) == Configuration.UI_MODE_NIGHT_YES
        } catch (t: Throwable) {
            false
        }
    }

    private fun rebuild() {
        // ⚠️ 本函数跑在 HandlerThread 上：一旦抛异常没人接住，那个 Looper 线程就
        // 死了，之后所有 post 静默失败 —— 表现是"取色永久停在兜底方案、且一条
        // 日志都没有"。这里整体兜住，并把 initialized 复位以便下次重试。
        try {
            // 与 KernelSU Manager 一致：直接采用系统莫奈引擎生成的 accent 色作为
            // 种子，而不是壁纸的原始主色。这样即使壁纸是蓝色、系统莫奈选了绿色，
            // 也会跟随系统。
            val customKeyColor = userSettings.keyColor
            val systemSeeds =
                if (customKeyColor == 0) appContext?.let { readSystemMonetSeeds(it) } else null
            // 与 KernelSU Manager 完全一致：所有风格都用系统莫奈 primary 作为种子
            val lightSeed =
                if (customKeyColor != 0) customKeyColor else systemSeeds?.first ?: fallbackSeed()
            val darkSeed =
                if (customKeyColor != 0) customKeyColor else systemSeeds?.second ?: fallbackSeed()
            lightScheme =
                buildScheme(lightSeed, false, userSettings.paletteStyle, userSettings.colorSpec)
            darkScheme =
                buildScheme(darkSeed, true, userSettings.paletteStyle, userSettings.colorSpec)
            paletteGeneration++
            Log.i(
                TAG,
                "palette rebuilt, lightSeed=#${Integer.toHexString(lightSeed)}, " +
                    "darkSeed=#${Integer.toHexString(darkSeed)}"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "palette rebuild failed (will retry)", t)
            initialized = false
        }
    }

    private fun buildScheme(
        seed: Int,
        dark: Boolean,
        style: PaletteStyle,
        spec: ColorSpec.SpecVersion
    ): DynamicScheme {
        val hct = Hct.fromInt(seed)
        val effectiveSpec = if (
            spec == ColorSpec.SpecVersion.SPEC_2025 &&
            style != PaletteStyle.TonalSpot &&
            style != PaletteStyle.Neutral &&
            style != PaletteStyle.Vibrant &&
            style != PaletteStyle.Expressive
        ) {
            ColorSpec.SpecVersion.SPEC_2021
        } else {
            spec
        }
        return when (style) {
            PaletteStyle.TonalSpot -> SchemeTonalSpot(hct, dark, 0.0, effectiveSpec, DynamicScheme.Platform.PHONE)
            PaletteStyle.Neutral -> SchemeNeutral(hct, dark, 0.0, effectiveSpec, DynamicScheme.Platform.PHONE)
            PaletteStyle.Vibrant -> SchemeVibrant(hct, dark, 0.0, effectiveSpec, DynamicScheme.Platform.PHONE)
            PaletteStyle.Expressive -> SchemeExpressive(hct, dark, 0.0, effectiveSpec, DynamicScheme.Platform.PHONE)
            PaletteStyle.Rainbow -> SchemeRainbow(hct, dark, 0.0, effectiveSpec, DynamicScheme.Platform.PHONE)
            PaletteStyle.FruitSalad -> SchemeFruitSalad(hct, dark, 0.0, effectiveSpec, DynamicScheme.Platform.PHONE)
            PaletteStyle.Monochrome -> SchemeMonochrome(hct, dark, 0.0, effectiveSpec, DynamicScheme.Platform.PHONE)
            PaletteStyle.Fidelity -> SchemeFidelity(hct, dark, 0.0, effectiveSpec, DynamicScheme.Platform.PHONE)
            PaletteStyle.Content -> SchemeContent(hct, dark, 0.0, effectiveSpec, DynamicScheme.Platform.PHONE)
        }
    }

    /**
     * 系统莫奈引擎的种子色：
     * - Android 12/13 (API 31-33)：Compose `dynamicLightColorScheme` 的 primary 来自
     *   system_accent1_600，`dynamicDarkColorScheme` 的 primary 来自 system_accent1_200；
     * - Android 14+ (API 34+)：对应 system_primary_light / system_primary_dark。
     */
    private fun readSystemMonetSeeds(context: Context): Pair<Int, Int>? {
        if (Build.VERSION.SDK_INT < 31) return null
        return try {
            val resources: Resources = context.resources
            if (Build.VERSION.SDK_INT >= 34) {
                resources.getColor(android.R.color.system_primary_light, null) to
                    resources.getColor(android.R.color.system_primary_dark, null)
            } else {
                resources.getColor(android.R.color.system_accent1_600, null) to
                    resources.getColor(android.R.color.system_accent1_200, null)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "read system monet colors failed", t)
            null
        }
    }

    private fun readWallpaperColors(): List<Int> {
        val ctx = appContext ?: return emptyList()
        if (Build.VERSION.SDK_INT < 27) return emptyList()
        return try {
            val wm = WallpaperManager.getInstance(ctx)
            val colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return emptyList()
            listOfNotNull(
                colors.primaryColor.toArgb(),
                colors.secondaryColor?.toArgb(),
                colors.tertiaryColor?.toArgb()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "read wallpaper colors failed", t)
            emptyList()
        }
    }

    /** Android 12 之前的兜底：从壁纸颜色量化出种子。 */
    private fun fallbackSeed(): Int = pickSeed(readWallpaperColors())

    private fun pickSeed(candidates: List<Int>): Int {
        val valid = candidates.filter { c ->
            c != -1 &&
                Color.alpha(c) == 255 &&
                try {
                    val tone = Hct.fromInt(c).tone
                    tone in 8.0..96.0
                } catch (t: Throwable) {
                    false
                }
        }
        if (valid.isEmpty()) return DEFAULT_SEED
        if (valid.size == 1) return valid[0]

        // 用 Material 的 Celebi 量化 + Score 挑选主色，更接近系统 Monet 的选色逻辑
        return try {
            val quantized = QuantizerCelebi.quantize(
                valid.toIntArray(),
                minOf(valid.size, 4)
            )
            Score.score(quantized, 1, valid[0], false).firstOrNull() ?: valid[0]
        } catch (t: Throwable) {
            valid[0]
        }
    }

    private fun registerColorListener() {
        val ctx = appContext ?: return
        if (Build.VERSION.SDK_INT < 27) return
        try {
            WallpaperManager.getInstance(ctx).addOnColorsChangedListener(
                WallpaperManager.OnColorsChangedListener { _, _ ->
                    rebuildHandler.post { rebuild() }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (t: Throwable) {
            Log.w(TAG, "register wallpaper listener failed", t)
        }
    }
}
