package ch.sonensei.cockpit.newitems

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import java.util.UUID

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
                loadMore = { calls += "loadMore" },
                retry = { calls += "retry" },
                dismissNotice = { calls += "dismiss" },
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

    @Test
    fun theFirstLoadShowsTheLoadingText() {
        show(NewUiState(loading = true))

        composeRule.onNodeWithTag(NewTags.LOADING).assertExists()
        composeRule.onNodeWithTag(NewTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun theChosenFilterIsSelected() {
        show(loaded.copy(filter = NewFilter.MAIL, rows = listOf(mailRow)))

        composeRule.onNodeWithTag(NewTags.filter(NewFilter.MAIL)).assertIsSelected()
        composeRule.onNodeWithTag(NewTags.filter(NewFilter.ALL)).assertIsNotSelected()
        composeRule.onNodeWithText("Call the plumber").assertDoesNotExist()
    }

    @Test
    fun aMailBeingConvertedCannotBeConvertedAgain() {
        show(loaded.copy(converting = setOf(invoice.id)))

        composeRule.onNodeWithTag(NewTags.toTask(invoice.id)).assertIsNotEnabled()
    }

    @Test
    fun anUnreadMailWithoutSubjectIsMarked() {
        val blank = mail("", "2026-10-20T10:00:00Z", 8)
        show(loaded.copy(rows = listOf(NewRow.Mail(blank), taskRow)))

        composeRule.onNodeWithText("(no subject)").assertExists()
        composeRule.onNodeWithContentDescription("Unread", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aReadMailHasNoUnreadMarker() {
        show(loaded.copy(rows = listOf(NewRow.Mail(invoice.copy(seen = true)))))

        composeRule.onNodeWithContentDescription("Unread", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun loadMoreShowsWhileOlderMailIsLeft() {
        show(loaded.copy(moreMail = true))

        composeRule
            .onNodeWithTag(NewTags.LOAD_MORE)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf("loadMore"), calls)
    }

    @Test
    fun loadMoreIsDisabledWhileAPageLoads() {
        show(loaded.copy(moreMail = true, loadingMore = true))

        composeRule.onNodeWithTag(NewTags.LOAD_MORE).assertIsNotEnabled()
    }

    @Test
    fun loadMoreIsHiddenForTheInboxFilterAndAfterTheLastPage() {
        show(loaded.copy(moreMail = true, filter = NewFilter.INBOX, rows = listOf(taskRow)))
        composeRule.onNodeWithTag(NewTags.LOAD_MORE).assertDoesNotExist()

        state = loaded.copy(moreMail = false)
        composeRule.onNodeWithTag(NewTags.LOAD_MORE).assertDoesNotExist()
    }

    @Test
    fun failedLoadsShowTheErrorBannerWithRetry() {
        show(loaded.copy(inboxFailed = true, mailFailed = true))

        composeRule
            .onNodeWithText("The Inbox could not be loaded. The messages could not be loaded.")
            .assertExists()
        composeRule.onNodeWithTag(NewTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
        composeRule.onNodeWithTag(NewTags.OFFLINE).assertDoesNotExist()
    }

    @Test
    fun aFailedMailLoadAloneNamesOnlyTheMail() {
        show(loaded.copy(mailFailed = true))

        composeRule.onNodeWithText("The messages could not be loaded.").assertExists()
        composeRule.onNodeWithText("The Inbox could not be loaded.", substring = true).assertDoesNotExist()
    }

    @Test
    fun retryIsDisabledWhileLoading() {
        show(loaded.copy(mailFailed = true, loading = true))

        composeRule.onNodeWithTag(NewTags.RETRY).assertIsNotEnabled()
    }

    @Test
    fun offlineReplacesTheErrorBanner() {
        show(loaded.copy(mailOffline = true, mailFailed = true))

        composeRule.onNodeWithTag(NewTags.OFFLINE).assertExists()
        composeRule.onNodeWithTag(NewTags.ERROR).assertDoesNotExist()
    }

    @Test
    fun offlineWithoutLoadedMailSaysSo() {
        show(NewUiState(rows = listOf(taskRow), inboxRefreshedAt = at, mailOffline = true))

        composeRule.onNodeWithText("Mail could not be loaded yet.", substring = true).assertExists()
        composeRule.onNodeWithText("GTD Inbox as loaded at", substring = true).assertDoesNotExist()
    }

    @Test
    fun offlineNamesWhenEachPartWasLoaded() {
        show(loaded.copy(inboxOffline = true, mailOffline = true))

        composeRule.onNodeWithText("GTD Inbox as loaded at", substring = true).assertExists()
        composeRule.onNodeWithText("Mail as loaded at", substring = true).assertExists()
    }

    @Test
    fun anOfflineInboxAloneKeepsMailConvertible() {
        show(loaded.copy(inboxOffline = true))

        composeRule.onNodeWithTag(NewTags.OFFLINE).assertExists()
        composeRule.onNodeWithTag(NewTags.toTask(invoice.id)).assertIsEnabled()
        composeRule.onNodeWithText("Mail as loaded at", substring = true).assertDoesNotExist()
    }

    @Test
    fun aCreatedTaskIsAnnouncedAndCanBeDismissed() {
        show(loaded.copy(notice = NewNotice.TaskCreated("Invoice")))

        composeRule.onNodeWithText("Added “Invoice” to the Inbox").assertExists()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), calls)
    }

    @Test
    fun aConversionThatDidNotWorkSaysWhy() {
        show(loaded.copy(notice = NewNotice.TaskOffline))
        composeRule.onNodeWithText("The server could not be reached. No task was created.").assertExists()

        state = loaded.copy(notice = NewNotice.TaskFailed)
        composeRule.onNodeWithText("The task could not be created.").assertExists()

        state = loaded
        composeRule.onNodeWithTag(NewTags.NOTICE).assertDoesNotExist()
    }

    @Test
    fun theTaskViewShowsTheTaskReadOnlyAndBackCloses() {
        var closed = 0
        val withNotes = task("Call the plumber", "2026-10-20T08:00:00Z", 1, notes = "Ask about the boiler")
        composeRule.setContent { CockpitTheme { NewTaskView(withNotes, onClose = { closed++ }, clock = clock) } }

        composeRule.onNodeWithTag(NewTags.TASK_VIEW).assertExists()
        composeRule.onNodeWithText("Call the plumber").assertExists()
        composeRule.onNodeWithText("Ask about the boiler").assertExists()
        composeRule.onNodeWithText("Captured", substring = true).assertExists()
        composeRule.onNodeWithText("Process it in the Inbox to decide what it is.").assertExists()
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun aTaskWithoutTimestampShowsNoCaptureTime() {
        val legacy = plumber.copy(id = UUID.fromString("00000000-0000-4000-8000-000000000009"), notesMd = " ")
        composeRule.setContent { CockpitTheme { NewTaskView(legacy, onClose = {}, clock = clock) } }

        composeRule.onNodeWithText("Call the plumber").assertExists()
        composeRule.onNodeWithText("Captured", substring = true).assertDoesNotExist()
    }
}
