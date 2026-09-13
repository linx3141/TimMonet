package com.timmonet

import android.os.Bundle
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.timmonet.settings.TimMonetSettings
import com.timmonet.settings.RemoteSettingsWriter
import com.timmonet.ui.screen.ThemeScreen
import com.timmonet.ui.theme.ColorMode
import com.timmonet.ui.theme.TimMonetTheme

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.decorView.isForceDarkAllowed = false
        }
        RemoteSettingsWriter.init()
        var settings by mutableStateOf(TimMonetSettings.read(this))
        Log.i("TimMonetUI", "read settings: ${settings}")
        // 本页可能在 TIM 进程内的入口里改过设置：远端更新就采用远端，
        // 否则（首次）把本地值推上去。绝不能无条件 push 本地旧值。
        RemoteSettingsWriter.syncOnBind(
            localSettings = settings,
            localRevision = TimMonetSettings.revision(this)
        ) { remote ->
            settings = remote
            TimMonetSettings.write(this, remote)
        }

        setContent {
            TimMonetTheme(appSettings = settings) {
                                ThemeScreen(
                    colorMode = settings.colorMode,
                    keyColor = settings.keyColor,
                    paletteStyle = settings.paletteStyle,
                    colorSpec = settings.colorSpec,
                    amoledBlack = settings.amoledBlack,
                    onBack = { finish() },
                    onSetKeyColor = { keyColor ->
                        settings = settings.copy(keyColor = keyColor)
                        TimMonetSettings.write(this, settings)
                        RemoteSettingsWriter.push(settings)
                    },
                    onSetColorMode = { mode: ColorMode ->
                        settings = settings.copy(colorMode = mode)
                        TimMonetSettings.write(this, settings)
                        RemoteSettingsWriter.push(settings)
                    },
                    onSetAmoledBlack = { amoled ->
                        settings = settings.copy(amoledBlack = amoled)
                        TimMonetSettings.write(this, settings)
                        RemoteSettingsWriter.push(settings)
                    },
                    onSetColorStyle = { name ->
                        val style = runCatching {
                            com.materialkolor.PaletteStyle.valueOf(name)
                        }.getOrNull() ?: return@ThemeScreen
                        settings = settings.copy(paletteStyle = style)
                        TimMonetSettings.write(this, settings)
                        RemoteSettingsWriter.push(settings)
                    },
                    onSetColorSpec = { name ->
                        val spec = runCatching {
                            com.materialkolor.dynamiccolor.ColorSpec.SpecVersion.valueOf(name)
                        }.getOrNull() ?: return@ThemeScreen
                        settings = settings.copy(colorSpec = spec)
                        TimMonetSettings.write(this, settings)
                        RemoteSettingsWriter.push(settings)
                    },
                )
            
            }
        }
    }
}
