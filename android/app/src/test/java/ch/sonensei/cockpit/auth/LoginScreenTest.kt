package ch.sonensei.cockpit.auth

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

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
    fun `the toggle switches back from recovery code to authenticator code`() {
        var state by mutableStateOf(LoginUiState(useRecoveryCode = true))
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

        composeRule.onNodeWithText("Use the authenticator app instead").performScrollTo().performClick()

        assertFalse(state.useRecoveryCode)
        composeRule.onNodeWithText("Code from your authenticator app").performScrollTo().assertIsDisplayed()
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

    @Test
    fun `each error shows its own message`() {
        var state by mutableStateOf(LoginUiState())
        composeRule.setContent { LoginScreen(state, {}, {}, {}, {}, {}, {}) }
        val messages =
            mapOf(
                LoginError.SERVER_URL_MALFORMED to "Enter the server address as an http(s) URL",
                LoginError.SERVER_URL_HTTPS_REQUIRED to "The server needs an https address",
                LoginError.INVALID_REQUEST to "The server could not read the sign-in request",
                LoginError.WRONG_CREDENTIALS to "Username, password or code is wrong",
                LoginError.TOO_MANY_ATTEMPTS to "Too many failed attempts",
                LoginError.NETWORK to "The server cannot be reached",
                LoginError.SERVER to "The server had a problem signing you in",
                LoginError.PASSKEY_REJECTED to "The server did not accept this passkey",
                LoginError.NO_PASSKEY to "This device has no passkey for this server",
                LoginError.PASSKEY_UNAVAILABLE to "Passkeys are not available on this device",
                LoginError.PASSKEY_FAILED to "The passkey could not be used",
                LoginError.TOTP_RESET_REQUIRED to "Set up your authenticator app again",
            )
        assertEquals(LoginError.entries.toSet(), messages.keys)

        composeRule.onNodeWithTag(LoginTags.ERROR).assertDoesNotExist()
        messages.forEach { (error, message) ->
            state = LoginUiState(error = error)
            composeRule.onNodeWithTag(LoginTags.ERROR).performScrollTo().assertTextContains(message, substring = true)
        }
    }

    @Test
    fun `a server address error quotes the address as the app understood it`() {
        var state by mutableStateOf(LoginUiState(error = LoginError.SERVER_URL_MALFORMED, errorAddress = "https://my-cockpit.sonensei?x"))
        composeRule.setContent { LoginScreen(state, {}, {}, {}, {}, {}, {}) }

        composeRule
            .onNodeWithTag(LoginTags.ERROR)
            .performScrollTo()
            .assertTextContains("\"https://my-cockpit.sonensei?x\" is not a valid server address", substring = true)
        state = LoginUiState(error = LoginError.SERVER_URL_HTTPS_REQUIRED, errorAddress = "http://my-cockpit.sonensei.ch")
        composeRule
            .onNodeWithTag(LoginTags.ERROR)
            .performScrollTo()
            .assertTextContains("\"http://my-cockpit.sonensei.ch\" uses plain http", substring = true)
        // Nothing understood (e.g. only invisible characters): the general message instead of empty quotes.
        state = LoginUiState(error = LoginError.SERVER_URL_MALFORMED, errorAddress = "")
        composeRule
            .onNodeWithTag(LoginTags.ERROR)
            .performScrollTo()
            .assertTextContains("Enter the server address as an http(s) URL", substring = true)
    }

    @Test
    fun `the server address keyboard neither autocorrects nor capitalizes`() {
        assertEquals(false, ServerUrlKeyboardOptions.autoCorrectEnabled)
        assertEquals(KeyboardType.Uri, ServerUrlKeyboardOptions.keyboardType)
        assertEquals(KeyboardCapitalization.None, ServerUrlKeyboardOptions.capitalization)
    }

    @Test
    fun `the focused server address field asks the keyboard for a URI without autocorrect or capitals`() {
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            LoginScreen(LoginUiState(), {}, {}, {}, {}, {}, {})
        }

        composeRule.onNodeWithTag(LoginTags.SERVER_URL).performClick()
        val info = EditorInfo()
        val connection = composeRule.runOnIdle { view.onCreateInputConnection(info) }

        assertNotNull("the focused field opened no input connection", connection)
        assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_URI, info.inputType and InputType.TYPE_MASK_VARIATION)
        assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        val capitals =
            listOf(InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, InputType.TYPE_TEXT_FLAG_CAP_WORDS, InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        capitals.forEach { assertEquals(0, info.inputType and it) }
    }

    @Test
    fun `a non-address error ignores a leftover address and shows its own message`() {
        show(LoginUiState(error = LoginError.NETWORK, errorAddress = "https://my-cockpit.sonensei.ch"))

        val error = composeRule.onNodeWithTag(LoginTags.ERROR).performScrollTo()
        error.assertTextContains("The server cannot be reached", substring = true)
        error.assert(
            SemanticsMatcher("does not quote the address") { node ->
                node.config
                    .getOrElse(SemanticsProperties.Text) { emptyList() }
                    .none { it.text.contains("my-cockpit.sonensei.ch") }
            },
        )
    }

    @Test
    fun `a server address error without an understood address shows the general message`() {
        var state by mutableStateOf(LoginUiState(error = LoginError.SERVER_URL_HTTPS_REQUIRED, errorAddress = null))
        composeRule.setContent { LoginScreen(state, {}, {}, {}, {}, {}, {}) }

        composeRule
            .onNodeWithTag(LoginTags.ERROR)
            .performScrollTo()
            .assertTextContains("The server needs an https address. Plain http only works for this device.", substring = true)
        state = LoginUiState(error = LoginError.SERVER_URL_HTTPS_REQUIRED, errorAddress = "")
        composeRule
            .onNodeWithTag(LoginTags.ERROR)
            .performScrollTo()
            .assertTextContains("The server needs an https address. Plain http only works for this device.", substring = true)
    }

    @Test
    @Config(qualifiers = "de")
    fun `the server address errors quote the address in German`() {
        var state by mutableStateOf(LoginUiState(error = LoginError.SERVER_URL_MALFORMED, errorAddress = "https://my-cockpit.sonensei?x"))
        composeRule.setContent { LoginScreen(state, {}, {}, {}, {}, {}, {}) }

        composeRule
            .onNodeWithTag(LoginTags.ERROR)
            .performScrollTo()
            .assertTextContains("„https://my-cockpit.sonensei?x“ ist keine gültige Serveradresse", substring = true)
        state = LoginUiState(error = LoginError.SERVER_URL_HTTPS_REQUIRED, errorAddress = "http://my-cockpit.sonensei.ch")
        composeRule
            .onNodeWithTag(LoginTags.ERROR)
            .performScrollTo()
            .assertTextContains("„http://my-cockpit.sonensei.ch“ verwendet einfaches http", substring = true)
    }

    @Test
    fun `only a server address error marks the address field`() {
        var state by mutableStateOf(LoginUiState(error = LoginError.SERVER_URL_HTTPS_REQUIRED))
        composeRule.setContent { LoginScreen(state, {}, {}, {}, {}, {}, {}) }
        val markedAsError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)

        composeRule.onNodeWithTag(LoginTags.SERVER_URL).assert(markedAsError)
        state = LoginUiState(error = LoginError.SERVER_URL_MALFORMED)
        composeRule.onNodeWithTag(LoginTags.SERVER_URL).assert(markedAsError)
        state = LoginUiState(error = LoginError.WRONG_CREDENTIALS)
        composeRule.onNodeWithTag(LoginTags.SERVER_URL).assert(!markedAsError)
    }

    @Test
    fun `the password field is masked`() {
        show(filled)

        composeRule.onNodeWithTag(LoginTags.PASSWORD).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        composeRule.onNodeWithTag(LoginTags.USERNAME).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
    }

    @Test
    fun `done on the keyboard in the code field submits`() {
        var submitted = 0
        composeRule.setContent { LoginScreen(filled, {}, {}, {}, {}, {}, { submitted++ }) }

        composeRule.onNodeWithTag(LoginTags.CODE).performScrollTo().performImeAction()

        assertEquals(1, submitted)
    }

    @Test
    fun `a one second wait reads in the singular`() {
        show(filled.copy(error = LoginError.TOO_MANY_ATTEMPTS, retryAfterSeconds = 1))

        composeRule.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().assertTextContains("Try again in 1 second")
    }

    @Test
    fun `while signing in the second factor toggle is locked too`() {
        show(filled.copy(submitting = true))

        composeRule.onNodeWithTag(LoginTags.TOGGLE_FACTOR).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(LoginTags.SERVER_URL).assertIsNotEnabled()
        composeRule.onNodeWithTag(LoginTags.PASSWORD).assertIsNotEnabled()
        composeRule.onNodeWithTag(LoginTags.CODE).assertIsNotEnabled()
    }

    @Test
    fun `the brand mark is decorative and the title alone names the screen`() {
        show(filled)

        // The 72 dp logo has contentDescription = null: no node exposes a description (or role) for it to TalkBack.
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription), useUnmergedTree = true)
            .assertCountEquals(0)
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Role), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .forEach { assertNotEquals(Role.Image, it.config[SemanticsProperties.Role]) }
        composeRule.onAllNodesWithText("Sign in to your cockpit").assertCountEquals(1)
    }

    @Test
    fun `the TOTP re-enrollment error offers a button that opens the web enrollment page`() {
        val opened = mutableListOf<String>()
        val uriHandler =
            object : UriHandler {
                override fun openUri(uri: String) {
                    opened += uri
                }
            }
        composeRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                LoginScreen(
                    LoginUiState(error = LoginError.TOTP_RESET_REQUIRED, totpEnrollmentUrl = "https://cockpit.example.org/enroll-totp"),
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                )
            }
        }

        composeRule
            .onNodeWithTag(LoginTags.OPEN_TOTP_ENROLLMENT)
            .performScrollTo()
            .assertTextContains("Open the setup in the browser")
            .performClick()

        assertEquals(listOf("https://cockpit.example.org/enroll-totp"), opened)
    }

    @Test
    @Config(qualifiers = "de")
    fun `the web enrollment button is labelled in German`() {
        show(LoginUiState(error = LoginError.TOTP_RESET_REQUIRED, totpEnrollmentUrl = "https://cockpit.example.org/enroll-totp"))

        composeRule
            .onNodeWithTag(LoginTags.OPEN_TOTP_ENROLLMENT)
            .performScrollTo()
            .assertTextContains("Einrichtung im Browser öffnen")
    }

    @Test
    fun `the web enrollment button does not crash without a browser`() {
        val noBrowser =
            object : UriHandler {
                override fun openUri(uri: String): Unit = throw IllegalStateException("no activity can open $uri")
            }
        composeRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides noBrowser) {
                LoginScreen(
                    LoginUiState(error = LoginError.TOTP_RESET_REQUIRED, totpEnrollmentUrl = "https://cockpit.example.org/enroll-totp"),
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                )
            }
        }

        composeRule.onNodeWithTag(LoginTags.OPEN_TOTP_ENROLLMENT).performScrollTo().performClick()

        composeRule.onNodeWithTag(LoginTags.ERROR).assertExists()
        composeRule.onNodeWithTag(LoginTags.OPEN_TOTP_ENROLLMENT).assertExists()
    }

    @Test
    fun `the web enrollment button is shown only with the TOTP re-enrollment error`() {
        var state by mutableStateOf(LoginUiState(error = LoginError.TOTP_RESET_REQUIRED))
        composeRule.setContent { LoginScreen(state, {}, {}, {}, {}, {}, {}) }

        composeRule.onNodeWithTag(LoginTags.OPEN_TOTP_ENROLLMENT).assertDoesNotExist()
        state = LoginUiState(error = LoginError.NETWORK, totpEnrollmentUrl = "https://cockpit.example.org/enroll-totp")
        composeRule.onNodeWithTag(LoginTags.OPEN_TOTP_ENROLLMENT).assertDoesNotExist()
        state = LoginUiState(totpEnrollmentUrl = "https://cockpit.example.org/enroll-totp")
        composeRule.onNodeWithTag(LoginTags.OPEN_TOTP_ENROLLMENT).assertDoesNotExist()
    }

    private fun show(state: LoginUiState) =
        composeRule.setContent {
            LoginScreen(state, {}, {}, {}, {}, {}, {})
        }

    private val filled = LoginUiState("https://cockpit.example.org", "alex", "pw", "123456")
}
