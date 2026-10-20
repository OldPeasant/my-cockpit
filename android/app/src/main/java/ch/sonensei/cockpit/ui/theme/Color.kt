package ch.sonensei.cockpit.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Fallback palette for devices without dynamic color (Android 9-11): instrument-panel slate blue with an
// amber indicator accent. Android 12+ uses the wallpaper-derived scheme instead.
private val Slate10 = Color(0xFF001C38)
private val Slate20 = Color(0xFF0E3253)
private val Slate30 = Color(0xFF2A4A6D)
private val Slate40 = Color(0xFF436287)
private val Slate80 = Color(0xFFAACAF3)
private val Slate90 = Color(0xFFD3E4FF)
private val Amber10 = Color(0xFF2A1800)
private val Amber20 = Color(0xFF462A00)
private val Amber30 = Color(0xFF643F00)
private val Amber40 = Color(0xFF835500)
private val Amber80 = Color(0xFFF9BC5B)
private val Amber90 = Color(0xFFFFDDB4)
private val Teal10 = Color(0xFF002020)
private val Teal20 = Color(0xFF003737)
private val Teal30 = Color(0xFF004F50)
private val Teal40 = Color(0xFF006A6A)
private val Teal80 = Color(0xFF76D6D5)
private val Teal90 = Color(0xFF93F2F2)
private val Neutral10 = Color(0xFF191C20)
private val Neutral20 = Color(0xFF2E3135)
private val Neutral90 = Color(0xFFE1E2E8)
private val Neutral95 = Color(0xFFEFF0F6)
private val Neutral98 = Color(0xFFF8F9FF)
private val NeutralVariant30 = Color(0xFF43474E)
private val NeutralVariant50 = Color(0xFF73777F)
private val NeutralVariant60 = Color(0xFF8D9199)
private val NeutralVariant80 = Color(0xFFC3C6CF)
private val NeutralVariant90 = Color(0xFFDFE2EB)

internal val CockpitLightColors =
    lightColorScheme(
        primary = Slate40,
        onPrimary = Color.White,
        primaryContainer = Slate90,
        onPrimaryContainer = Slate10,
        secondary = Amber40,
        onSecondary = Color.White,
        secondaryContainer = Amber90,
        onSecondaryContainer = Amber10,
        tertiary = Teal40,
        onTertiary = Color.White,
        tertiaryContainer = Teal90,
        onTertiaryContainer = Teal10,
        background = Neutral98,
        onBackground = Neutral10,
        surface = Neutral98,
        onSurface = Neutral10,
        surfaceVariant = NeutralVariant90,
        onSurfaceVariant = NeutralVariant30,
        outline = NeutralVariant50,
        outlineVariant = NeutralVariant80,
        inverseSurface = Neutral20,
        inverseOnSurface = Neutral95,
        inversePrimary = Slate80,
    )

internal val CockpitDarkColors =
    darkColorScheme(
        primary = Slate80,
        onPrimary = Slate20,
        primaryContainer = Slate30,
        onPrimaryContainer = Slate90,
        secondary = Amber80,
        onSecondary = Amber20,
        secondaryContainer = Amber30,
        onSecondaryContainer = Amber90,
        tertiary = Teal80,
        onTertiary = Teal20,
        tertiaryContainer = Teal30,
        onTertiaryContainer = Teal90,
        background = Neutral10,
        onBackground = Neutral90,
        surface = Neutral10,
        onSurface = Neutral90,
        surfaceVariant = NeutralVariant30,
        onSurfaceVariant = NeutralVariant80,
        outline = NeutralVariant60,
        outlineVariant = NeutralVariant30,
        inverseSurface = Neutral90,
        inverseOnSurface = Neutral20,
        inversePrimary = Slate40,
    )
