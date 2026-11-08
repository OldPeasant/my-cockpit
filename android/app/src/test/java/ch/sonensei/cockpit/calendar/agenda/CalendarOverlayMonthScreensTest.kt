package ch.sonensei.cockpit.calendar.agenda

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.task.LocalTaskOpener
import ch.sonensei.cockpit.gtd.task.TaskOpener
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** The overlay toggle, the overlay's tasks in the agenda, day and week views, the month view, and their strings. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class CalendarOverlayMonthScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.parse("2026-10-21")
    private val monday = LocalDate.parse("2026-10-19")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", 0xFF3366FFL, true, false, 1)

    private val report = OverlayTask(id(10), "Send the quarterly report", dueDate = today, dueTime = "14:30")
    private val dentist = OverlayTask(id(11), "Book the dentist", scheduledDate = today.plusDays(1))
    private val renewal = OverlayTask(id(12), "Renew the fictitious licence", ticklerDate = today)

    private fun allDay(
        n: Int,
        title: String,
        date: LocalDate,
    ) = Occurrence(id(n), work.id, title, null, true, null, null, date, date.plusDays(1))

    private val calls = mutableListOf<String>()

    private fun show(state: CalendarUiState) =
        composeRule.setContent {
            CockpitTheme {
                CalendarContent(
                    state,
                    CalendarCallbacks(
                        select = { calls += "select $it" },
                        move = { calls += "move $it" },
                        openDay = { calls += "day $it" },
                        loadMore = { calls += "more" },
                        open = { calls += "open ${it.title}" },
                        setOverlay = { calls += "overlay $it" },
                        openTask = { calls += "task ${it.title}" },
                    ),
                )
            }
        }

    private fun state(
        view: CalendarView = CalendarView.AGENDA,
        occurrences: List<Occurrence> = emptyList(),
        overlay: Boolean = true,
        tasks: List<OverlayTask> = listOf(report, dentist, renewal),
        tasksFailed: Boolean = false,
        message: CalendarMessage? = null,
    ): CalendarUiState {
        val (from, to) =
            when (view) {
                CalendarView.AGENDA -> today to today.plusDays(30)
                CalendarView.DAY -> today to today.plusDays(1)
                CalendarView.WEEK -> monday to monday.plusDays(7)
                CalendarView.MONTH -> monthGrid(today, DayOfWeek.MONDAY).let { it.first() to it.last().plusDays(1) }
            }
        return CalendarUiState(
            view = view,
            today = today,
            anchor = today,
            firstDayOfWeek = DayOfWeek.MONDAY,
            zone = utc,
            calendars = listOf(work),
            from = from,
            to = to,
            occurrences = occurrences,
            loaded = true,
            message = message,
            overlay = overlay,
            tasks = tasks,
            tasksFailed = tasksFailed,
        )
    }

    private fun tag(
        task: OverlayTask,
        kind: TaskMarkKind,
        date: LocalDate,
    ) = CalendarTags.task("task-$kind-${task.id}", date)

    @Test
    fun `the toggle shows the overlay state and asks to switch it`() {
        show(state(view = CalendarView.DAY, overlay = false))

        // The toggles are in the overflow menu, so the four view segments keep their width on a phone.
        composeRule.onNodeWithTag(CalendarTags.OVERLAY).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithTag(CalendarTags.OVERLAY).assertIsOff().assertTextContains("Show tasks")
        composeRule.onNodeWithTag(CalendarTags.OVERLAY).performClick()

        assertEquals(listOf("overlay true"), calls)
        // Choosing an item closes the menu.
        composeRule.onNodeWithTag(CalendarTags.OVERLAY).assertDoesNotExist()
    }

    @Test
    fun `a switched on toggle asks to switch the overlay off`() {
        show(state(view = CalendarView.DAY, overlay = true))

        composeRule.onNodeWithTag(CalendarTags.MENU).performClick()
        composeRule.onNodeWithTag(CalendarTags.OVERLAY).assertIsOn()
        composeRule.onNodeWithTag(CalendarTags.OVERLAY).performClick()

        assertEquals(listOf("overlay false"), calls)
    }

    @Test
    fun `the agenda lists the tasks under their day, also a day without events, and a tap opens the task`() {
        show(state(occurrences = listOf(allDay(1, "Holiday", today))))

        composeRule.onNodeWithTag(tag(report, TaskMarkKind.DUE, today)).assertTextContains("Send the quarterly report", substring = true)
        composeRule.onNodeWithTag(tag(report, TaskMarkKind.DUE, today)).assertTextContains("Due", substring = true)
        composeRule.onNodeWithTag(tag(renewal, TaskMarkKind.TICKLER, today)).assertTextContains("Back from tickler", substring = true)
        // Tomorrow has only a task: it gets its own day header.
        composeRule.onNodeWithTag(CalendarTags.day(today.plusDays(1))).assertExists()
        composeRule
            .onNodeWithTag(CalendarTags.AGENDA)
            .performScrollToNode(hasTestTag(tag(dentist, TaskMarkKind.SCHEDULED, today.plusDays(1))))
        composeRule.onNodeWithTag(tag(dentist, TaskMarkKind.SCHEDULED, today.plusDays(1))).assertTextContains("Scheduled", substring = true)

        composeRule.onNodeWithTag(tag(dentist, TaskMarkKind.SCHEDULED, today.plusDays(1))).performClick()
        assertTrue(calls.contains("task Book the dentist"))
    }

    @Test
    fun `with the overlay off no task is shown, even if tasks are in the state`() {
        show(state(overlay = false))

        composeRule.onNodeWithTag(tag(report, TaskMarkKind.DUE, today)).assertDoesNotExist()
        composeRule.onNodeWithText("Send the quarterly report").assertDoesNotExist()
    }

    @Test
    fun `the day view shows the tasks in the all-day strip with kind and time, not as an empty day`() {
        show(state(view = CalendarView.DAY))

        composeRule.onNodeWithTag(tag(report, TaskMarkKind.DUE, today)).assertTextContains("Send the quarterly report", substring = true)
        composeRule.onNodeWithTag(tag(report, TaskMarkKind.DUE, today)).assertTextContains("Due 2:30", substring = true)
        composeRule.onNodeWithTag(tag(renewal, TaskMarkKind.TICKLER, today)).assertExists()
        // Tomorrow's task is not on this day.
        composeRule.onNodeWithTag(tag(dentist, TaskMarkKind.SCHEDULED, today.plusDays(1))).assertDoesNotExist()
        composeRule.onNodeWithText("Nothing planned.").assertDoesNotExist()

        composeRule.onNodeWithTag(tag(report, TaskMarkKind.DUE, today)).performClick()
        assertEquals(listOf("task Send the quarterly report"), calls)
    }

    @Test
    fun `the week view shows each day's tasks in the all-day strip and a tap opens the task`() {
        show(state(view = CalendarView.WEEK))

        composeRule.onNodeWithTag(tag(report, TaskMarkKind.DUE, today)).assertExists()
        composeRule
            .onNodeWithTag(
                tag(dentist, TaskMarkKind.SCHEDULED, today.plusDays(1)),
            ).assertTextContains("Book the dentist", substring = true)

        composeRule.onNodeWithTag(tag(dentist, TaskMarkKind.SCHEDULED, today.plusDays(1))).performClick()
        assertEquals(listOf("task Book the dentist"), calls)
    }

    @Test
    fun `tasks that could not be loaded are reported, but not on top of an events message`() {
        show(state(view = CalendarView.DAY, tasksFailed = true))
        composeRule.onNodeWithTag(CalendarTags.TASKS_FAILED).assertTextContains("The tasks could not be loaded.")
    }

    @Test
    fun `the events message replaces the tasks line`() {
        show(state(view = CalendarView.DAY, tasksFailed = true, message = CalendarMessage.OFFLINE))
        composeRule.onNodeWithTag(CalendarTags.TASKS_FAILED).assertDoesNotExist()
    }

    @Test
    // A phone with room for three titles per cell: lower cells list fewer (see CalendarMonthLayoutTest).
    @Config(qualifiers = "en-rUS-w412dp-h915dp")
    fun `the month view lists up to three titles per day, events first, then +n, and a tap opens the day`() {
        val events = listOf(allDay(1, "Holiday", today), allDay(2, "Offsite", today))
        show(state(view = CalendarView.MONTH, occurrences = events))

        composeRule.onNodeWithTag(CalendarTags.MONTH).assertExists()
        composeRule.onNodeWithText("October 2026").assertExists()
        // Today: 2 events and 2 tasks; the 4th (the later task) is counted as +1.
        val day = composeRule.onNodeWithTag(CalendarTags.monthDay(today))
        day.assertTextContains("Holiday", substring = true)
        day.assertTextContains("Offsite", substring = true)
        day.assertTextContains("Send the quarterly report", substring = true)
        composeRule.onNodeWithTag(CalendarTags.monthMore(today), useUnmergedTree = true).assertTextContains("+1")
        composeRule.onNodeWithTag(CalendarTags.monthDay(today.plusDays(1))).assertTextContains("Book the dentist", substring = true)
        composeRule.onNodeWithTag(CalendarTags.monthMore(today.plusDays(1)), useUnmergedTree = true).assertDoesNotExist()
        // The grid's first and last days are outside October.
        composeRule.onNodeWithTag(CalendarTags.monthDay(LocalDate.parse("2026-09-28"))).assertExists()
        composeRule.onNodeWithTag(CalendarTags.monthDay(LocalDate.parse("2026-11-01"))).assertExists()
        composeRule.onNodeWithContentDescription("Next month").assertExists()

        composeRule.onNodeWithTag(CalendarTags.monthDay(today.plusDays(1))).performClick()
        composeRule.onNodeWithTag(CalendarTags.NEXT).performClick()
        assertEquals(listOf("day ${today.plusDays(1)}", "move 1"), calls)
    }

    @Test
    fun `the month view describes each day with its number of entries`() {
        show(state(view = CalendarView.MONTH, overlay = false))

        composeRule.onNodeWithContentDescription("Wednesday, October 21, 2026, 0 entries").assertExists()
        composeRule.onNodeWithTag(CalendarTags.view(CalendarView.MONTH)).performClick()
        assertEquals(listOf("select MONTH"), calls)
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp")
    fun `on a 360dp phone each of the four view segments keeps the Material minimum width`() {
        show(state(view = CalendarView.MONTH))

        for (view in CalendarView.entries) {
            composeRule.onNodeWithTag(CalendarTags.view(view)).assertWidthIsAtLeast(58.dp)
        }
        composeRule.onNodeWithTag(CalendarTags.TODAY).assertIsDisplayed()
        composeRule.onNodeWithTag(CalendarTags.MENU).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "de-rDE-w360dp-h640dp")
    fun `on a 360dp phone the German view segments keep the Material minimum width`() {
        show(state(view = CalendarView.MONTH))

        for (view in CalendarView.entries) {
            composeRule.onNodeWithTag(CalendarTags.view(view)).assertWidthIsAtLeast(58.dp)
        }
        composeRule.onNodeWithTag(CalendarTags.MENU).assertIsDisplayed()
    }

    @Test
    fun `a task released to Next today shows as scheduled on today in the day and month views`() {
        // The server cleared its scheduled date when it moved it to Next today.
        val plumber = OverlayTask(id(13), "Call the fictitious plumber", releasedOn = today)
        show(state(view = CalendarView.DAY, tasks = listOf(plumber)))

        composeRule
            .onNodeWithTag(tag(plumber, TaskMarkKind.SCHEDULED, today))
            .assertTextContains("Call the fictitious plumber", substring = true)
            .assertTextContains("Scheduled", substring = true)
        composeRule.onNodeWithTag(tag(plumber, TaskMarkKind.SCHEDULED, today)).performClick()
        assertEquals(listOf("task Call the fictitious plumber"), calls)
    }

    @Test
    fun `a released-today task is listed in today's month cell`() {
        val plumber = OverlayTask(id(13), "Call the fictitious plumber", releasedOn = today)
        show(state(view = CalendarView.MONTH, tasks = listOf(plumber)))

        composeRule.onNodeWithTag(CalendarTags.monthDay(today)).assertTextContains("Call the fictitious plumber", substring = true)
        composeRule.onNodeWithContentDescription("Wednesday, October 21, 2026, 1 entry").assertExists()
    }

    /**
     * The view stays open across midnight: refresh() moves today, while the range and the reloaded tasks stay the same.
     * The task released yesterday must no longer be marked scheduled on yesterday.
     */
    private fun assertReleasedMarkFollowsToday(view: CalendarView) {
        val plumber = OverlayTask(id(13), "Call the fictitious plumber", releasedOn = today)
        var current by mutableStateOf(state(view = view, tasks = listOf(plumber)))
        composeRule.setContent { CockpitTheme { CalendarContent(current, CalendarCallbacks()) } }
        composeRule.onNodeWithText("Call the fictitious plumber", useUnmergedTree = true).assertExists()

        current = current.copy(today = today.plusDays(1))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Call the fictitious plumber", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `when today moves on with the same tasks, the agenda drops yesterday's released-today mark`() =
        assertReleasedMarkFollowsToday(CalendarView.AGENDA)

    @Test
    fun `when today moves on with the same tasks, the day view drops yesterday's released-today mark`() =
        assertReleasedMarkFollowsToday(CalendarView.DAY)

    @Test
    fun `when today moves on with the same tasks, the week view drops yesterday's released-today mark`() =
        assertReleasedMarkFollowsToday(CalendarView.WEEK)

    @Test
    fun `when today moves on with the same tasks, the month view drops yesterday's released-today mark`() =
        assertReleasedMarkFollowsToday(CalendarView.MONTH)

    @Test
    fun `tapping a task title in the month view opens its day, not the task`() {
        show(state(view = CalendarView.MONTH, tasks = listOf(dentist)))

        composeRule.onNodeWithText("Book the dentist", useUnmergedTree = true).performClick()

        assertEquals(listOf("day ${today.plusDays(1)}"), calls)
    }

    @Test
    fun `the month view lists no task titles while the overlay is off`() {
        show(state(view = CalendarView.MONTH, overlay = false, occurrences = listOf(allDay(1, "Holiday", today))))

        composeRule.onNodeWithTag(CalendarTags.monthDay(today)).assertTextContains("Holiday", substring = true)
        composeRule.onNodeWithText("Send the quarterly report", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Book the dentist", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the route opens a tapped task with its snapshot through the task opener`() {
        val snapshot = GtdTask(report.id, report.title, TaskList.NEXT, 1, 2, dueDate = today)
        val source = TaskOverlaySource { _, _, _ -> CalendarResult.Ok(listOf(report.copy(snapshot = snapshot))) }
        val repo = mockk<CalendarRepository>()
        coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(work))
        coEvery { repo.occurrences(any(), any(), any()) } returns CalendarResult.Ok(emptyList())
        val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), utc)
        val viewModel =
            CalendarViewModel(repo, clock, Locale.US, taskSource = source, overlaySetting = TaskOverlaySetting.InMemory(enabled = true))
        val opened = mutableListOf<Pair<UUID, GtdTask?>>()
        val opener = TaskOpener { id, task, _ -> opened += id to task }
        val owner =
            object : LifecycleOwner {
                val registry = LifecycleRegistry(this)
                override val lifecycle: Lifecycle get() = registry
            }
        owner.registry.currentState = Lifecycle.State.RESUMED

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalTaskOpener provides opener) {
                CockpitTheme { CalendarRoute(viewModel = viewModel) }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(tag(report, TaskMarkKind.DUE, today)).performClick()

        assertEquals(listOf(report.id to snapshot), opened)
    }

    @Test
    fun `the route's retry button loads the tasks again and shows them`() {
        var answers = 0
        val source =
            TaskOverlaySource { _, _, _ ->
                if (answers++ == 0) CalendarResult.Offline else CalendarResult.Ok(listOf(report))
            }
        val repo = mockk<CalendarRepository>()
        coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(work))
        coEvery { repo.occurrences(any(), any(), any()) } returns CalendarResult.Ok(emptyList())
        val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), utc)
        val viewModel =
            CalendarViewModel(repo, clock, Locale.US, taskSource = source, overlaySetting = TaskOverlaySetting.InMemory(enabled = true))
        val owner =
            object : LifecycleOwner {
                val registry = LifecycleRegistry(this)
                override val lifecycle: Lifecycle get() = registry
            }
        owner.registry.currentState = Lifecycle.State.RESUMED

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                CockpitTheme { CalendarRoute(viewModel = viewModel) }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CalendarTags.TASKS_FAILED).assertIsDisplayed()
        composeRule.onNodeWithTag(tag(report, TaskMarkKind.DUE, today)).assertDoesNotExist()

        composeRule.onNodeWithTag(CalendarTags.TASKS_RETRY).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CalendarTags.TASKS_FAILED).assertDoesNotExist()
        composeRule.onNodeWithTag(tag(report, TaskMarkKind.DUE, today)).assertExists()
        assertEquals(2, answers)
    }

    @Test
    fun `an agenda with only tasks is not reported as empty`() {
        show(state(tasks = listOf(dentist)))

        composeRule.onNodeWithTag(CalendarTags.day(today.plusDays(1))).assertExists()
        composeRule.onNodeWithTag(tag(dentist, TaskMarkKind.SCHEDULED, today.plusDays(1))).assertExists()
        composeRule.onNodeWithTag(CalendarTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun `an agenda without events and with the overlay off says nothing is planned`() {
        show(state(overlay = false, tasks = listOf(dentist)))

        composeRule.onNodeWithTag(CalendarTags.EMPTY).assertExists()
        composeRule.onNodeWithTag(CalendarTags.day(today.plusDays(1))).assertDoesNotExist()
    }

    @Test
    fun `the week view shows compact task chips without the kind and time label`() {
        show(state(view = CalendarView.WEEK))

        val chip = composeRule.onNodeWithTag(tag(report, TaskMarkKind.DUE, today))
        chip.assertTextContains("Send the quarterly report", substring = true)
        // The day view's "Due 2:30 PM" label is left out for lack of room.
        composeRule.onNodeWithText("Due", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de-rDE")
    fun `the month view and the overlay are shown in German`() {
        show(state(view = CalendarView.MONTH))

        composeRule.onNodeWithText("Oktober 2026").assertExists()
        composeRule.onNodeWithContentDescription("Vorheriger Monat").assertExists()
        composeRule.onNodeWithContentDescription("Nächster Monat").assertExists()
        composeRule.onNodeWithContentDescription("Weitere Optionen").performClick()
        composeRule.onNodeWithTag(CalendarTags.OVERLAY).assertIsOn().assertTextContains("Aufgaben anzeigen")
        composeRule.onNodeWithTag(CalendarTags.CALENDARS).assertIsOff().assertTextContains("Kalender")
        composeRule.onNodeWithTag(CalendarTags.view(CalendarView.MONTH)).assertTextContains("Monat")
        // Today has the due task and the tickler.
        composeRule.onNodeWithContentDescription("Mittwoch, 21. Oktober 2026, 2 Einträge").assertExists()
    }

    @Test
    @Config(qualifiers = "de-rDE")
    fun `the day view labels a task's kind and time in German`() {
        show(state(view = CalendarView.DAY, tasksFailed = true))

        composeRule.onNodeWithTag(tag(report, TaskMarkKind.DUE, today)).assertTextContains("Fällig 14:30", substring = true)
        composeRule
            .onNodeWithTag(tag(renewal, TaskMarkKind.TICKLER, today))
            .assertTextContains("Zurück aus Wiedervorlage", substring = true)
        composeRule.onNodeWithTag(CalendarTags.TASKS_FAILED).assertTextContains("Die Aufgaben konnten nicht geladen werden.")
    }

    @Test
    fun `the new strings exist in English and German`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val english = localized(context, Locale.ENGLISH)
        val german = localized(context, Locale.GERMAN)
        val names =
            listOf(
                R.string.calendar_view_month,
                R.string.calendar_previous_month,
                R.string.calendar_next_month,
                R.string.calendar_task_overlay,
                R.string.calendar_task_due,
                R.string.calendar_task_scheduled,
                R.string.calendar_task_tickler,
                R.string.calendar_tasks_failed,
                R.string.calendar_tasks_retry,
                R.string.calendar_more_options,
            )
        for (name in names) {
            val en = english.getString(name)
            val de = german.getString(name)
            assertTrue(en.isNotBlank() && de.isNotBlank())
            assertNotEquals("${english.resources.getResourceEntryName(name)} is not translated", en, de)
        }
        assertEquals("Month", english.getString(R.string.calendar_view_month))
        assertEquals("Monat", german.getString(R.string.calendar_view_month))
        assertEquals("+3", german.getString(R.string.calendar_month_more, 3))
        assertEquals("1 entry", english.resources.getQuantityString(R.plurals.calendar_month_items, 1, 1))
        assertEquals("2 entries", english.resources.getQuantityString(R.plurals.calendar_month_items, 2, 2))
        assertEquals("1 Eintrag", german.resources.getQuantityString(R.plurals.calendar_month_items, 1, 1))
        assertEquals("5 Einträge", german.resources.getQuantityString(R.plurals.calendar_month_items, 5, 5))
    }

    private fun localized(
        context: Context,
        locale: Locale,
    ): Context = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
}
