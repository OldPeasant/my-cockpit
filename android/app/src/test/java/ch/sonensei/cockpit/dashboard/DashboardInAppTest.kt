package ch.sonensei.cockpit.dashboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.screenTag
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
 * The Dashboard in the Hilt activity with a fake repository: the app opens on the real dashboard (not the placeholder),
 * the stored layout decides the cards, a failing widget keeps its error in its own card, pull to refresh reloads, and
 * the cards open their screens through the real shell navigation.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class DashboardInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val repo: DashboardRepository = mockk()

    @Inject lateinit var tokenStore: PersistentTokenStore

    private val inbox = WidgetData.Inbox(Count(3, more = false), Count(0, more = false))
    private val today = WidgetData.Today(emptyList(), emptyList(), habitsDue = 0, habitsDone = 0)

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        // The user's layout starts with Inbox, unlike the default order (Today first) shown before it loads.
        coEvery { repo.layout() } returns DashboardResult.Ok(listOf(WidgetId.INBOX, WidgetId.TODAY, WidgetId.WEEKLY_REVIEW))
        coEvery { repo.load(WidgetId.INBOX) } returns DashboardResult.Ok(inbox)
        coEvery { repo.load(WidgetId.TODAY) } returns DashboardResult.Ok(today)
        coEvery { repo.load(WidgetId.WEEKLY_REVIEW) } returns DashboardResult.Failed
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun waitForTag(tag: String) =
        composeRule.waitUntil(WAIT_MS) { composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForTag(DashboardTags.LIST)
        composeRule.waitUntil(WAIT_MS) {
            composeRule.onAllNodesWithTag(DashboardTags.loading(WidgetId.INBOX), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }

    /** Back to the first card (see `the first configured card is at the top when the app opens`). */
    private fun scrollToTop() = composeRule.onNodeWithTag(DashboardTags.LIST).performScrollToIndex(0)

    private fun tab(label: String) = composeRule.onNode(hasText(label) and isSelectable())

    @Test
    fun `with stored tokens the app opens on the real dashboard, selected in the bottom bar`() {
        launch()

        composeRule.onNodeWithTag(screenTag(CockpitDestination.Dashboard)).assertIsDisplayed()
        tab("Dashboard").assertIsSelected()
        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertDoesNotExist()
        composeRule.onNode(hasText("3 tasks to process"), useUnmergedTree = true).assertExists()
        coVerify(exactly = 1) { repo.layout() }
    }

    /**
     * The stored layout starts with Inbox; the default one shown before it loads starts with Today. The first card of
     * the user's layout must be the one at the top when the app opens, not scrolled away above the screen.
     */
    @Test
    fun `the first configured card is at the top when the app opens`() {
        launch()

        composeRule.onNodeWithTag(DashboardTags.card(WidgetId.INBOX)).assertIsDisplayed()
        composeRule.onNode(hasText("3 tasks to process"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `only the configured widgets show, and a failing one shows its error in its own card`() {
        launch()
        waitForTag(DashboardTags.error(WidgetId.WEEKLY_REVIEW))

        composeRule.onNodeWithTag(DashboardTags.card(WidgetId.UPCOMING)).assertDoesNotExist()
        composeRule.onNodeWithTag(DashboardTags.card(WidgetId.PINNED_NOTES)).assertDoesNotExist()
        composeRule.onNodeWithTag(DashboardTags.error(WidgetId.INBOX), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(DashboardTags.error(WidgetId.TODAY), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNode(hasText("Nothing due today."), useUnmergedTree = true).assertIsDisplayed()
        coVerify(exactly = 0) { repo.load(WidgetId.UPCOMING) }
    }

    @Test
    fun `pulling down reloads the layout and every shown widget`() {
        launch()
        waitForTag(DashboardTags.error(WidgetId.WEEKLY_REVIEW))
        scrollToTop()

        composeRule.onNodeWithTag(DashboardTags.LIST).performTouchInput { swipeDown() }
        composeRule.waitUntil(WAIT_MS) {
            runCatching { coVerify(exactly = 2) { repo.load(WidgetId.WEEKLY_REVIEW) } }.isSuccess
        }

        coVerify(exactly = 2) { repo.layout() }
        coVerify(exactly = 2) { repo.load(WidgetId.INBOX) }
        coVerify(exactly = 2) { repo.load(WidgetId.TODAY) }
    }

    @Test
    fun `the Inbox card switches to the Inbox tab, and the Dashboard tab returns to the dashboard`() {
        launch()
        scrollToTop()

        composeRule.onNodeWithTag(DashboardTags.card(WidgetId.INBOX)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(screenTag(CockpitDestination.Inbox)).assertIsDisplayed()
        tab("Inbox").assertIsSelected()
        tab("Dashboard").assertIsNotSelected()

        tab("Dashboard").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Dashboard)).assertIsDisplayed()
        tab("Dashboard").assertIsSelected()
    }

    @Test
    fun `the Today card opens the real Today screen on top of the dashboard`() {
        launch()

        composeRule.onNodeWithTag(DashboardTags.LIST).performScrollToNode(hasTestTag(DashboardTags.card(WidgetId.TODAY)))
        composeRule.onNodeWithTag(DashboardTags.card(WidgetId.TODAY)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(screenTag(CockpitDestination.Today)).assertIsDisplayed()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Dashboard)).assertDoesNotExist()
        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertDoesNotExist()
    }

    @Test
    fun `the dashboard loads again when it is shown again`() {
        launch()
        scrollToTop()
        composeRule.onNodeWithTag(DashboardTags.card(WidgetId.INBOX)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Inbox)).assertIsDisplayed()

        tab("Dashboard").performClick()
        composeRule.waitUntil(WAIT_MS) { runCatching { coVerify(exactly = 2) { repo.layout() } }.isSuccess }
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
