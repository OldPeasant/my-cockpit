package ch.sonensei.cockpit.calendar.agenda

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * Inside the Hilt activity the Calendar tab is the real calendar with its Hilt view model, not the placeholder. No
 * server is configured, so its first load fails as offline, and the screen says so instead of spinning forever.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class CalendarScreenInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @Inject lateinit var tokenStore: PersistentTokenStore

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun `the Calendar tab is the real agenda, and without a server it says it is offline`() {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.onNode(hasText("Calendar") and isSelectable()).performClick()

        composeRule.onNodeWithTag(CalendarTags.AGENDA).assertIsDisplayed()
        composeRule.onNodeWithTag(CalendarTags.view(CalendarView.AGENDA)).assertIsSelected()
        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertDoesNotExist()
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodes(hasTestTag(CalendarTags.MESSAGE)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Can't reach the server. Showing the last loaded events.").assertIsDisplayed()

        // The view switch reaches the view model: the week view replaces the agenda.
        composeRule.onNodeWithTag(CalendarTags.view(CalendarView.WEEK)).performClick()
        composeRule.onNodeWithTag(CalendarTags.TIME_GRID).assertIsDisplayed()
        composeRule.onNodeWithTag(CalendarTags.AGENDA).assertDoesNotExist()
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
