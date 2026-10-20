package ch.sonensei.cockpit.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `typing fills the form and enables sign-in`() {
        var state by mutableStateOf(LoginUiState())
        var submitted = 0
        composeRule.setContent {
            LoginScreen(
                state = state,
                onServerUrlChange = { state = state.copy(serverUrl = it) },
                onUsernameChange = { state = state.copy(username = it) },
                onPasswordChange = { state = state.copy(password = it) },
                onCodeChange = { state = state.copy(code = it) },
                onUseRecoveryCodeChange = { state = state.copy(useRecoveryCode = it) },
                onSubmit = { submitted++ },
            )
        }

        composeRule.onNodeWithText("Sign in to your cockpit").assertIsDisplayed()
        composeRule.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().assertIsNotEnabled()

        composeRule.onNodeWithTag(LoginTags.SERVER_URL).performTextInput("https://cockpit.example.org")
        composeRule.onNodeWithTag(LoginTags.USERNAME).performTextInput("alex")
        composeRule.onNodeWithTag(LoginTags.PASSWORD).performTextInput("fictitious")
        composeRule.onNodeWithTag(LoginTags.CODE).performTextInput("123456")
        composeRule
            .onNodeWithTag(LoginTags.SUBMIT)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        assertEquals(LoginUiState("https://cockpit.example.org", "alex", "fictitious", "123456"), state)
        assertEquals(1, submitted)
    }

    @Test
    fun `the toggle switches between authenticator code and recovery code`() {
        var state by mutableStateOf(LoginUiState())
        composeRule.setContent {
            LoginScreen(
                state = state,
                onServerUrlChange = {},
                onUsernameChange = {},
                onPasswordChange = {},
                onCodeChange = {},
                onUseRecoveryCodeChange = { state = state.copy(useRecoveryCode = it) },
                onSubmit = {},
            )
        }

        composeRule.onNodeWithText("Code from your authenticator app").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(LoginTags.TOGGLE_FACTOR).performScrollTo().performClick()

        assertTrue(state.useRecoveryCode)
        composeRule.onNodeWithText("Recovery code").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Use the authenticator app instead").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an error is shown with how to fix it`() {
        show(LoginUiState(error = LoginError.WRONG_CREDENTIALS))

        composeRule
            .onNodeWithTag(LoginTags.ERROR)
            .performScrollTo()
            .assertTextContains("Username, password or code is wrong", substring = true)
    }

    @Test
    fun `too many attempts shows the countdown on a disabled button`() {
        show(filled.copy(error = LoginError.TOO_MANY_ATTEMPTS, retryAfterSeconds = 42))

        composeRule.onNodeWithTag(LoginTags.ERROR).performScrollTo().assertTextContains("Too many failed attempts", substring = true)
        composeRule
            .onNodeWithTag(LoginTags.SUBMIT)
            .performScrollTo()
            .assertIsNotEnabled()
            .assertTextContains("Try again in 42 seconds")
    }

    @Test
    fun `while signing in the form is locked`() {
        show(filled.copy(submitting = true))

        composeRule
            .onNodeWithTag(LoginTags.SUBMIT)
            .performScrollTo()
            .assertIsNotEnabled()
            .assertTextContains("Signing in…")
        composeRule.onNodeWithTag(LoginTags.USERNAME).assertIsNotEnabled()
    }

    private fun show(state: LoginUiState) =
        composeRule.setContent {
            LoginScreen(state, {}, {}, {}, {}, {}, {})
        }

    private val filled = LoginUiState("https://cockpit.example.org", "alex", "pw", "123456")
}
