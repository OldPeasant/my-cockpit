package ch.sonensei.cockpit.newitems

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.newitems.NewTestData.mail
import ch.sonensei.cockpit.newitems.NewTestData.task
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The screen alone, driven by a state the test controls. */
@RunWith(AndroidJUnit4::class)
class NewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val at = Instant.parse("2026-10-20T12:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)
    private val plumber = task("Call the plumber", "2026-10-20T08:00:00Z", 1)
    private val invoice = mail("Invoice", "2026-10-20T09:00:00Z", 3)
    private val taskRow = NewRow.Task(plumber)
    private val mailRow = NewRow.Mail(invoice)
    private val loaded = NewUiState(rows = listOf(mailRow, taskRow), inboxRefreshedAt = at, mailLoadedAt = at)

    private val calls = mutableListOf<String>()
    private var state by mutableStateOf(loaded)

    private fun show(initial: NewUiState) {
        state = initial
        val callbacks =
            NewCallbacks(
                setFilter = { calls += "filter $it" },
                open = { calls += "open ${it.encode()}" },
                toTask = { calls += "toTask $it" },
                retry = { calls += "retry" },
            )
        composeRule.setContent { CockpitTheme { NewContent(state, callbacks, clock = clock) } }
    }

    @Test
    fun rowsShowTheirSource() {
        show(loaded)

        composeRule.onNodeWithTag(NewTags.source(mailRow), useUnmergedTree = true).assertTextEquals("Mail")
        composeRule.onNodeWithTag(NewTags.source(taskRow), useUnmergedTree = true).assertTextEquals("GTD Inbox")
        composeRule.onNodeWithTag(NewTags.filter(NewFilter.ALL)).assertIsSelected()
    }

    @Test
    fun tappingRowsOpensTheItemAndTheFilterIsChosen() {
        show(loaded)

        composeRule.onNodeWithText("Call the plumber").performClick()
        composeRule.onNodeWithText("Invoice").performClick()
        composeRule.onNodeWithTag(NewTags.toTask(invoice.id)).performClick()
        composeRule.onNodeWithTag(NewTags.filter(NewFilter.MAIL)).performClick()

        assertEquals(
            listOf("open task:${plumber.id}", "open mail:${invoice.id}", "toTask ${invoice.id}", "filter MAIL"),
            calls,
        )
    }

    @Test
    fun offlineShowsTheBannerAndMailIsReadOnly() {
        show(loaded.copy(inboxOffline = true, mailOffline = true))

        composeRule.onNodeWithTag(NewTags.OFFLINE).assertExists()
        composeRule.onNodeWithTag(NewTags.toTask(invoice.id)).assertIsNotEnabled()
        composeRule.onNodeWithText("Invoice").assertExists()
        composeRule.onNodeWithTag(NewTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun onlineMailCanBecomeATask() {
        show(loaded)

        composeRule.onNodeWithTag(NewTags.toTask(invoice.id)).assertIsEnabled()
        composeRule.onNodeWithTag(NewTags.OFFLINE).assertDoesNotExist()
    }

    @Test
    fun nothingNewShowsTheEmptyText() {
        show(NewUiState(inboxRefreshedAt = at))

        composeRule.onNodeWithTag(NewTags.EMPTY).assertExists()
    }
}
