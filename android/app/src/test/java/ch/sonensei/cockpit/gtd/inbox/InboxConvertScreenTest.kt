package ch.sonensei.cockpit.gtd.inbox

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.task.CalendarChoice
import ch.sonensei.cockpit.gtd.task.ConvertedItem
import ch.sonensei.cockpit.gtd.task.ConvertedOpener
import ch.sonensei.cockpit.gtd.task.ConvertedType
import ch.sonensei.cockpit.gtd.task.EventPlan
import ch.sonensei.cockpit.gtd.task.LocalConvertedOpener
import ch.sonensei.cockpit.gtd.task.TargetCalendar
import ch.sonensei.cockpit.gtd.task.ToCalendarTags
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Inbox processing's "To reference" / "To calendar" buttons, the calendar dialog and the snackbar telling where the item went. */
@RunWith(AndroidJUnit4::class)
class InboxConvertScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val first = GtdTask(id(1), "Book the concert", TaskList.INBOX, 0, 1)
    private val today = LocalDate.of(2026, 10, 20)
    private val work = TargetCalendar(id(90), "Work")
    private val home = TargetCalendar(id(91), "Home", isDefault = true)
    private val plan = EventPlan(home.id, today, LocalTime.of(11, 0))
    private val note = ConvertedItem(ConvertedType.NOTE, id(70), "Book the concert")
    private val processing =
        InboxUiState(
            tasks = listOf(first),
            refreshedAt = Instant.parse("2026-10-20T09:00:00Z"),
            today = today,
            processing = true,
        )

    private val calls = mutableListOf<String>()
    private val opened = mutableListOf<ConvertedItem>()
    private var state by mutableStateOf(processing)
    private var opener: ConvertedOpener? = null

    private fun show(initial: InboxUiState) {
        state = initial
        val callbacks =
            InboxCallbacks(
                choose = { id, kind -> calls += "choose $id $kind" },
                updatePrompt = { change -> state = state.copy(prompt = state.prompt?.let(change)) },
                confirmPrompt = { calls += "confirm $it" },
                cancelPrompt = { calls += "cancel" },
                retryCalendars = { calls += "retryCalendars" },
                dismissConverted = { calls += "dismissConverted ${it.id}" },
            )
        composeRule.setContent {
            CockpitTheme { CompositionLocalProvider(LocalConvertedOpener provides opener) { InboxContent(state, callbacks) } }
        }
    }

    private fun calendarPrompt(choice: CalendarChoice = CalendarChoice(listOf(home, work))) =
        processing.copy(prompt = InboxPrompt(InboxActionKind.CALENDAR, today.plusDays(1), plan = plan, calendars = choice))

    @Test
    fun `processing offers To calendar to defer and To reference to drop`() {
        show(processing)

        composeRule
            .onNodeWithTag(InboxTags.action(InboxActionKind.CALENDAR))
            .performScrollTo()
            .assertTextContains("To calendar")
            .performClick()
        composeRule
            .onNodeWithTag(InboxTags.action(InboxActionKind.REFERENCE))
            .performScrollTo()
            .assertTextContains("To reference")
            .performClick()

        assertEquals(listOf("choose ${first.id} CALENDAR", "choose ${first.id} REFERENCE"), calls)
    }

    @Test
    fun `offline the conversion buttons are disabled`() {
        show(processing.copy(offline = true))

        composeRule.onNodeWithTag(InboxTags.action(InboxActionKind.CALENDAR)).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(InboxTags.action(InboxActionKind.REFERENCE)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `the calendar prompt is the calendar dialog, which edits the prompt's plan and confirms for the item`() {
        show(calendarPrompt())

        composeRule.onNodeWithTag(ToCalendarTags.DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(InboxTags.PROMPT_CONFIRM).assertDoesNotExist()
        composeRule.onNodeWithTag(ToCalendarTags.calendar(home)).assertIsSelected()
        composeRule.onNodeWithTag(ToCalendarTags.calendar(work)).performClick()
        composeRule.onNodeWithTag(ToCalendarTags.duration(120)).performScrollTo().performClick()

        assertEquals(work.id, state.prompt?.plan?.calendarId)
        assertEquals(120, state.prompt?.plan?.durationMin)
        composeRule.onNodeWithTag(ToCalendarTags.CONFIRM).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(ToCalendarTags.CANCEL).performClick()
        assertEquals(listOf("confirm ${first.id}", "cancel"), calls)
    }

    @Test
    fun `calendars that failed to load are retried from the dialog`() {
        show(calendarPrompt(CalendarChoice(failed = true)))

        composeRule.onNodeWithTag(ToCalendarTags.CONFIRM).assertIsNotEnabled()
        composeRule.onNodeWithTag(ToCalendarTags.RETRY).performClick()

        assertEquals(listOf("retryCalendars"), calls)
    }

    @Test
    fun `offline the calendar dialog cannot confirm`() {
        show(calendarPrompt().copy(offline = true))

        composeRule.onNodeWithTag(ToCalendarTags.CONFIRM).assertIsNotEnabled()
    }

    @Test
    fun `in the shell the snackbar names the note and Open opens it`() {
        opener = ConvertedOpener { opened += it }
        show(processing.copy(converted = note))

        composeRule.onNodeWithText("Moved to reference as note “Book the concert”").assertIsDisplayed()
        composeRule.onNodeWithText("Open").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("dismissConverted ${note.id}"), calls)
        assertEquals(listOf(note), opened)
    }

    @Test
    fun `in the shell a snackbar that times out opens nothing`() {
        opener = ConvertedOpener { opened += it }
        show(processing.copy(converted = note))
        composeRule.onNodeWithText("Open").assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(SNACKBAR_TIMEOUT_MS)
        composeRule.waitForIdle()

        assertEquals(listOf("dismissConverted ${note.id}"), calls)
        assertEquals(emptyList<ConvertedItem>(), opened)
    }

    @Test
    fun `outside the shell the snackbar offers no Open, only dismissing`() {
        show(processing.copy(converted = note))

        composeRule.onNodeWithText("Moved to reference as note “Book the concert”").assertIsDisplayed()
        composeRule.onNodeWithText("Open").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Dismiss", substring = true, ignoreCase = true).performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("dismissConverted ${note.id}"), calls)
    }

    private companion object {
        /** Longer than a long snackbar (10 s). */
        const val SNACKBAR_TIMEOUT_MS = 12_000L
    }
}
