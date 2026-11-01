package ch.sonensei.cockpit.trash

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

/** The screen wired to a real view model (repository faked): it loads when shown, and taps reach the repository. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class TrashRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val task =
        TrashItem(id(1), "TASK", "Call the plumber", null, Instant.parse("2026-10-01T09:00:00Z"), Instant.parse("2099-10-31T09:00:00Z"))
    private val note =
        TrashItem(id(2), "NOTE", "Recipe", null, Instant.parse("2026-10-20T09:00:00Z"), Instant.parse("2099-11-19T09:00:00Z"))

    private val repo = mockk<TrashRepository>()

    private fun show() {
        val viewModel = TrashViewModel(repo)
        composeRule.setContent { CockpitTheme { TrashRoute(viewModel = viewModel) } }
        composeRule.waitForIdle()
    }

    @Test
    fun `outside a Hilt activity the Trash shows the placeholder instead of failing`() {
        composeRule.setContent { CockpitTheme { TrashScreen() } }

        composeRule.onNodeWithText("This part of your cockpit is not built yet.").assertExists()
        composeRule.onNodeWithTag(TrashTags.LIST).assertDoesNotExist()
    }

    @Test
    fun `the Trash loads when it is shown`() {
        coEvery { repo.list() } returns TrashResult.Ok(listOf(task, note))
        show()

        coVerify(exactly = 1) { repo.list() }
        composeRule.onNodeWithTag(TrashTags.item(task)).assertExists()
        composeRule.onNodeWithTag(TrashTags.item(note)).assertExists()
    }

    @Test
    fun `retry after a failed load shows the items`() {
        coEvery { repo.list() } returns TrashResult.Offline andThen TrashResult.Ok(listOf(task))
        show()
        composeRule.onNodeWithText("No connection to the server. The Trash could not be loaded.").assertExists()

        composeRule.onNodeWithTag(TrashTags.RETRY).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TrashTags.MESSAGE).assertDoesNotExist()
        composeRule.onNodeWithTag(TrashTags.item(task)).assertExists()
    }

    @Test
    fun `pulling down reloads the Trash`() {
        coEvery { repo.list() } returns TrashResult.Ok(listOf(task)) andThen TrashResult.Ok(listOf(task, note))
        show()

        composeRule.onNodeWithTag(TrashTags.LIST).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        coVerify(exactly = 2) { repo.list() }
        composeRule.onNodeWithTag(TrashTags.item(note)).assertExists()
    }

    @Test
    fun `restore takes the item out of the list`() {
        coEvery { repo.list() } returns TrashResult.Ok(listOf(task, note))
        coEvery { repo.restore(task) } returns TrashResult.Ok(Unit)
        show()

        composeRule.onNodeWithTag(TrashTags.restore(task)).performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { repo.restore(task) }
        composeRule.onNodeWithTag(TrashTags.item(task)).assertDoesNotExist()
        composeRule.onNodeWithTag(TrashTags.item(note)).assertExists()
    }

    @Test
    fun `delete forever runs only after the confirmation`() {
        coEvery { repo.list() } returns TrashResult.Ok(listOf(task))
        coEvery { repo.purge(task) } returns TrashResult.Ok(Unit)
        show()

        composeRule.onNodeWithTag(TrashTags.purge(task)).performClick()
        composeRule.onNodeWithTag(TrashTags.CONFIRM_CANCEL).performClick()
        composeRule.waitForIdle()
        coVerify(exactly = 0) { repo.purge(any()) }
        composeRule.onNodeWithTag(TrashTags.CONFIRM).assertDoesNotExist()

        composeRule.onNodeWithTag(TrashTags.purge(task)).performClick()
        composeRule.onNodeWithTag(TrashTags.CONFIRM_DELETE).performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 1) { repo.purge(task) }
        composeRule.onNodeWithTag(TrashTags.item(task)).assertDoesNotExist()
        composeRule.onNodeWithTag(TrashTags.EMPTY).assertExists()
    }
}
