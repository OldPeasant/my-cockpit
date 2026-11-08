package ch.sonensei.cockpit.ui.theme

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.core.settings.LocalUserSettings
import ch.sonensei.cockpit.core.settings.ThemeSetting
import ch.sonensei.cockpit.core.settings.UserSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Material 3 theme of the app: wallpaper-based dynamic color on Android 12+ (API 31), the cockpit fallback
 * palette before that; light or dark follows the system unless [darkTheme] says otherwise.
 */
@Composable
fun CockpitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> CockpitDarkColors
            else -> CockpitLightColors
        }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** Whether [theme] means dark, with [systemDark] deciding for [ThemeSetting.SYSTEM]. */
fun isDark(
    theme: ThemeSetting,
    systemDark: Boolean,
): Boolean =
    when (theme) {
        ThemeSetting.LIGHT -> false
        ThemeSetting.DARK -> true
        ThemeSetting.SYSTEM -> systemDark
    }

/**
 * The app theme driven by the synced settings: the `theme` setting picks light or dark (the system decides while it is
 * "system" or unknown), the system bars follow it, and the screens below read the settings from [LocalUserSettings].
 */
@Composable
fun CockpitSettingsTheme(
    settings: StateFlow<UserSettings?>,
    content: @Composable () -> Unit,
) {
    val current by settings.collectAsStateWithLifecycle()
    val dark = isDark(current?.theme ?: ThemeSetting.SYSTEM, isSystemInDarkTheme())
    val activity = LocalActivity.current as? ComponentActivity
    LaunchedEffect(activity, dark) {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
    }
    CompositionLocalProvider(LocalUserSettings provides current) {
        CockpitTheme(darkTheme = dark, content = content)
    }
}

/** The scrims `enableEdgeToEdge` uses by default behind a three-button navigation bar. */
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
