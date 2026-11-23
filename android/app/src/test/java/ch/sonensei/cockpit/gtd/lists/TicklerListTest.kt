package ch.sonensei.cockpit.gtd.lists

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.next.TaskListTags
import ch.sonensei.cockpit.gtd.task.LocalTaskOpener
import ch.sonensei.cockpit.gtd.task.TaskOpener
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/** The Tickler screen alone, driven by a state the test controls. */
@RunWith(AndroidJUnit4::class)
class TicklerListTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 11, 23)
    private val passport = GtdTask(id(1), "Renew passport", TaskList.INBOX, 0, 1, ticklerDate = LocalDate.of(2026, 12, 14))
    private val trip = GtdTask(id(2), "Plan summer trip", TaskList.SOMEDAY, 1, 1, ticklerDate = LocalDate.of(2027, 1, 14))
    private val loaded = TaskListUiState(tasks = listOf(passport, trip), loading = false, loaded = true, today = today)

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(loaded)
    private val callbacks = LiveListCallbacks(retry = { calls += "retry" }, complete = { calls += "complete ${it.title}" })

    private fun show(
        initial: TaskListUiState,
        opener: TaskOpener? = null,
    ) {
        state = initial
        composeRule.setContent {
            CockpitTheme {
                CompositionLocalProvider(LocalTaskOpener provides opener) {
                    TicklerContent(state, callbacks)
                }
            }
        }
    }

    @Test
    fun `each row shows the day it comes back, with the year when not this year`() {
        show(loaded)
        composeRule.onNodeWithTag(TaskListTags.row(passport.id)).assertIsDisplayed()
        composeRule.onNodeWithTag(TicklerTags.backOn(passport.id), useUnmergedTree = true).assertTextEquals("Back on Mon, Dec 14")
        composeRule.onNodeWithTag(TicklerTags.backOn(trip.id), useUnmergedTree = true).assertTextEquals("Back on Thu, Jan 14, 2027")
    }

    @Test
    fun `back-on date in English and German`() {
        assertEquals("Mon, Dec 14", backOnDate(LocalDate.of(2026, 12, 14), today, Locale.US))
        val german = backOnDate(LocalDate.of(2026, 12, 14), today, Locale.GERMAN)
        assertTrue(german, german.startsWith("Mo.") && german.contains("14.") && german.contains("Dez"))
        assertTrue(backOnDate(LocalDate.of(2027, 1, 14), today, Locale.GERMAN).contains("2027"))
    }

    @Test
    fun `a task without a tickler date has no back-on line`() {
        val plain = GtdTask(id(3), "Sort photos", TaskList.INBOX, 2, 1)
        show(loaded.copy(tasks = listOf(plain)))
        composeRule.onNodeWithTag(TaskListTags.row(plain.id)).assertIsDisplayed()
        composeRule.onNodeWithTag(TicklerTags.backOn(plain.id), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `completes a task with its checkbox, a busy one is disabled`() {
        show(loaded.copy(busy = setOf(trip.id)))
        composeRule.onNodeWithTag(TaskListTags.complete(trip.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag(TaskListTags.complete(passport.id)).assertIsEnabled().performClick()
        assertEquals(listOf("complete Renew passport"), calls)
    }

    @Test
    fun `tapping a task opens its detail`() {
        show(loaded, opener = { id, snapshot, _ -> calls += "open $id ${snapshot?.title}" })
        composeRule.onNodeWithTag(TaskListTags.row(trip.id)).performClick()
        assertEquals(listOf("open ${trip.id} Plan summer trip"), calls)
    }

    @Test
    fun `empty, then read-only after a failed reload`() {
        show(TaskListUiState(loading = false, loaded = true, today = today))
        composeRule.onNodeWithText("Nothing in the tickler").assertIsDisplayed()
        state = loaded.copy(error = LoadError.OFFLINE)
        composeRule.onNodeWithTag(LiveListTags.STALE).assertIsDisplayed()
        composeRule.onNodeWithTag(TaskListTags.complete(passport.id)).assertIsNotEnabled()
    }

    @Test
    fun `loading, then a first load error with retry`() {
        show(TaskListUiState(today = today))
        composeRule.onNodeWithTag(LiveListTags.LOADING).assertIsDisplayed()
        state = TaskListUiState(loading = false, error = LoadError.OFFLINE, today = today)
        composeRule.onNodeWithTag(LiveListTags.ERROR).assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    @Config(qualifiers = "de")
    fun `german return date and empty state`() {
        show(loaded)
        val text = composeRule.onNodeWithTag(TicklerTags.backOn(passport.id), useUnmergedTree = true)
        text.assertTextEquals("Zurück am " + backOnDate(passport.ticklerDate!!, today, Locale.GERMAN))
        state = TaskListUiState(loading = false, loaded = true, today = today)
        composeRule.onNodeWithText("Nichts auf Wiedervorlage").assertIsDisplayed()
    }
}
