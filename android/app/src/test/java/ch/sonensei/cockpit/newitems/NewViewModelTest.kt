package ch.sonensei.cockpit.newitems

import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.mail.list.MailPage
import ch.sonensei.cockpit.mail.list.MailRepository
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.mail.list.Mailbox
import ch.sonensei.cockpit.mail.read.MailGtdAction
import ch.sonensei.cockpit.mail.read.MailReadRepository
import ch.sonensei.cockpit.newitems.NewTestData.mail
import ch.sonensei.cockpit.newitems.NewTestData.task
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class NewViewModelTest {
    private val loadedAt = Instant.parse("2026-10-20T12:00:00Z")
    private val plumber = task("Call the plumber", "2026-10-20T08:00:00Z", 1)
    private val invoice = mail("Invoice", "2026-10-20T09:00:00Z", 3)
    private val party = mail("Party", "2026-10-20T11:00:00Z", 4)

    private val inbox = MutableStateFlow(Cached(listOf(plumber), offline = false, refreshedAt = loadedAt))
    private val tasks =
        mockk<TaskRepository> {
            every { observe(TaskView.INBOX) } returns inbox
            coEvery { refresh(TaskView.INBOX) } returns RefreshResult.Refreshed
        }
    private val mailRepo = mockk<MailRepository>()
    private val reader = mockk<MailReadRepository>()
    private val cache = NewMailCache()
    private val filters =
        object : NewFilterStore {
            var stored = NewFilter.ALL

            override fun read() = stored

            override fun write(filter: NewFilter) {
                stored = filter
            }
        }
    private val clock = Clock.fixed(loadedAt, ZoneOffset.UTC)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = NewViewModel(tasks, mailRepo, reader, cache, filters, clock)

    private fun mailAnswers(result: MailResult<MailPage>) = coEvery { mailRepo.messages(Mailbox.Unified, null) } returns result

    @Test
    fun showsTasksAndMailsMergedAndKeepsTheMailForOffline() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(party, invoice), null)))
            val vm = viewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

            vm.refresh()

            assertEquals(
                listOf("mail:${party.id}", "mail:${invoice.id}", "task:${plumber.id}"),
                vm.uiState.value.rows
                    .map { it.key },
            )
            assertEquals(listOf(party, invoice), cache.last?.messages)
            assertTrue(vm.uiState.value.canConvert)
        }

    @Test
    fun offlineShowsTheCachedMailReadOnly() =
        runTest {
            cache.last = CachedMail(listOf(invoice), null, loadedAt)
            inbox.value = Cached(listOf(plumber), offline = true, refreshedAt = loadedAt)
            mailAnswers(MailResult.Offline)
            val vm = viewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

            vm.refresh()
            vm.toTask(invoice.id)

            val state = vm.uiState.value
            assertTrue(state.offline)
            assertTrue(state.mailOffline)
            assertFalse(state.canConvert)
            assertEquals(listOf("mail:${invoice.id}", "task:${plumber.id}"), state.rows.map { it.key })
            coVerify(exactly = 0) { reader.gtd(any(), any()) }
        }

    @Test
    fun theFilterIsRememberedAcrossViewModels() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(invoice), null)))
            val first = viewModel()
            first.setFilter(NewFilter.MAIL)

            val second = viewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { second.uiState.collect {} }
            second.refresh()

            assertEquals(NewFilter.MAIL, second.uiState.value.filter)
            assertEquals(
                listOf("mail:${invoice.id}"),
                second.uiState.value.rows
                    .map { it.key },
            )
        }

    @Test
    fun toTaskCreatesAnInboxTaskAndReloadsTheInbox() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(invoice), null)))
            coEvery { reader.gtd(invoice.id, MailGtdAction.TASK) } returns MailResult.Ok("Invoice")
            val vm = viewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            vm.refresh()

            vm.toTask(invoice.id)

            assertEquals(NewNotice.TaskCreated("Invoice"), vm.uiState.value.notice)
            assertTrue(
                vm.uiState.value.converting
                    .isEmpty(),
            )
            coVerify { tasks.refresh(TaskView.INBOX) }
        }

    @Test
    fun aRemovedMailLeavesTheList() =
        runTest {
            mailAnswers(MailResult.Ok(MailPage(listOf(party, invoice), null)))
            val vm = viewModel()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            vm.refresh()

            vm.removed(party.id)

            assertEquals(
                listOf("mail:${invoice.id}", "task:${plumber.id}"),
                vm.uiState.value.rows
                    .map { it.key },
            )
            assertEquals(listOf(invoice), cache.last?.messages)
        }
}
