package com.timmonet

import android.os.Bundle
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
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
        RemoteSettingsWriter.push(settings)

        setContent {
            TimMonetTheme(appSettings = settings) {
                key(settings) {
                    ThemeScreen(
                        colorMode = settings.colorMode,
                        keyColor = settings.keyColor,
                        paletteStyle = settings.paletteStyle,
                        colorSpec = settings.colorSpec,
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
}
