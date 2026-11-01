package ch.sonensei.cockpit.trash

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The Trash screen alone, driven by states the tests control. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class TrashScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val now = Instant.parse("2026-10-29T12:00:00Z")

    private val task =
        TrashItem(id(1), "TASK", "Call the plumber", "Home", Instant.parse("2026-10-01T09:00:00Z"), Instant.parse("2026-10-31T09:00:00Z"))
    private val note =
        TrashItem(id(2), "NOTE", "Recipe", null, Instant.parse("2026-10-20T09:00:00Z"), Instant.parse("2026-11-19T09:00:00Z"))
    private val calls = mutableListOf<String>()

    private val callbacks =
        TrashCallbacks(
            refresh = { calls += "refresh" },
            retry = { calls += "retry" },
            restore = { calls += "restore ${it.title}" },
            purge = { calls += "purge ${it.title}" },
            confirmPurge = { calls += "confirm" },
            cancelPurge = { calls += "cancel" },
            dismiss = { calls += "dismiss" },
        )

    private fun show(state: TrashUiState) =
        composeRule.setContent {
            CockpitTheme { TrashContent(state, callbacks, now = now, zone = ZoneOffset.UTC) }
        }

    @Test
    fun `rows show type, title, deletion date and days until purge`() {
        show(TrashUiState(items = listOf(task, note), loaded = true))

        val row = composeRule.onNodeWithTag(TrashTags.item(task))
        row.assertTextContains("Task · Home", substring = true)
        row.assertTextContains("Call the plumber", substring = true)
        row.assertTextContains("Deleted Oct 1, 2026", substring = true)
        composeRule.onNodeWithTag(TrashTags.purgeIn(task), useUnmergedTree = true).assertTextContains("Deleted permanently in 2 days")
        composeRule.onNodeWithTag(TrashTags.purgeIn(note), useUnmergedTree = true).assertTextContains("Deleted permanently in 21 days")
        composeRule.onNodeWithContentDescription("Task", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Note", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `restore and delete forever report the item`() {
        show(TrashUiState(items = listOf(task), loaded = true))

        composeRule.onNodeWithContentDescription("Restore Call the plumber").performClick()
        composeRule.onNodeWithContentDescription("Delete permanently: Call the plumber").performClick()
        assertEquals(listOf("restore Call the plumber", "purge Call the plumber"), calls)
    }

    @Test
    fun `delete forever asks for confirmation`() {
        show(TrashUiState(items = listOf(task), loaded = true, confirmingPurge = task))

        composeRule.onNodeWithText("Delete permanently?").assertExists()
        composeRule.onNodeWithTag(TrashTags.CONFIRM_CANCEL).performClick()
        composeRule.onNodeWithTag(TrashTags.CONFIRM_DELETE).performClick()
        assertEquals(listOf("cancel", "confirm"), calls)
    }

    @Test
    fun `a busy item's actions are disabled`() {
        show(TrashUiState(items = listOf(task), loaded = true, busy = setOf(task.key)))
        composeRule.onNodeWithTag(TrashTags.restore(task)).assertIsNotEnabled()
        composeRule.onNodeWithTag(TrashTags.purge(task)).assertIsNotEnabled()
    }

    @Test
    fun `loading shows the progress bar and no empty text`() {
        show(TrashUiState(loading = true))
        composeRule.onNodeWithTag(TrashTags.LOADING).assertExists()
        composeRule.onNodeWithTag(TrashTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun `an empty Trash says so`() {
        show(TrashUiState(loaded = true))
        composeRule.onNodeWithTag(TrashTags.EMPTY).assertExists()
    }

    @Test
    fun `a failed load says why and retries`() {
        show(TrashUiState(message = TrashMessage.OFFLINE))
        composeRule.onNodeWithText("The Trash could not be loaded", substring = true).assertExists()
        composeRule.onNodeWithTag(TrashTags.MESSAGE).assertExists()
        composeRule.onNodeWithTag(TrashTags.RETRY).performClick()
        assertEquals(listOf("retry"), calls)
    }

    @Test
    fun `the result of a change is shown in a snackbar`() {
        show(TrashUiState(loaded = true, snackbar = TrashSnackbar.Restored(task, 1)))
        composeRule.onNodeWithText("Restored “Call the plumber”").assertExists()
    }

    @Test
    fun `every kind of note has its text and is dismissed once shown`() {
        var state by mutableStateOf(TrashUiState(loaded = true, snackbar = TrashSnackbar.Purged(task, 1)))
        composeRule.setContent { CockpitTheme { TrashContent(state, callbacks, now = now, zone = ZoneOffset.UTC) } }
        composeRule.onNodeWithText("Deleted “Call the plumber” permanently").assertExists()
        composeRule.waitUntil(timeoutMillis = 10_000) { "dismiss" in calls }

        state = state.copy(snackbar = TrashSnackbar.Failed(note, TrashFailure.OFFLINE, 2))
        composeRule.onNodeWithText("No connection to the server: “Recipe” was not changed.").assertExists()

        state = state.copy(snackbar = TrashSnackbar.Failed(note, TrashFailure.FAILED, 3))
        composeRule.onNodeWithText("“Recipe” could not be changed.").assertExists()
    }

    @Test
    fun `pulling the list down asks to refresh, and a pulled refresh shows no extra progress bar`() {
        var state by mutableStateOf(TrashUiState(items = listOf(task), loaded = true))
        composeRule.setContent { CockpitTheme { TrashContent(state, callbacks, now = now, zone = ZoneOffset.UTC) } }
        composeRule.onNodeWithTag(TrashTags.LIST).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertEquals(listOf("refresh"), calls)

        state = state.copy(loading = true, refreshing = true)
        composeRule.onNodeWithTag(TrashTags.LOADING).assertDoesNotExist()
    }

    @Test
    fun `an empty Trash can be pulled down too`() {
        show(TrashUiState(loaded = true))
        composeRule.onNodeWithTag(TrashTags.LIST).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertEquals(listOf("refresh"), calls)
    }

    @Test
    fun `an item due today, tomorrow or soon says so`() {
        val today = task.copy(id = id(3), purgeAt = now.plusSeconds(0))
        val tomorrow = task.copy(id = id(4), purgeAt = now.plusSeconds(86_400))
        show(TrashUiState(items = listOf(today, tomorrow), loaded = true))

        composeRule.onNodeWithTag(TrashTags.purgeIn(today), useUnmergedTree = true).assertTextEquals("Deleted permanently today")
        composeRule.onNodeWithTag(TrashTags.purgeIn(tomorrow), useUnmergedTree = true).assertTextEquals("Deleted permanently in 1 day")
    }

    @Test
    fun `an item of an unknown type is labelled as an item`() {
        val odd = note.copy(id = id(5), type = "SPACESHIP", title = "Fictitious odd thing")
        show(TrashUiState(items = listOf(odd), loaded = true))
        composeRule.onNodeWithTag(TrashTags.item(odd)).assertTextContains("Item", substring = true)
        composeRule.onNodeWithContentDescription("Item", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the confirmation names the item`() {
        show(TrashUiState(items = listOf(task), loaded = true, confirmingPurge = task))
        composeRule
            .onNodeWithText("“Call the plumber” will be deleted permanently, with its links. This cannot be undone.")
            .assertExists()
    }

    @Test
    fun `the hint about 30 days is shown once loaded`() {
        show(TrashUiState(items = listOf(task), loaded = true))
        composeRule.onNodeWithText("Deleted items stay here for 30 days", substring = true).assertExists()
        composeRule.onNodeWithTag(TrashTags.EMPTY).assertDoesNotExist()
    }

    @Test
    fun `nothing is claimed before the first load`() {
        show(TrashUiState())
        composeRule.onNodeWithTag(TrashTags.EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag(TrashTags.MESSAGE).assertDoesNotExist()
        composeRule.onNodeWithText("Deleted items stay here for 30 days", substring = true).assertDoesNotExist()
    }

    @Test
    fun `offline with loaded items says they are the last loaded ones`() {
        show(TrashUiState(items = listOf(task), loaded = true, message = TrashMessage.OFFLINE))
        composeRule.onNodeWithText("No connection to the server. These are the last loaded items.").assertExists()
        composeRule.onNodeWithTag(TrashTags.item(task)).assertExists()
    }

    @Test
    fun `a refused load says so, and retry waits while a load runs`() {
        show(TrashUiState(loading = true, message = TrashMessage.FAILED))
        composeRule.onNodeWithText("The Trash could not be loaded.").assertExists()
        composeRule.onNodeWithTag(TrashTags.RETRY).assertIsNotEnabled()
        composeRule.onNodeWithTag(TrashTags.EMPTY).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "de")
    fun `the rows are German on a German phone`() {
        show(TrashUiState(items = listOf(task), loaded = true))

        val row = composeRule.onNodeWithTag(TrashTags.item(task))
        row.assertTextContains("Aufgabe · Home", substring = true)
        row.assertTextContains("Gelöscht am 01.10.2026", substring = true)
        composeRule.onNodeWithTag(TrashTags.purgeIn(task), useUnmergedTree = true).assertTextEquals("Wird in 2 Tagen endgültig gelöscht")
        composeRule.onNodeWithContentDescription("Call the plumber wiederherstellen").assertExists()
        composeRule.onNodeWithText("Gelöschte Elemente bleiben 30 Tage hier", substring = true).assertExists()
    }
}
