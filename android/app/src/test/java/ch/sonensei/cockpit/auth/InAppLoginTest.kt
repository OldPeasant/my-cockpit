package ch.sonensei.cockpit.auth

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.screenTag
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * The whole login path in the running app: the form on the login screen, the real Hilt graph and HTTP client,
 * a fake server. A successful login stores the tokens (never in plain text) and routes to the shell; a 429 shows the wait.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class InAppLoginTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @Inject lateinit var tokenStore: PersistentTokenStore

    private val server = MockWebServer()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        // The shell's start screen (Today) loads GTD data, which schedules the periodic GTD sync.
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        server.start()
    }

    @After
    fun tearDown() {
        scenario?.close()
        server.close()
    }

    @Test
    fun `signing in with a totp opens the shell and writes no plain token to disk`() {
        server.enqueue(json(200, TOKEN_BODY))
        scenario = ActivityScenario.launch(MainActivity::class.java)

        fillForm(code = "123456")
        composeRule.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().performClick()

        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodesWithTag(screenTag(CockpitDestination.Today)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(LoginTags.SCREEN).assertDoesNotExist()
        assertEquals(DeviceSession(AuthTokens("access-1", "refresh-1"), DEVICE_ID), tokenStore.session.value)
        // Robolectric has no Android Keystore, so the store fails closed (memory only); either way no plain token is on disk.
        // Encrypted persistence with the Keystore cipher is covered by KeystoreTokenCipherTest.
        val stored = storedRecord().orEmpty()
        assertFalse(stored.contains("access-1") || stored.contains("refresh-1"))

        val request = server.takeRequest()
        assertEquals("/api/v1/auth/token", request.url.encodedPath)
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("alex", body["username"]!!.jsonPrimitive.content)
        assertEquals("123456", body["totp"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a 429 from the server shows the error and the wait on a disabled button`() {
        server.enqueue(json(429, PROBLEM).newBuilder().addHeader("Retry-After", "42").build())
        scenario = ActivityScenario.launch(MainActivity::class.java)

        fillForm(code = "123456")
        composeRule.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().performClick()

        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(LoginTags.ERROR).fetchSemanticsNodes().isNotEmpty() }
        composeRule
            .onNodeWithTag(LoginTags.ERROR)
            .performScrollTo()
            .assertTextContains("Too many failed attempts", substring = true)
        composeRule
            .onNodeWithTag(LoginTags.SUBMIT)
            .performScrollTo()
            .assertIsNotEnabled()
            .assertTextContains("Try again in", substring = true)
        composeRule.onNodeWithTag(LoginTags.SCREEN).assertIsDisplayed()
        assertNull(tokenStore.current())
        assertNull(storedRecord())
    }

    @Test
    fun `wrong credentials keep the login screen and store nothing`() {
        server.enqueue(json(401, PROBLEM))
        scenario = ActivityScenario.launch(MainActivity::class.java)

        fillForm(code = "123456")
        composeRule.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().performClick()

        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(LoginTags.ERROR).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(LoginTags.SCREEN).assertIsDisplayed()
        assertNull(tokenStore.current())
        assertNull(storedRecord())
    }

    private fun fillForm(code: String) {
        composeRule.onNodeWithTag(LoginTags.SERVER_URL).performTextInput(server.url("/").toString())
        composeRule.onNodeWithTag(LoginTags.USERNAME).performTextInput("alex")
        composeRule.onNodeWithTag(LoginTags.PASSWORD).performTextInput("fictitious-password")
        composeRule.onNodeWithTag(LoginTags.CODE).performScrollTo().performTextInput(code)
    }

    private fun storedRecord(): String? =
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSharedPreferences(PersistentTokenStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PersistentTokenStore.KEY_SESSION, null)

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
        const val DEVICE_ID = "0192f0a4-7b1c-7cde-8f00-123456789abc"
        const val PROBLEM = """{"type":"about:blank","title":"Fictitious problem","status":400}"""
        val TOKEN_BODY =
            """
            {"accessToken":"access-1","accessTokenExpiresAt":"2026-10-20T12:15:00Z",
             "deviceId":"$DEVICE_ID","refreshToken":"refresh-1",
             "refreshTokenExpiresAt":"2027-01-18T12:00:00Z","tokenType":"Bearer"}
            """.trimIndent()
    }
}
