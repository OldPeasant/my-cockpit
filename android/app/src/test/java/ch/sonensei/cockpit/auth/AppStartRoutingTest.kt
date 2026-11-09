package ch.sonensei.cockpit.auth

import android.content.Context
import android.os.Looper
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
import org.robolectric.Shadows.shadowOf
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

        composeRule.onNodeWithTag(screenTag(CockpitDestination.Dashboard)).assertIsDisplayed()
        composeRule.onNodeWithTag(LoginTags.SCREEN).assertDoesNotExist()
    }

    @Test
    fun `logging out from settings returns to the login screen and forgets the tokens`() {
        tokenStore.login(AuthTokens("access-1", "refresh-1"), null)
        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNode(hasText("More") and isSelectable()).performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText("Settings"))
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Log out").performScrollTo().performClick()

        composeRule.onNodeWithTag(LoginTags.SCREEN).assertIsDisplayed()
        assertNull(tokenStore.current())
    }

    @Test
    fun `a logout by the network layer also shows the login screen`() {
        tokenStore.login(AuthTokens("access-1", "refresh-1"), null)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Dashboard)).assertIsDisplayed()

        session.logout(LogoutReason.REFRESH_REJECTED)

        composeRule.onNodeWithTag(LoginTags.SCREEN).assertIsDisplayed()
    }

    @Test
    fun `storing a login while the login screen shows switches to the shell`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNodeWithTag(LoginTags.SCREEN).assertIsDisplayed()

        tokenStore.login(AuthTokens("access-1", "refresh-1"), null)

        composeRule.onNodeWithTag(screenTag(CockpitDestination.Dashboard)).assertIsDisplayed()
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
        composeRule.onNodeWithText("Log out").performScrollTo().performClick()
        composeRule.onNodeWithTag(LoginTags.SCREEN).assertIsDisplayed()

        awaitDeleted(cached)
        assertFalse(cached.exists())
    }

    @Test
    fun `a logout by the network layer also clears the cached files`() {
        tokenStore.login(AuthTokens("access-1", "refresh-1"), null)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Dashboard)).assertIsDisplayed()
        val cached = cachedFile()

        session.logout(LogoutReason.REFRESH_REJECTED)

        composeRule.onNodeWithTag(LoginTags.SCREEN).assertIsDisplayed()
        awaitDeleted(cached)
        assertFalse(cached.exists())
    }

    /**
     * Waits for the logout to delete [file]. A user logout runs on the main dispatcher, and every cache it clears hops to
     * IO and back, so each step needs a turn of Robolectric's paused main looper. `composeRule.waitUntil` only runs the
     * looper as a side effect of compose frames, which is slow or stalls on a loaded host; this runs it explicitly. On a
     * timeout the app's threads are dumped, so the step the logout is stuck in shows in the failure.
     */
    private fun awaitDeleted(file: File) {
        val deadline = System.nanoTime() + CACHE_CLEAR_WAIT_MS * NANOS_PER_MS
        while (file.exists()) {
            if (System.nanoTime() > deadline) {
                throw AssertionError("The logout did not delete ${file.name} within ${CACHE_CLEAR_WAIT_MS}ms\n${appThreads()}")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
    }

    private fun appThreads(): String =
        Thread
            .getAllStackTraces()
            .filterValues { stack -> stack.any { it.className.startsWith("ch.sonensei.cockpit") } }
            .entries
            .joinToString("\n\n") { (thread, stack) ->
                "${thread.name}:\n" + stack.take(STACK_DEPTH).joinToString("\n") { "    at $it" }
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

        /**
         * The logout clears every session cache one after another (Room tables, WorkManager, push, ...) before the
         * cache directory, so on a loaded host the file goes away much later than the login screen shows.
         */
        const val CACHE_CLEAR_WAIT_MS = 30_000L
        const val POLL_MS = 10L
        const val NANOS_PER_MS = 1_000_000L
        const val STACK_DEPTH = 40
    }
}
