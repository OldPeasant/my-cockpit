package ch.sonensei.cockpit.mail.list

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The list with its real view model: a failed next page is not asked for in a loop, Retry brings paging back. */
@RunWith(AndroidJUnit4::class)
class MailListPagingRetryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneId.of("Europe/Zurich"))
    private val work = MailAccount(id(80), "Work", "me@example.org")
    private val repo = mockk<MailRepository>()

    private fun mail(n: Int) =
        MailSummary(id(n), work.id, "Anna Example", "Subject $n", "", Instant.parse("2026-10-21T07:00:00Z"), true, false, false)

    private fun showWith(vm: MailListViewModel) =
        composeRule.setContent {
            val state by vm.uiState.collectAsState()
            CockpitTheme {
                MailListContent(state, MailListCallbacks(loadMore = vm::loadMore, retry = vm::refresh), clock = clock)
            }
        }

    @Test
    fun `an offline next page is asked for once, and again only after Retry succeeds`() {
        coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(work, emptyList())))
        coEvery { repo.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage((1..3).map(::mail), "c1"))
        coEvery { repo.messages(Mailbox.Unified, "c1") } returns MailResult.Offline
        val vm = MailListViewModel(repo)
        vm.refresh()
        showWith(vm)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        // The short list is near its end: the page is asked for, fails, and is not asked for again by itself.
        coVerify(exactly = 1) { repo.messages(Mailbox.Unified, "c1") }
        assertEquals(MailListMessage.OFFLINE, vm.uiState.value.message)
        assertEquals("c1", vm.uiState.value.nextCursor)

        // Back online: Retry reloads the first page, keeps the cursor, and paging goes on from it.
        coEvery { repo.messages(Mailbox.Unified, "c1") } returns MailResult.Ok(MailPage(listOf(mail(4)), null))
        composeRule.onNodeWithTag(MailListTags.RETRY).performClick()
        composeRule.waitForIdle()

        coVerify(exactly = 2) { repo.messages(Mailbox.Unified, null) }
        coVerify(exactly = 2) { repo.messages(Mailbox.Unified, "c1") }
        assertEquals(
            (1..4).map(::id),
            vm.uiState.value.messages
                .map { it.id },
        )
        assertNull(vm.uiState.value.nextCursor)
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun `a Retry that fails again does not start paging`() {
        coEvery { repo.accounts() } returns MailResult.Ok(listOf(MailAccountFolders(work, emptyList())))
        coEvery { repo.messages(Mailbox.Unified, null) } returns MailResult.Ok(MailPage((1..3).map(::mail), "c1"))
        coEvery { repo.messages(Mailbox.Unified, "c1") } returns MailResult.Failed(503)
        val vm = MailListViewModel(repo)
        vm.refresh()
        showWith(vm)
        composeRule.waitForIdle()

        coEvery { repo.messages(Mailbox.Unified, null) } returns MailResult.Offline
        composeRule.onNodeWithTag(MailListTags.RETRY).performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()

        coVerify(exactly = 2) { repo.messages(Mailbox.Unified, null) }
        coVerify(exactly = 1) { repo.messages(Mailbox.Unified, "c1") }
        assertEquals(MailListMessage.OFFLINE, vm.uiState.value.message)
        assertEquals(
            (1..3).map(::id),
            vm.uiState.value.messages
                .map { it.id },
        )
    }
}
