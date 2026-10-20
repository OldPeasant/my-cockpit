package ch.sonensei.cockpit.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
}
