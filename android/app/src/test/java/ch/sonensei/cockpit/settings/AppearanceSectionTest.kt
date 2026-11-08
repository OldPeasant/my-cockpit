package ch.sonensei.cockpit.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.settings.ThemeSetting
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class AppearanceSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsTheSyncedThemeAndReportsAChoice() {
        val chosen = mutableListOf<ThemeSetting>()
        composeRule.setContent { AppearanceSection(AppearanceUiState(theme = ThemeSetting.SYSTEM)) { chosen += it } }

        composeRule.onNodeWithTag(SettingsTags.theme(ThemeSetting.SYSTEM)).assertIsSelected()
        composeRule.onNodeWithTag(SettingsTags.theme(ThemeSetting.DARK)).assertIsNotSelected()
        composeRule.onNodeWithText("Applies to the web app too.").assertExists()

        composeRule.onNodeWithText("Dark").performClick()
        assertEquals(listOf(ThemeSetting.DARK), chosen)
    }

    @Test
    fun theChoiceIsDisabledWhileTheSettingsAreUnknown() {
        composeRule.setContent { AppearanceSection(AppearanceUiState(theme = null)) {} }

        composeRule.onNodeWithTag(SettingsTags.theme(ThemeSetting.LIGHT)).assertIsNotEnabled()
        composeRule.onNodeWithTag(SettingsTags.APPEARANCE_MESSAGE).assertExists()
        composeRule.onNodeWithText("Your settings could not be loaded yet.").assertExists()
    }

    @Test
    fun aSavingThemeIsShownAsChosenAndAConflictIsExplained() {
        composeRule.setContent {
            AppearanceSection(
                AppearanceUiState(theme = ThemeSetting.LIGHT, saving = ThemeSetting.DARK, message = AppearanceMessage.CONFLICT),
            ) {}
        }

        composeRule.onNodeWithTag(SettingsTags.theme(ThemeSetting.DARK)).assertIsSelected()
        composeRule.onNodeWithTag(SettingsTags.theme(ThemeSetting.DARK)).assertIsNotEnabled()
        composeRule.onNodeWithText("changed elsewhere", substring = true).assertExists()
    }

    @Test
    @Config(qualifiers = "de")
    fun isTranslatedToGerman() {
        composeRule.setContent { AppearanceSection(AppearanceUiState(theme = ThemeSetting.SYSTEM)) {} }

        composeRule.onNodeWithText("Darstellung").assertExists()
        composeRule.onNodeWithText("Wie das System").assertExists()
    }
}
