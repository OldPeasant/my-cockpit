package ch.sonensei.cockpit.contacts

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<ContactsRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun contact(n: Int) = Contact(id(n), "Contact $n", null, null, emptyList(), emptyList(), emptyList(), null, null, "LOCAL")

    private val nothing = ContactRelated(emptyList(), emptyList(), emptyList(), emptyList())

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the list loads, and a search runs once typing pauses`() =
        runTest(dispatcher) {
            coEvery { repo.list("") } returns ContactsResult.Ok(listOf(contact(1), contact(2)))
            coEvery { repo.list("an") } returns ContactsResult.Ok(listOf(contact(3)))
            val vm = ContactsListViewModel(repo)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(listOf(contact(1), contact(2)), vm.uiState.value.contacts)
            assertTrue(vm.uiState.value.loaded)

            vm.search("a")
            advanceTimeBy(100)
            vm.search("an")
            advanceUntilIdle()
            assertEquals("an", vm.uiState.value.query)
            assertEquals(listOf(contact(3)), vm.uiState.value.contacts)
            coVerify(exactly = 0) { repo.list("a") }
        }

    @Test
    fun `a failed load keeps the last list and says why`() =
        runTest(dispatcher) {
            coEvery { repo.list("") } returns ContactsResult.Ok(listOf(contact(1))) andThen ContactsResult.Offline
            val vm = ContactsListViewModel(repo)
            vm.refresh()
            advanceUntilIdle()
            vm.refresh()
            advanceUntilIdle()
            assertEquals(listOf(contact(1)), vm.uiState.value.contacts)
            assertEquals(ContactsMessage.OFFLINE, vm.uiState.value.message)
            // Same search: the list shown still belongs to it.
            assertTrue(vm.uiState.value.loaded)
        }

    @Test
    fun `retrying a failed new search that then succeeds shows its result`() =
        runTest(dispatcher) {
            coEvery { repo.list("Bob") } returns ContactsResult.Offline andThen ContactsResult.Ok(emptyList())
            val vm = ContactsListViewModel(repo)
            vm.search("Bob")
            advanceUntilIdle()
            assertFalse(vm.uiState.value.loaded)

            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.loaded)
            assertNull(vm.uiState.value.message)
            assertEquals(emptyList<Contact>(), vm.uiState.value.contacts)
            coVerify(exactly = 2) { repo.list("Bob") }
        }

    @Test
    fun `a failed new search does not claim that nothing matches it`() =
        runTest(dispatcher) {
            coEvery { repo.list("zz") } returns ContactsResult.Ok(emptyList())
            coEvery { repo.list("Bob") } returns ContactsResult.Offline
            val vm = ContactsListViewModel(repo)
            vm.search("zz")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.loaded)

            vm.search("Bob")
            assertFalse(vm.uiState.value.loaded)
            advanceUntilIdle()
            assertEquals("Bob", vm.uiState.value.query)
            assertFalse(vm.uiState.value.loaded)
            assertEquals(ContactsMessage.OFFLINE, vm.uiState.value.message)
        }

    @Test
    fun `a failed refresh keeps the related items shown, with a message`() =
        runTest(dispatcher) {
            coEvery { repo.get(id(1)) } returns ContactsResult.Ok(contact(1))
            coEvery { repo.related(id(1)) } returns ContactsResult.Ok(nothing) andThen ContactsResult.Offline
            val vm = ContactDetailViewModel(repo)
            vm.open(id(1))
            advanceUntilIdle()
            vm.refresh()
            advanceUntilIdle()
            assertEquals(nothing, vm.uiState.value.related)
            assertEquals(ContactsMessage.OFFLINE, vm.uiState.value.relatedMessage)
        }

    @Test
    fun `the detail loads the contact and its related items separately`() =
        runTest(dispatcher) {
            coEvery { repo.get(id(1)) } returns ContactsResult.Ok(contact(1))
            coEvery { repo.related(id(1)) } returns ContactsResult.Failed(500)
            coEvery { repo.get(id(2)) } returns ContactsResult.Ok(contact(2))
            coEvery { repo.related(id(2)) } returns ContactsResult.Ok(nothing)
            val vm = ContactDetailViewModel(repo)
            vm.open(id(1))
            advanceUntilIdle()
            assertEquals(contact(1), vm.uiState.value.contact)
            assertNull(vm.uiState.value.message)
            assertEquals(ContactsMessage.FAILED, vm.uiState.value.relatedMessage)

            vm.open(id(2))
            assertNull(vm.uiState.value.contact)
            advanceUntilIdle()
            assertEquals(contact(2), vm.uiState.value.contact)
            assertEquals(nothing, vm.uiState.value.related)
            assertNull(vm.uiState.value.relatedMessage)

            vm.open(id(2))
            advanceUntilIdle()
            coVerify(exactly = 2) { repo.get(id(2)) }
        }

    @Test
    fun `a contact that cannot be loaded says so`() =
        runTest(dispatcher) {
            coEvery { repo.get(id(1)) } returns ContactsResult.Offline
            coEvery { repo.related(id(1)) } returns ContactsResult.Offline
            val vm = ContactDetailViewModel(repo)
            vm.open(id(1))
            advanceUntilIdle()
            assertNull(vm.uiState.value.contact)
            assertEquals(ContactsMessage.OFFLINE, vm.uiState.value.message)
        }
}
