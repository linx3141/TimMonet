@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.timmonet.ui.theme

import android.util.Log
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import com.materialkolor.PaletteStyle
import com.timmonet.core.MonetPalette
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

fun ColorScheme.amoledBackground(amoled: Boolean): ColorScheme =
    if (!amoled) this
    else copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainer = Color.Black,
        surfaceContainerHigh = Color.Black,
        surfaceContainerHighest = Color.Black,
    )

@Composable
fun rememberTimMonetColorScheme(
    seedColor: Color,
    isDark: Boolean,
    isAmoled: Boolean,
    paletteStyle: PaletteStyle,
    colorSpec: ColorSpec.SpecVersion,
): ColorScheme {
    val context = LocalContext.current
    val seed = if (seedColor == Color.Unspecified) {
        (if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
    } else {
        seedColor
    }
    return rememberDynamicColorScheme(
        seedColor = seed,
        isDark = isDark,
        isAmoled = isAmoled,
        style = paletteStyle,
        specVersion = colorSpec.effectiveFor(paletteStyle),
    ).amoledBackground(isAmoled)
}

@Composable
fun TimMonetTheme(
    appSettings: AppSettings,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // ⚠️ 不能用 Compose 的 isSystemInDarkTheme()：宿主(TIM)进程里应用级 uiMode 被
    // TIM 固定成浅色 → 恒为 false，"系统深色 + 自动"时会渲染成浅色面板。
    // MonetPalette.systemDarkNow() 读的是系统全局配置，两个进程都对。
    val systemDarkTheme = MonetPalette.systemDarkNow()
    val darkTheme = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && systemDarkTheme)
    val amoledMode = appSettings.amoledBlack && darkTheme
    val dynamicColor = appSettings.keyColor == 0

    val colorScheme = rememberTimMonetColorScheme(
        seedColor = if (dynamicColor) Color.Unspecified else Color(appSettings.keyColor),
        isDark = darkTheme,
        isAmoled = amoledMode,
        paletteStyle = appSettings.paletteStyle,
        colorSpec = appSettings.colorSpec,
    )
    Log.i(
        "TimMonetUI",
        "theme colorMode=${appSettings.colorMode.name}(${appSettings.colorMode.value}) " +
            "systemDark=$systemDarkTheme darkTheme=$darkTheme " +
            "surface=${Integer.toHexString(colorScheme.surfaceContainer.toArgb())} " +
            "background=${Integer.toHexString(colorScheme.background.toArgb())}"
    )

    LaunchedEffect(darkTheme) {
        val window = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = Typography,
        content = content,
    )
}
