package ch.sonensei.cockpit.gtd.lists

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.next.TaskListTags
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The Logbook screen alone, driven by a state the test controls. */
@RunWith(AndroidJUnit4::class)
class LogbookScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 10, 20)
    private val taxes =
        LoggedTask(
            GtdTask(id(1), "File taxes", TaskList.NEXT, 0, 2, projectId = id(10), contextIds = listOf(id(20)), notesMd = "Sent by post"),
            Instant.parse("2026-10-20T08:00:00Z"),
        )
    private val call = LoggedTask(GtdTask(id(2), "Call Ben", TaskList.NEXT, 1, 2), Instant.parse("2026-10-19T08:00:00Z"))
    private val old = LoggedTask(GtdTask(id(3), "Book the trip", TaskList.NEXT, 2, 2), Instant.parse("2026-10-01T08:00:00Z"))
    private val loaded =
        LogbookUiState(
            tasks = listOf(taxes, call, old),
            loading = false,
            loaded = true,
            projects = mapOf(id(10) to "Taxes 2026"),
            contexts = mapOf(id(20) to GtdContext(id(20), "@desk", 0, 1)),
            zone = ZoneOffset.UTC,
            today = today,
        )

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(loaded)

    private val callbacks =
        LogbookCallbacks(
            retry = { calls += "retry" },
            loadMore = { calls += "more" },
            open = { calls += "open ${it.task.title}" },
            close = { calls += "close" },
            reopen = { calls += "reopen ${it.task.title}" },
            dismissAction = { calls += "dismiss ${it.title}" },
        )

    private fun show(initial: LogbookUiState) {
        state = initial
        composeRule.setContent { CockpitTheme { LogbookContent(state, callbacks) } }
    }

    @Test
    fun `groups by completion day with project and contexts, and reopens`() {
        show(loaded)
        composeRule.onNodeWithTag(LogbookTags.day(today)).assertIsDisplayed()
        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("Yesterday").assertIsDisplayed()
        composeRule.onNodeWithTag(LogbookTags.day(LocalDate.of(2026, 10, 1))).assertIsDisplayed()
        composeRule.onNodeWithText("Taxes 2026 · @desk").assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.action(call.task.id)).performClick()
        composeRule.onNodeWithTag(TaskListTags.row(taxes.task.id)).performClick()
        assertEquals(listOf("reopen Call Ben", "open File taxes"), calls)
    }

    @Test
    fun `the opened task shows its details and reopens or closes`() {
        show(loaded.copy(opened = taxes))
        composeRule.onNodeWithTag(LogbookTags.DETAIL).assertIsDisplayed()
        composeRule.onNodeWithText("Project: Taxes 2026").assertIsDisplayed()
        composeRule.onNodeWithText("Contexts: @desk").assertIsDisplayed()
        composeRule.onNodeWithText("Sent by post").assertIsDisplayed()
        composeRule.onNodeWithTag(LogbookTags.DETAIL_REOPEN).performClick()
        composeRule.onNodeWithTag(LogbookTags.DETAIL_CLOSE).performClick()
        assertEquals(listOf("reopen File taxes", "close"), calls)
    }

    @Test
    fun `reopen is disabled while busy or read-only`() {
        show(loaded.copy(busy = setOf(taxes.task.id), error = LoadError.OFFLINE))
        composeRule.onNodeWithTag(LiveListTags.STALE).assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.action(call.task.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag(LiveListTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `loading, empty and error states`() {
        show(LogbookUiState())
        composeRule.onNodeWithTag(LiveListTags.LOADING).assertIsDisplayed()
        state = LogbookUiState(loading = false, loaded = true)
        composeRule.onNodeWithTag(LiveListTags.EMPTY).assertIsDisplayed()
        composeRule.onNodeWithText("Nothing completed yet. Tasks you finish show up here.").assertIsDisplayed()
        state = LogbookUiState(loading = false, error = LoadError.OFFLINE)
        composeRule.onNodeWithTag(LiveListTags.ERROR).assertIsDisplayed()
        composeRule.onNodeWithTag(LiveListTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `a short list with more pages asks for the next one, and a failed page offers a retry`() {
        show(loaded.copy(canLoadMore = true))
        composeRule.onNodeWithTag(LogbookTags.LOADING_MORE).assertIsDisplayed()
        composeRule.waitForIdle()
        assertEquals(listOf("more"), calls.distinct())
        calls.clear()
        state = state.copy(moreError = LoadError.FAILED)
        composeRule.onNodeWithTag(LogbookTags.MORE_FAILED).assertIsDisplayed()
        composeRule.onNodeWithTag(LogbookTags.MORE_RETRY).performClick()
        assertEquals(listOf("more"), calls)
    }

    @Test
    @Config(qualifiers = "de")
    fun `german strings`() {
        show(loaded.copy(opened = taxes))
        composeRule.onNodeWithTag(LogbookTags.DETAIL_REOPEN).assertTextEquals("Wieder öffnen")
        composeRule.onNodeWithText("Projekt: Taxes 2026").assertIsDisplayed()
    }
}
