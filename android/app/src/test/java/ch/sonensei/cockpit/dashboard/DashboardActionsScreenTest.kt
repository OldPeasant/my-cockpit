package ch.sonensei.cockpit.dashboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID

/** Tapping the items of the cards, the review button and the layout editor, with actions the tests record. */
@RunWith(AndroidJUnit4::class)
class DashboardActionsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val opened = mutableListOf<CockpitDestination>()

    /** Every action call, as text. */
    private val calls = mutableListOf<String>()

    private val recording =
        DashboardActions(
            openEvent = { calls += "event ${it.key}" },
            openProject = { calls += "project $it" },
            openNote = { calls += "note $it" },
            openBookmark = { calls += "bookmark ${it.url}" },
            customize = { calls += "customize $it" },
            setVisible = { widget, visible -> calls += "visible ${widget.key} $visible" },
            move = { widget, index -> calls += "move ${widget.key} $index" },
            reset = { calls += "reset" },
        )

    private fun ready(data: WidgetData) = WidgetState(data, loading = false)

    private fun show(
        state: DashboardUiState,
        actions: DashboardActions = recording,
    ) = composeRule.setContent {
        CockpitTheme { DashboardContent(state, onRefresh = {}, onOpen = { opened += it }, actions = actions) }
    }

    private fun one(
        widget: WidgetId,
        data: WidgetData,
    ) = DashboardUiState(widgets = listOf(widget), states = mapOf(widget to ready(data)), layout = layoutOf(widget), layoutLoaded = true)

    private fun tap(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true).performClick()

    private val today = LocalDate.of(2026, 10, 21)
    private val dentist = DashboardEvent("e:1", "Dentist", today, start = null)

    // Items

    @Test
    fun `an event of the Today and the Upcoming card opens the event, not the card's screen`() {
        show(
            DashboardUiState(
                widgets = listOf(WidgetId.TODAY, WidgetId.UPCOMING),
                states =
                    mapOf(
                        WidgetId.TODAY to ready(WidgetData.Today(emptyList(), listOf(dentist), habitsDue = 0, habitsDone = 0)),
                        WidgetId.UPCOMING to ready(WidgetData.Upcoming(listOf(dentist.copy(key = "e:2")))),
                    ),
            ),
        )
        tap(DashboardTags.event("e:1"))
        tap(DashboardTags.event("e:2"))
        assertEquals(listOf("event e:1", "event e:2"), calls)
        assertEquals(emptyList<CockpitDestination>(), opened)
    }

    @Test
    fun `without an event opener an event opens the calendar, also from the Today card`() {
        show(one(WidgetId.TODAY, WidgetData.Today(emptyList(), listOf(dentist), habitsDue = 0, habitsDone = 0)), DashboardActions())
        tap(DashboardTags.event("e:1"))
        assertEquals(listOf(CockpitDestination.Calendar), opened)
    }

    @Test
    fun `a stalled project, a read-later bookmark and a pinned note open themselves`() {
        show(
            DashboardUiState(
                widgets = listOf(WidgetId.STALLED_PROJECTS, WidgetId.READ_LATER, WidgetId.PINNED_NOTES),
                states =
                    mapOf(
                        WidgetId.STALLED_PROJECTS to ready(WidgetData.StalledProjects(listOf(DashboardItem(id(1), "Move flat")))),
                        WidgetId.READ_LATER to
                            ready(WidgetData.ReadLater(listOf(DashboardItem(id(2), "A long read", "https://www.example.org/read")), false)),
                        WidgetId.PINNED_NOTES to ready(WidgetData.PinnedNotes(listOf(DashboardItem(id(3), "Packing list")), false)),
                    ),
            ),
        )
        tap(DashboardTags.item(id(1)))
        tap(DashboardTags.item(id(2)))
        tap(DashboardTags.item(id(3)))
        assertEquals(listOf("project ${id(1)}", "bookmark https://www.example.org/read", "note ${id(3)}"), calls)
        assertEquals(emptyList<CockpitDestination>(), opened)
    }

    @Test
    fun `without openers the items open their card's screen`() {
        show(
            DashboardUiState(
                widgets = listOf(WidgetId.STALLED_PROJECTS, WidgetId.READ_LATER, WidgetId.PINNED_NOTES),
                states =
                    mapOf(
                        WidgetId.STALLED_PROJECTS to ready(WidgetData.StalledProjects(listOf(DashboardItem(id(1), "Move flat")))),
                        WidgetId.READ_LATER to ready(WidgetData.ReadLater(listOf(DashboardItem(id(2), "A long read")), false)),
                        WidgetId.PINNED_NOTES to ready(WidgetData.PinnedNotes(listOf(DashboardItem(id(3), "Packing list")), false)),
                    ),
            ),
            DashboardActions(),
        )
        tap(DashboardTags.item(id(1)))
        tap(DashboardTags.item(id(2)))
        tap(DashboardTags.item(id(3)))
        assertEquals(listOf(CockpitDestination.Projects, CockpitDestination.Bookmarks, CockpitDestination.Notes), opened)
    }

    @Test
    fun `the Inbox lines open the GTD Inbox and Mail whatever the card would open`() {
        // No tasks but unread mail: the card itself opens Mail, its task line still the GTD Inbox.
        show(one(WidgetId.INBOX, WidgetData.Inbox(Count(0, more = false), Count(3, more = false))))
        tap(DashboardTags.INBOX_TASKS)
        tap(DashboardTags.INBOX_MAIL)
        assertEquals(listOf(CockpitDestination.Inbox, CockpitDestination.Mail), opened)
    }

    @Test
    fun `the habits line of the Today card opens Habits`() {
        show(one(WidgetId.TODAY, WidgetData.Today(emptyList(), emptyList(), habitsDue = 2, habitsDone = 1)))
        tap(DashboardTags.HABITS)
        assertEquals(listOf(CockpitDestination.Habits), opened)
    }

    // Weekly review

    private fun showReview(
        days: Long?,
        step: Int?,
    ) = show(one(WidgetId.WEEKLY_REVIEW, WidgetData.WeeklyReview(days, step)))

    @Test
    fun `an open review offers to continue it and the button opens the review`() {
        showReview(days = 2, step = 3)
        composeRule.onNode(hasText("Continue the open review"), useUnmergedTree = true).assertIsDisplayed()
        tap(DashboardTags.REVIEW_BUTTON)
        assertEquals(listOf(CockpitDestination.Review), opened)
    }

    @Test
    fun `no review yet offers to start one`() {
        showReview(days = null, step = null)
        composeRule.onNode(hasText("Start a weekly review"), useUnmergedTree = true).assertIsDisplayed()
        tap(DashboardTags.REVIEW_BUTTON)
        assertEquals(listOf(CockpitDestination.Review), opened)
    }

    @Test
    fun `a review seven days ago is due again`() {
        showReview(days = 7, step = null)
        composeRule.onNode(hasText("Start a weekly review"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a recent review without an open one has no button`() {
        showReview(days = 6, step = null)
        composeRule.onNodeWithTag(DashboardTags.REVIEW_BUTTON, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the review button label follows the open step and the days since the last review`() {
        assertEquals(R.string.dashboard_review_continue, WidgetData.WeeklyReview(null, 1).reviewButton())
        assertEquals(R.string.dashboard_review_continue, WidgetData.WeeklyReview(30, 8).reviewButton())
        assertEquals(R.string.dashboard_review_start, WidgetData.WeeklyReview(null, null).reviewButton())
        assertEquals(R.string.dashboard_review_start, WidgetData.WeeklyReview(REVIEW_DUE_DAYS, null).reviewButton())
        assertEquals(R.string.dashboard_review_start, WidgetData.WeeklyReview(40, null).reviewButton())
        assertNull(WidgetData.WeeklyReview(REVIEW_DUE_DAYS - 1, null).reviewButton())
        assertNull(WidgetData.WeeklyReview(0, null).reviewButton())
    }

    @Test
    @Config(qualifiers = "de")
    fun `in German the review button and Customize are German`() {
        showReview(days = null, step = null)
        composeRule.onNode(hasText("Wochenrückblick starten"), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasText("Anpassen"), useUnmergedTree = true).assertExists()
    }

    // Customize

    private fun scrollTo(tag: String) = composeRule.onNodeWithTag(DashboardTags.LIST).performScrollToNode(hasTestTag(tag))

    private fun customizing(
        layout: List<WidgetSlot> = layoutOf(WidgetId.INBOX, WidgetId.TODAY),
        error: WidgetError? = null,
    ) = DashboardUiState(
        widgets = layout.shown(),
        layout = layout,
        layoutLoaded = true,
        customizing = true,
        layoutError = error,
    )

    @Test
    fun `Customize is disabled until the layout is loaded`() {
        show(DashboardUiState(widgets = listOf(WidgetId.INBOX)))
        scrollTo(DashboardTags.CUSTOMIZE)
        composeRule.onNodeWithTag(DashboardTags.CUSTOMIZE).assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), calls)
        composeRule.onNodeWithTag(DashboardTags.CUSTOMIZE_PANEL).assertDoesNotExist()
    }

    @Test
    fun `Customize opens the editor once the layout is loaded`() {
        show(DashboardUiState(widgets = listOf(WidgetId.INBOX), layout = layoutOf(WidgetId.INBOX), layoutLoaded = true))
        scrollTo(DashboardTags.CUSTOMIZE)
        composeRule.onNodeWithTag(DashboardTags.CUSTOMIZE).assertIsEnabled()
        composeRule.onNode(hasText("Customize"), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(DashboardTags.CUSTOMIZE_PANEL).assertDoesNotExist()
        composeRule.onNodeWithTag(DashboardTags.CUSTOMIZE).performClick()
        assertEquals(listOf("customize true"), calls)
    }

    @Test
    fun `while customizing the panel lists every widget and Done closes it`() {
        show(customizing())
        scrollTo(DashboardTags.CUSTOMIZE)
        composeRule.onNode(hasText("Done"), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(DashboardTags.CUSTOMIZE).performClick()
        assertEquals(listOf("customize false"), calls)
        WidgetId.entries.forEach { widget ->
            scrollTo(DashboardTags.slot(widget))
            composeRule.onNodeWithTag(DashboardTags.slot(widget)).assertIsDisplayed()
        }
    }

    @Test
    fun `a checkbox shows whether its widget is shown and toggles it`() {
        show(customizing())
        scrollTo(DashboardTags.slot(WidgetId.INBOX))
        composeRule.onNodeWithTag(DashboardTags.slotVisible(WidgetId.INBOX)).assertIsOn().performClick()
        scrollTo(DashboardTags.slot(WidgetId.PINNED_NOTES))
        composeRule.onNodeWithTag(DashboardTags.slotVisible(WidgetId.PINNED_NOTES)).assertIsOff().performClick()
        assertEquals(listOf("visible inbox false", "visible pinnedNotes true"), calls)
    }

    private fun customActions(widget: WidgetId) =
        composeRule
            .onNodeWithTag(DashboardTags.slot(widget))
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.CustomActions)
            .orEmpty()

    @Test
    fun `each row can be moved with accessibility actions, the ends only one way`() {
        val layout = layoutOf(WidgetId.INBOX, WidgetId.TODAY)
        show(customizing(layout))
        val first = layout.first().id
        val second = layout[1].id
        val last = layout.last().id
        scrollTo(DashboardTags.slot(first))
        assertEquals(listOf("Move down"), customActions(first).map { it.label })
        assertEquals(listOf("Move up", "Move down"), customActions(second).map { it.label })
        scrollTo(DashboardTags.slot(last))
        assertEquals(listOf("Move up"), customActions(last).map { it.label })

        composeRule.runOnIdle {
            customActions(second).single { it.label == "Move up" }.action()
            customActions(second).single { it.label == "Move down" }.action()
            customActions(last).single { it.label == "Move up" }.action()
        }
        assertEquals(listOf("move ${second.key} 0", "move ${second.key} 2", "move ${last.key} ${layout.lastIndex - 1}"), calls)
    }

    @Test
    fun `a long-press drag moves a row by the rows it was dragged over`() {
        val layout = DEFAULT_LAYOUT
        show(customizing(layout))
        scrollTo(DashboardTags.slot(WidgetId.INBOX))
        val row = composeRule.onNodeWithTag(DashboardTags.slot(WidgetId.TODAY)).fetchSemanticsNode().boundsInRoot
        val height = row.height
        composeRule.onNodeWithTag(DashboardTags.slot(WidgetId.TODAY)).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(10) { moveBy(Offset(0f, height * 2 / 10)) }
            up()
        }
        composeRule.waitForIdle()
        assertEquals(listOf("move today 2"), calls)
    }

    @Test
    fun `a long-press drag of less than half a row moves nothing`() {
        show(customizing(DEFAULT_LAYOUT))
        scrollTo(DashboardTags.slot(WidgetId.INBOX))
        val height =
            composeRule
                .onNodeWithTag(DashboardTags.slot(WidgetId.INBOX))
                .fetchSemanticsNode()
                .boundsInRoot.height
        composeRule.onNodeWithTag(DashboardTags.slot(WidgetId.INBOX)).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(4) { moveBy(Offset(0f, height / 10)) }
            up()
        }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `reset asks for the default layout`() {
        show(customizing())
        scrollTo(DashboardTags.RESET)
        composeRule.onNodeWithTag(DashboardTags.RESET).performClick()
        assertEquals(listOf("reset"), calls)
    }

    @Test
    fun `a layout change that could not be saved says so in the panel`() {
        show(customizing(error = WidgetError.OFFLINE))
        scrollTo(DashboardTags.CUSTOMIZE_PANEL)
        composeRule
            .onNode(hasText("The server is not reachable: the change was not saved."), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `a failed layout save without a connection problem has its own message`() {
        show(customizing(error = WidgetError.FAILED))
        scrollTo(DashboardTags.CUSTOMIZE_PANEL)
        composeRule.onNode(hasText("The change could not be saved."), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `all widgets hidden says so and offers to choose widgets`() {
        show(DashboardUiState(widgets = emptyList(), layout = layoutOf(), layoutLoaded = true))
        composeRule.onNode(hasText("All widgets are hidden."), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(DashboardTags.CHOOSE_WIDGETS).performClick()
        assertEquals(listOf("customize true"), calls)
        WidgetId.entries.forEach { composeRule.onNodeWithTag(DashboardTags.card(it)).assertDoesNotExist() }
    }

    @Test
    fun `while customizing with all widgets hidden there is no second choose button`() {
        show(customizing(layoutOf()))
        composeRule.onNodeWithTag(DashboardTags.ALL_HIDDEN).assertIsDisplayed()
        composeRule.onNodeWithTag(DashboardTags.CHOOSE_WIDGETS).assertDoesNotExist()
        composeRule.onNodeWithTag(DashboardTags.CUSTOMIZE_PANEL).assertExists()
    }
}
