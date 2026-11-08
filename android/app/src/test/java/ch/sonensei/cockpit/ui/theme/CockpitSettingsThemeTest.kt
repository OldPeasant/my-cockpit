package ch.sonensei.cockpit.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.core.settings.LocalUserSettings
import ch.sonensei.cockpit.core.settings.ThemeSetting
import ch.sonensei.cockpit.core.settings.UserSettings
import ch.sonensei.cockpit.core.settings.userCreativityLabel
import ch.sonensei.cockpit.core.settings.userEnergyLabel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The synced `theme` setting picks light or dark app-wide; the screens read the settings from [LocalUserSettings]. */
@RunWith(AndroidJUnit4::class)
class CockpitSettingsThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val settings = MutableStateFlow<UserSettings?>(null)

    private fun show(): () -> ColorScheme {
        var scheme: ColorScheme? = null
        composeRule.setContent { CockpitSettingsTheme(settings) { scheme = MaterialTheme.colorScheme } }
        return {
            composeRule.waitForIdle()
            scheme!!
        }
    }

    @Test
    @Config(sdk = [30], qualifiers = "notnight")
    fun darkSettingOverridesALightSystem() {
        settings.value = UserSettings(theme = ThemeSetting.DARK)
        assertSame(CockpitDarkColors, show()())
    }

    @Test
    @Config(sdk = [30], qualifiers = "night")
    fun lightSettingOverridesADarkSystem() {
        settings.value = UserSettings(theme = ThemeSetting.LIGHT)
        assertSame(CockpitLightColors, show()())
    }

    @Test
    @Config(sdk = [30], qualifiers = "night")
    fun systemOrUnknownSettingFollowsTheSystem() {
        val scheme = show()
        assertSame(CockpitDarkColors, scheme())

        settings.value = UserSettings(theme = ThemeSetting.SYSTEM)
        assertSame(CockpitDarkColors, scheme())
    }

    @Test
    @Config(sdk = [30], qualifiers = "notnight")
    fun aChangedSettingRecomposesTheTheme() {
        settings.value = UserSettings(theme = ThemeSetting.LIGHT)
        val scheme = show()
        assertSame(CockpitLightColors, scheme())

        settings.value = UserSettings(theme = ThemeSetting.DARK)
        assertSame(CockpitDarkColors, scheme())
    }

    @Test
    fun isDarkFollowsTheSystemOnlyForSystem() {
        assertTrue(isDark(ThemeSetting.DARK, systemDark = false))
        assertFalse(isDark(ThemeSetting.LIGHT, systemDark = true))
        assertTrue(isDark(ThemeSetting.SYSTEM, systemDark = true))
        assertFalse(isDark(ThemeSetting.SYSTEM, systemDark = false))
    }

    @Test
    fun renamedLabelsReplaceTheTranslationOthersKeepIt() {
        settings.value = UserSettings(energyLabels = mapOf("LOW" to "Tired"), creativityLabels = mapOf("CREATIVE" to "Flow"))
        composeRule.setContent {
            CockpitSettingsTheme(settings) {
                Text(userEnergyLabel("LOW", R.string.next_energy_low) + "|" + userEnergyLabel("HIGH", R.string.next_energy_high))
                Text(userCreativityLabel("CREATIVE", R.string.next_creativity_creative))
            }
        }
        composeRule.onNodeWithText("Tired|High").assertExists()
        composeRule.onNodeWithText("Flow").assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun withoutSettingsTheAppTranslationIsShown() {
        var label = ""
        composeRule.setContent { CockpitSettingsTheme(settings) { label = userEnergyLabel("MEDIUM", R.string.next_energy_medium) } }
        composeRule.waitForIdle()
        assertEquals("Mittel", label)
    }
}
