package ch.sonensei.cockpit.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
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

    @Test
    fun `the passkey button is disabled while a login runs or a wait is due`() {
        var state by mutableStateOf(LoginUiState(serverUrl = "https://cockpit.example.org", submitting = true))
        composeRule.setContent {
            LoginScreen(state, {}, {}, {}, {}, {}, {}, onPasskeySubmit = {})
        }

        composeRule.onNodeWithTag(LoginTags.PASSKEY).performScrollTo().assertIsNotEnabled()
        state = state.copy(submitting = false, retryAfterSeconds = 30)
        composeRule.onNodeWithTag(LoginTags.PASSKEY).performScrollTo().assertIsNotEnabled()
        state = state.copy(retryAfterSeconds = 0)
        composeRule.onNodeWithTag(LoginTags.PASSKEY).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `every passkey login error has its own message`() {
        var state by mutableStateOf(LoginUiState(serverUrl = "https://cockpit.example.org"))
        composeRule.setContent {
            LoginScreen(state, {}, {}, {}, {}, {}, {}, onPasskeySubmit = {})
        }
        val expected =
            mapOf(
                LoginError.PASSKEY_REJECTED to "did not accept this passkey",
                LoginError.NO_PASSKEY to "no passkey for this server",
                LoginError.PASSKEY_UNAVAILABLE to "not available on this device",
                LoginError.PASSKEY_FAILED to "could not be used",
                LoginError.TOTP_RESET_REQUIRED to "authenticator app again in the web app",
            )

        expected.forEach { (error, message) ->
            state = state.copy(error = error)
            composeRule.onNodeWithTag(LoginTags.ERROR).performScrollTo().assertTextContains(message, substring = true)
        }
    }

    @Test
    fun `a used passkey shows when it was last used`() {
        val used = PASSKEY.copy(name = "Phone", lastUsedAt = OffsetDateTime.parse("2026-10-21T08:30:00Z"))
        composeRule.setContent {
            PasskeySettingsSection(PasskeySettingsUiState(passkeys = listOf(PASSKEY, used)), {}, {}, {}, {}, {}, {})
        }

        composeRule.onAllNodesWithTag(PasskeyTags.ITEM).assertCountEquals(2)
        composeRule.onNodeWithText("Last used", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Never used").assertIsDisplayed()
    }

    @Test
    fun `while loading the section shows progress, neither an empty list nor a retry`() {
        composeRule.setContent {
            PasskeySettingsSection(PasskeySettingsUiState(), {}, {}, {}, {}, {}, {})
        }

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        composeRule.onAllNodesWithText("No passkeys yet.").assertCountEquals(0)
        composeRule.onAllNodesWithText("Retry").assertCountEquals(0)
    }

    @Test
    fun `a failed reload keeps the listed passkeys next to the retry`() {
        composeRule.setContent {
            PasskeySettingsSection(PasskeySettingsUiState(passkeys = listOf(PASSKEY), loadFailed = true), {}, {}, {}, {}, {}, {})
        }

        composeRule.onNodeWithText("Laptop").assertIsDisplayed()
        composeRule.onNodeWithText("Your passkeys could not be loaded.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun `an added passkey is confirmed by name`() {
        composeRule.setContent {
            PasskeySettingsSection(PasskeySettingsUiState(passkeys = listOf(PASSKEY), added = "Laptop"), {}, {}, {}, {}, {}, {})
        }

        composeRule.onNodeWithTag(PasskeyTags.ADDED).assertTextContains("Passkey \"Laptop\" added.")
    }

    @Test
    fun `while a passkey is created the form is locked`() {
        val form = PasskeyForm(name = "Pixel", code = "123456", submitting = true)
        var dismissed = 0
        composeRule.setContent {
            PasskeySettingsSection(PasskeySettingsUiState(passkeys = emptyList(), form = form), {}, {}, {}, {}, {}, { dismissed++ })
        }

        composeRule.onNodeWithTag(PasskeyTags.SUBMIT).assertIsNotEnabled().assertTextContains("Creating…")
        composeRule.onNodeWithTag(PasskeyTags.NAME).assertIsNotEnabled()
        composeRule.onNodeWithTag(PasskeyTags.CODE).assertIsNotEnabled()
        composeRule.onNodeWithText("Cancel").assertIsNotEnabled()
        assertEquals(0, dismissed)
    }

    @Test
    fun `the keyboard's done action submits a complete form, and cancel dismisses it`() {
        var state by mutableStateOf(PasskeySettingsUiState(passkeys = emptyList(), form = PasskeyForm(name = "Pixel")))
        var submitted = 0
        var dismissed = 0
        composeRule.setContent {
            PasskeySettingsSection(
                state = state,
                onRetry = {},
                onAdd = {},
                onNameChange = {},
                onCodeChange = { code -> state = state.copy(form = state.form!!.copy(code = code)) },
                onSubmit = { submitted++ },
                onDismiss = { dismissed++ },
            )
        }

        composeRule.onNodeWithTag(PasskeyTags.CODE).performImeAction()
        assertEquals(0, submitted)
        composeRule.onNodeWithTag(PasskeyTags.CODE).performTextInput("123456")
        composeRule.onNodeWithTag(PasskeyTags.CODE).performImeAction()
        assertEquals(1, submitted)
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `every registration error has its own message`() {
        var state by mutableStateOf(PasskeySettingsUiState(passkeys = emptyList(), form = PasskeyForm(name = "Pixel")))
        composeRule.setContent {
            PasskeySettingsSection(state, {}, {}, {}, {}, {}, {})
        }
        val expected =
            mapOf(
                PasskeyError.WRONG_CODE to "The code is wrong",
                PasskeyError.TOO_MANY_ATTEMPTS to "Too many failed attempts",
                PasskeyError.ALREADY_REGISTERED to "already registered",
                PasskeyError.UNAVAILABLE to "not available on this device",
                PasskeyError.FAILED to "could not be created",
                PasskeyError.NETWORK to "cannot be reached",
                PasskeyError.SERVER to "problem adding the passkey",
            )

        expected.forEach { (error, message) ->
            state = state.copy(form = state.form!!.copy(error = error))
            composeRule.onNodeWithTag(PasskeyTags.ERROR).assertTextContains(message, substring = true)
        }
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
