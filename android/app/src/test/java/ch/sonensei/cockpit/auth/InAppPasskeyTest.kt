package ch.sonensei.cockpit.auth

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.core.network.ServerUrlRepository
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.screenTag
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

/**
 * Passkeys in the running app with the real Hilt graph, HTTP client and Credential Manager, against a fake server:
 * the settings of a session list the user's passkeys, and the login screen offers the passkey login.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class InAppPasskeyTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @Inject lateinit var tokenStore: PersistentTokenStore

    @Inject lateinit var serverUrls: ServerUrlRepository

    private val server = MockWebServer()
    private val paths = CopyOnWriteArrayList<String>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        // The shell's screens load their own data too: answer only the passkey calls, everything else is not found.
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    paths += path
                    return when (path) {
                        "/api/v1/auth/passkeys" -> json(200, "[$PASSKEY]")
                        "/api/v1/auth/passkeys/authentication/options" -> json(200, AUTH_OPTIONS)
                        else -> json(404, PROBLEM)
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        scenario?.close()
        server.close()
    }

    @Test
    fun `the settings of a session list the user's passkeys`() {
        runBlocking { serverUrls.setServerUrl(server.url("/").toString()) }
        tokenStore.login(AuthTokens("access-1", "refresh-1"), null)
        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText("Settings"))
        composeRule.onNodeWithText("Settings").performClick()

        composeRule.onNodeWithTag(PasskeyTags.SECTION).assertIsDisplayed()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithText("Laptop").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Laptop").assertIsDisplayed()
    }

    @Test
    fun `the login screen starts a passkey login with only the server and waits for the passkey sheet`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNodeWithTag(LoginTags.SERVER_URL).performTextInput(server.url("/").toString())
        composeRule.onNodeWithTag(LoginTags.PASSKEY).performScrollTo().performClick()

        // The options were fetched and handed to Credential Manager. Robolectric has no system sheet that would
        // answer, so the login stays pending: no token request, nothing stored, both actions locked.
        // Robolectric's main looper only runs when idled: the coroutine resumes on it between the requests.
        composeRule.waitUntil(WAIT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            "/api/v1/auth/passkeys/authentication/options" in paths
        }
        composeRule.onNodeWithTag(LoginTags.PASSKEY).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(LoginTags.SCREEN).assertIsDisplayed()
        assertFalse("/api/v1/auth/passkeys/token" in paths)
        assertNull(tokenStore.current())
    }

    private fun json(
        code: Int,
        body: String,
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", if (code < 400) "application/json" else "application/problem+json")
        .body(body)
        .build()

    private companion object {
        const val WAIT_MS = 5_000L
        const val PROBLEM = """{"type":"about:blank","title":"Fictitious problem","status":404}"""
        const val PASSKEY = """{"id":"0192f1a0-0000-7000-8000-000000000009","name":"Laptop","createdAt":"2026-10-22T10:00:00Z"}"""
        const val AUTH_OPTIONS =
            """{"ceremonyId":"ceremony-1","publicKey":{"challenge":"Y2hhbGxlbmdl","rpId":"cockpit.example.org",
               "allowCredentials":[],"timeout":300000,"userVerification":"required"}}"""
    }
}
