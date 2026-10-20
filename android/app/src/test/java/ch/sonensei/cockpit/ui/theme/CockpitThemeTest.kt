package ch.sonensei.cockpit.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Checks which color scheme [CockpitTheme] hands to Material 3 per API level, dark mode and dynamic-color flag. */
@RunWith(AndroidJUnit4::class)
class CockpitThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Composes [CockpitTheme] and returns the scheme its content sees; `darkTheme = null` keeps the system default. */
    private fun schemeOf(
        darkTheme: Boolean? = null,
        dynamicColor: Boolean = true,
    ): ColorScheme {
        lateinit var scheme: ColorScheme
        val content: @Composable () -> Unit = { scheme = MaterialTheme.colorScheme }
        composeRule.setContent {
            if (darkTheme == null) {
                CockpitTheme(dynamicColor = dynamicColor, content = content)
            } else {
                CockpitTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)
            }
        }
        composeRule.waitForIdle()
        return scheme
    }

    /** ColorScheme has no value equality; compare the roles the app relies on. */
    private fun assertSameColors(
        expected: ColorScheme,
        actual: ColorScheme,
    ) {
        assertEquals(expected.primary, actual.primary)
        assertEquals(expected.onPrimary, actual.onPrimary)
        assertEquals(expected.secondary, actual.secondary)
        assertEquals(expected.background, actual.background)
        assertEquals(expected.surface, actual.surface)
        assertEquals(expected.onSurfaceVariant, actual.onSurfaceVariant)
    }

    @Test
    @Config(sdk = [30])
    fun beforeAndroid12UsesTheLightFallbackPalette() {
        assertSame(CockpitLightColors, schemeOf(darkTheme = false))
    }

    @Test
    @Config(sdk = [30])
    fun beforeAndroid12UsesTheDarkFallbackPaletteInDarkMode() {
        assertSame(CockpitDarkColors, schemeOf(darkTheme = true))
    }

    @Test
    @Config(sdk = [30], qualifiers = "night")
    fun darkModeFollowsTheSystemByDefault() {
        assertSame(CockpitDarkColors, schemeOf())
    }

    @Test
    @Config(sdk = [30], qualifiers = "notnight")
    fun lightModeFollowsTheSystemByDefault() {
        assertSame(CockpitLightColors, schemeOf())
    }

    @Test
    @Config(sdk = [31])
    fun fromAndroid12UsesDynamicLightColors() {
        val scheme = schemeOf(darkTheme = false)

        assertSameColors(dynamicLightColorScheme(context), scheme)
        assertNotEquals(CockpitLightColors.primary, scheme.primary)
    }

    @Test
    @Config(sdk = [34])
    fun fromAndroid12UsesDynamicDarkColorsInDarkMode() {
        val scheme = schemeOf(darkTheme = true)

        assertSameColors(dynamicDarkColorScheme(context), scheme)
        assertNotEquals(CockpitDarkColors.primary, scheme.primary)
    }

    @Test
    @Config(sdk = [34])
    fun dynamicColorCanBeTurnedOffOnAndroid12AndLater() {
        assertSame(CockpitLightColors, schemeOf(darkTheme = false, dynamicColor = false))
    }

    @Test
    @Config(sdk = [34])
    fun dynamicColorOffInDarkModeUsesTheDarkFallbackPalette() {
        assertSame(CockpitDarkColors, schemeOf(darkTheme = true, dynamicColor = false))
    }

    @Test
    fun fallbackPalettesDifferBetweenLightAndDark() {
        assertNotEquals(CockpitLightColors.primary, CockpitDarkColors.primary)
        assertNotEquals(CockpitLightColors.background, CockpitDarkColors.background)
    }
}
