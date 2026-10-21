package ch.sonensei.cockpit.gtd.inbox

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.core.network.AuthTokens
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The production Hilt graph and activity: the Inbox tab shows the real Inbox, built from the graph. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class InboxGraphTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @Inject lateinit var inbox: InboxRepository

    @Inject lateinit var tokenStore: PersistentTokenStore

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        // The Inbox's reload schedules the background sync.
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun `the graph provides the inbox repository with its patch client`() {
        assertNotNull(inbox)
    }

    @Test
    fun `the inbox tab of the app shows the inbox, not the placeholder`() {
        tokenStore.login(AuthTokens("fictitious-access", "fictitious-refresh"), null)
        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNode(hasText("Inbox") and isSelectable()).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(InboxTags.PROCESS).assertExists()
        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertDoesNotExist()
    }
}
