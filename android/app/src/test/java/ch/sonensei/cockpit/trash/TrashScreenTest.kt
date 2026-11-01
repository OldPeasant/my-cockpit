package ch.sonensei.cockpit.trash

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
