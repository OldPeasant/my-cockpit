package ch.sonensei.cockpit.auth

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.core.network.LogoutReason
import ch.sonensei.cockpit.core.network.TokenStore
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.screenTag
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.inject.Inject

/** The real activity and Hilt graph: app start routes by the stored tokens, and logging out returns to the login. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class AppStartRoutingTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @Inject lateinit var tokenStore: PersistentTokenStore

    @Inject lateinit var boundStore: TokenStore

    @Inject lateinit var session: AuthSession

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        // The shell's start screen (Today) loads GTD data, which schedules the periodic GTD sync,
        // and the shell starts the reminder sync.
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun `the network stack uses the persistent store`() {
        assertSame(tokenStore, boundStore)
    }

    @Test
    fun `without tokens the app starts on the login screen`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNodeWithTag(LoginTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithText("Sign in to your cockpit").assertIsDisplayed()
    }

    @Test
    fun `with tokens the app starts on the shell`() {
        tokenStore.login(AuthTokens("access-1", "refresh-1"), null)

        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNodeWithTag(screenTag(CockpitDestination.Today)).assertIsDisplayed()
        composeRule.onNodeWithTag(LoginTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun `logging out from settings returns to the login screen and forgets the tokens`() {
        tokenStore.login(AuthTokens("access-1", "refresh-1"), null)
        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText("Settings"))
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Log out").performClick()

        composeRule.onNodeWithTag(LoginTags.SCREEN).assertIsDisplayed()
        assertNull(tokenStore.current())
    }

    @Test
    fun `a logout by the network layer also shows the login screen`() {
        tokenStore.login(AuthTokens("access-1", "refresh-1"), null)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Today)).assertIsDisplayed()

        session.logout(LogoutReason.REFRESH_REJECTED)

        composeRule.onNodeWithTag(LoginTags.SCREEN).assertIsDisplayed()
    }

    @Test
    fun `storing a login while the login screen shows switches to the shell`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNodeWithTag(LoginTags.SCREEN).assertIsDisplayed()

        tokenStore.login(AuthTokens("access-1", "refresh-1"), null)

        composeRule.onNodeWithTag(screenTag(CockpitDestination.Today)).assertIsDisplayed()
        composeRule.onNodeWithTag(LoginTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun `the login form in the app reports a server address the app refuses`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNodeWithTag(LoginTags.SERVER_URL).performTextInput("http://cockpit.example.org")
        composeRule.onNodeWithTag(LoginTags.USERNAME).performTextInput("alex")
        composeRule.onNodeWithTag(LoginTags.PASSWORD).performTextInput("fictitious-password")
        composeRule.onNodeWithTag(LoginTags.TOGGLE_FACTOR).performScrollTo().performClick()
        composeRule.onNodeWithText("Recovery code").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(LoginTags.CODE).performTextInput("abcde-fghjk")
        composeRule.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().performClick()

        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(LoginTags.ERROR).fetchSemanticsNodes().isNotEmpty() }
        composeRule
            .onNodeWithTag(LoginTags.ERROR)
            .performScrollTo()
            .assertTextContains("The server needs an https address", substring = true)
        assertNull(tokenStore.current())
    }

    @Test
    fun `logging out from settings clears the cached files`() {
        val cached = cachedFile()
        tokenStore.login(AuthTokens("access-1", "refresh-1"), null)
        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText("Settings"))
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Log out").performClick()
        composeRule.onNodeWithTag(LoginTags.SCREEN).assertIsDisplayed()

        composeRule.waitUntil(WAIT_MS) { !cached.exists() }
        assertFalse(cached.exists())
    }

    @Test
    fun `a logout by the network layer also clears the cached files`() {
        tokenStore.login(AuthTokens("access-1", "refresh-1"), null)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Today)).assertIsDisplayed()
        val cached = cachedFile()

        session.logout(LogoutReason.REFRESH_REJECTED)

        composeRule.onNodeWithTag(LoginTags.SCREEN).assertIsDisplayed()
        composeRule.waitUntil(WAIT_MS) { !cached.exists() }
        assertFalse(cached.exists())
    }

    private fun cachedFile(): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.cacheDir, "thumbnail-fictitious.png").apply {
            writeText("fictitious")
            assertTrue(exists())
        }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
