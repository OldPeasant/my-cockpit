package ch.sonensei.cockpit.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** The dashboard alone, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val opened = mutableListOf<CockpitDestination>()
    private var refreshes = 0

    private fun show(state: DashboardUiState) =
        composeRule.setContent {
            CockpitTheme { DashboardContent(state, onRefresh = { refreshes++ }, onOpen = { opened += it }) }
        }

    private fun ready(data: WidgetData) = WidgetState(data, loading = false)

    private val allReady =
        mapOf(
            WidgetId.TODAY to
                ready(
                    WidgetData.Today(
                        listOf(DashboardTask(id(1), "File the tax return", LocalDate.now().minusDays(2))),
                        emptyList(),
                        habitsDue = 3,
                        habitsDone = 1,
                    ),
                ),
            WidgetId.INBOX to ready(WidgetData.Inbox(Count(4, more = false), Count(50, more = true))),
            WidgetId.UPCOMING to ready(WidgetData.Upcoming(emptyList())),
            WidgetId.WAITING_OVERDUE to ready(WidgetData.WaitingOverdue(emptyList())),
            WidgetId.STALLED_PROJECTS to ready(WidgetData.StalledProjects(listOf(DashboardItem(id(2), "Move flat")))),
            WidgetId.WEEKLY_REVIEW to ready(WidgetData.WeeklyReview(daysSinceLastReview = 6, openStep = 4)),
            WidgetId.READ_LATER to ready(WidgetData.ReadLater(listOf(DashboardItem(id(3), "A long read")), more = true)),
            WidgetId.PINNED_NOTES to ready(WidgetData.PinnedNotes(emptyList(), more = false)),
        )

    private fun scrollTo(widget: WidgetId) =
        composeRule.onNodeWithTag(DashboardTags.LIST).performScrollToNode(hasTestTag(DashboardTags.card(widget)))

    private fun assertCardShows(
        widget: WidgetId,
        text: String,
    ) {
        scrollTo(widget)
        composeRule
            .onNodeWithTag(DashboardTags.LIST)
            .performScrollToNode(hasText(text, substring = true))
        composeRule.onNode(hasText(text, substring = true), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `every widget shows its data`() {
        show(DashboardUiState(states = allReady))
        assertCardShows(WidgetId.TODAY, "File the tax return")
        assertCardShows(WidgetId.TODAY, "Habits done: 1/3")
        assertCardShows(WidgetId.INBOX, "4 tasks to process")
        assertCardShows(WidgetId.INBOX, "50+ unread emails")
        assertCardShows(WidgetId.UPCOMING, "No events in the next 7 days.")
        assertCardShows(WidgetId.WAITING_OVERDUE, "Nothing overdue.")
        assertCardShows(WidgetId.STALLED_PROJECTS, "Move flat")
        assertCardShows(WidgetId.WEEKLY_REVIEW, "6 days ago")
        assertCardShows(WidgetId.WEEKLY_REVIEW, "Review in progress: step 4 of 8")
        assertCardShows(WidgetId.READ_LATER, "A long read")
        assertCardShows(WidgetId.PINNED_NOTES, "No pinned notes.")
    }

    @Test
    fun `each card opens the matching screen`() {
        show(DashboardUiState(states = allReady))
        WidgetId.entries.forEach { widget ->
            scrollTo(widget)
            composeRule.onNodeWithTag(DashboardTags.card(widget)).performClick()
        }
        assertEquals(
            listOf(
                CockpitDestination.Today,
                CockpitDestination.Inbox,
                CockpitDestination.Calendar,
                CockpitDestination.Waiting,
                CockpitDestination.Projects,
                CockpitDestination.Review,
                CockpitDestination.Bookmarks,
                CockpitDestination.Notes,
            ),
            opened,
        )
    }

    @Test
    fun `only the configured widgets show, in their order`() {
        show(DashboardUiState(widgets = listOf(WidgetId.PINNED_NOTES, WidgetId.INBOX), states = allReady))
        composeRule.onNodeWithTag(DashboardTags.card(WidgetId.PINNED_NOTES)).assertIsDisplayed()
        composeRule.onNodeWithTag(DashboardTags.card(WidgetId.INBOX)).assertIsDisplayed()
        composeRule.onNodeWithTag(DashboardTags.card(WidgetId.TODAY)).assertDoesNotExist()
        val pinned = composeRule.onNodeWithTag(DashboardTags.card(WidgetId.PINNED_NOTES)).fetchSemanticsNode().boundsInRoot
        val inbox = composeRule.onNodeWithTag(DashboardTags.card(WidgetId.INBOX)).fetchSemanticsNode().boundsInRoot
        assertEquals(true, pinned.top < inbox.top)
    }

    @Test
    fun `a failing widget shows its error in its card while the others show their data`() {
        val states = allReady + (WidgetId.TODAY to WidgetState(loading = false, error = WidgetError.FAILED))
        show(DashboardUiState(widgets = listOf(WidgetId.TODAY, WidgetId.INBOX), states = states))
        composeRule.onNodeWithTag(DashboardTags.error(WidgetId.TODAY), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(DashboardTags.error(WidgetId.INBOX), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNode(hasText("4 tasks to process"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `an offline widget keeps its last data under the message`() {
        val states =
            allReady + (WidgetId.STALLED_PROJECTS to allReady.getValue(WidgetId.STALLED_PROJECTS).copy(error = WidgetError.OFFLINE))
        show(DashboardUiState(widgets = listOf(WidgetId.STALLED_PROJECTS), states = states))
        composeRule.onNode(hasText("The server is not reachable.", substring = true), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasText("Move flat"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a loading widget shows its progress`() {
        show(DashboardUiState(widgets = listOf(WidgetId.INBOX)))
        composeRule.onNodeWithTag(DashboardTags.loading(WidgetId.INBOX), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the weekly review card says when no review was done or it was today`() {
        show(
            DashboardUiState(
                widgets = listOf(WidgetId.WEEKLY_REVIEW),
                states = mapOf(WidgetId.WEEKLY_REVIEW to ready(WidgetData.WeeklyReview(daysSinceLastReview = null, openStep = null))),
            ),
        )
        composeRule.onNode(hasText("No weekly review completed yet."), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasText("Review in progress", substring = true), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a review done today and one day ago read naturally`() {
        var review by mutableStateOf(WidgetData.WeeklyReview(daysSinceLastReview = 0, openStep = null))
        composeRule.setContent {
            CockpitTheme {
                DashboardContent(
                    DashboardUiState(widgets = listOf(WidgetId.WEEKLY_REVIEW), states = mapOf(WidgetId.WEEKLY_REVIEW to ready(review))),
                    onRefresh = {},
                    onOpen = {},
                )
            }
        }
        composeRule.onNode(hasText("Today"), useUnmergedTree = true).assertExists()
        review = WidgetData.WeeklyReview(daysSinceLastReview = 1, openStep = null)
        composeRule.onNode(hasText("1 day ago"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `long lists show five rows and a more hint, all-day events say so`() {
        val notes = (1..7).map { DashboardItem(id(it), "Note $it") }
        val events = listOf(DashboardEvent("e:1", "Team offsite", LocalDate.now(), start = null))
        show(
            DashboardUiState(
                widgets = listOf(WidgetId.TODAY, WidgetId.PINNED_NOTES),
                states =
                    mapOf(
                        WidgetId.TODAY to ready(WidgetData.Today(emptyList(), events, habitsDue = 0, habitsDone = 0)),
                        WidgetId.PINNED_NOTES to ready(WidgetData.PinnedNotes(notes, more = false)),
                    ),
            ),
        )
        composeRule.onNode(hasText("All day"), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasText("Team offsite"), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasText("Habits done", substring = true), useUnmergedTree = true).assertDoesNotExist()
        assertCardShows(WidgetId.PINNED_NOTES, "Note 5")
        composeRule.onNode(hasText("Note 6"), useUnmergedTree = true).assertDoesNotExist()
        assertCardShows(WidgetId.PINNED_NOTES, "and more")
    }

    @Test
    fun `pulling down refreshes`() {
        show(DashboardUiState(states = allReady))
        composeRule.onNodeWithTag(DashboardTags.LIST).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertEquals(1, refreshes)
    }
}
