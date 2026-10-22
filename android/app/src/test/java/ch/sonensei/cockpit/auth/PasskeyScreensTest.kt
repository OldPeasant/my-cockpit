package ch.sonensei.cockpit.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.generated.models.PasskeyResponse
import ch.sonensei.cockpit.settings.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.OffsetDateTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PasskeyScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the passkey button needs only the server and reports its errors`() {
        var state by mutableStateOf(LoginUiState())
        var passkeyLogins = 0
        composeRule.setContent {
            LoginScreen(
                state = state,
                onServerUrlChange = { state = state.copy(serverUrl = it) },
                onUsernameChange = {},
                onPasswordChange = {},
                onCodeChange = {},
                onUseRecoveryCodeChange = {},
                onSubmit = {},
                onPasskeySubmit = { passkeyLogins++ },
            )
        }

        composeRule.onNodeWithTag(LoginTags.PASSKEY).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(LoginTags.SERVER_URL).performTextInput("https://cockpit.example.org")
        composeRule
            .onNodeWithTag(LoginTags.PASSKEY)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, passkeyLogins)

        state = state.copy(error = LoginError.NO_PASSKEY)
        composeRule.onNodeWithTag(LoginTags.ERROR).assertTextContains("no passkey", substring = true)
    }

    @Test
    fun `without a passkey action the login has no passkey button`() {
        composeRule.setContent {
            LoginScreen(LoginUiState(), {}, {}, {}, {}, {}, {})
        }

        composeRule.onAllNodesWithTag(LoginTags.PASSKEY).assertCountEquals(0)
    }

    @Test
    fun `the section lists passkeys and opens the add form`() {
        var state by mutableStateOf(PasskeySettingsUiState(passkeys = listOf(PASSKEY)))
        var submitted = 0
        composeRule.setContent {
            PasskeySettingsSection(
                state = state,
                onRetry = {},
                onAdd = { state = state.copy(form = PasskeyForm(name = "Pixel 9")) },
                onNameChange = { name -> state = state.copy(form = state.form!!.copy(name = name)) },
                onCodeChange = { code -> state = state.copy(form = state.form!!.copy(code = code)) },
                onSubmit = { submitted++ },
                onDismiss = { state = state.copy(form = null) },
            )
        }

        composeRule.onNodeWithText("Laptop").assertIsDisplayed()
        composeRule.onNodeWithText("Never used").assertIsDisplayed()
        composeRule.onNodeWithTag(PasskeyTags.ADD).performClick()
        composeRule.onNodeWithTag(PasskeyTags.SUBMIT).assertIsNotEnabled()
        composeRule.onNodeWithTag(PasskeyTags.CODE).performTextInput("123456")
        composeRule.onNodeWithTag(PasskeyTags.SUBMIT).assertIsEnabled().performClick()
        assertEquals(1, submitted)

        state = state.copy(form = state.form!!.copy(error = PasskeyError.WRONG_CODE))
        composeRule.onNodeWithTag(PasskeyTags.ERROR).assertTextContains("code is wrong", substring = true)
    }

    @Test
    fun `an empty or failed list says so`() {
        var state by mutableStateOf(PasskeySettingsUiState(passkeys = emptyList()))
        var retries = 0
        composeRule.setContent {
            PasskeySettingsSection(state, onRetry = { retries++ }, {}, {}, {}, {}, {})
        }

        composeRule.onNodeWithText("No passkeys yet.").assertIsDisplayed()
        state = PasskeySettingsUiState(loadFailed = true)
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `settings show passkeys only in a session`() {
        composeRule.setContent { SettingsScreen() }

        composeRule.onAllNodesWithTag(PasskeyTags.SECTION).assertCountEquals(0)
    }

    private companion object {
        val PASSKEY =
            PasskeyResponse(
                createdAt = OffsetDateTime.parse("2026-10-22T10:00:00Z"),
                id = UUID.fromString("0192f1a0-0000-7000-8000-000000000009"),
                name = "Laptop",
            )
    }
}
