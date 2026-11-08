package ch.sonensei.cockpit.contacts

import ch.sonensei.cockpit.core.network.generated.models.ContactRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** Creating, editing and deleting a contact through [ContactEditViewModel] with a fake repository. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactEditViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<ContactsRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val anna =
        Contact(
            id(1),
            "Anna Muster",
            "Beispiel AG",
            null,
            listOf(LabeledValue("anna@example.org", "work")),
            emptyList(),
            emptyList(),
            null,
            null,
            "LOCAL",
            version = 3,
            givenName = "Anna",
            familyName = "Muster",
        )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a new contact starts with an empty form and no errors`() {
        val vm = ContactEditViewModel(repo)
        vm.start(null)
        assertEquals(ContactEditUiState(), vm.uiState.value)
        assertEquals(ContactForm.empty(), vm.uiState.value.form)
    }

    @Test
    fun `editing a contact fills the form from it, dropping an earlier form`() {
        val vm = ContactEditViewModel(repo)
        vm.start(null)
        vm.change(ContactForm(givenName = "Someone else"))
        vm.start(anna)
        assertEquals(ContactForm.of(anna), vm.uiState.value.form)
        assertEquals(anna, vm.uiState.value.base)
    }

    @Test
    fun `saving a new contact creates it`() =
        runTest(dispatcher) {
            val created = anna.copy(id = id(2), displayName = "Carla", givenName = "Carla", familyName = null)
            val sent = slot<ContactRequest>()
            coEvery { repo.create(capture(sent)) } returns ContactsResult.Ok(created)
            val vm = ContactEditViewModel(repo)
            vm.start(null)
            vm.change(ContactForm.empty().copy(givenName = " Carla "))

            vm.save()
            assertTrue(vm.uiState.value.saving)
            advanceUntilIdle()

            assertEquals("Carla", sent.captured.givenName)
            val state = vm.uiState.value
            assertFalse(state.saving)
            assertEquals(created, state.saved)
            assertEquals(created, state.base)
            assertNull(state.error)
            coVerify(exactly = 0) { repo.update(any(), any()) }
        }

    @Test
    fun `saving an edited contact updates it against the contact it started from`() =
        runTest(dispatcher) {
            val updated = anna.copy(title = "CEO", version = 4)
            coEvery { repo.update(anna, any()) } returns ContactsResult.Ok(updated)
            val vm = ContactEditViewModel(repo)
            vm.start(anna)
            vm.change(ContactForm.of(anna).copy(title = "CEO"))

            vm.save()
            advanceUntilIdle()

            coVerify { repo.update(anna, match { it.title == "CEO" && it.givenName == "Anna" }) }
            coVerify(exactly = 0) { repo.create(any()) }
            assertEquals(updated, vm.uiState.value.saved)
        }

    @Test
    fun `a form that cannot be saved shows its problem and sends nothing, fixing it clears the problem`() =
        runTest(dispatcher) {
            val vm = ContactEditViewModel(repo)
            vm.start(null)
            vm.save()
            advanceUntilIdle()
            assertEquals(ContactFormProblem.NAME_REQUIRED, vm.uiState.value.problem)
            assertFalse(vm.uiState.value.saving)

            vm.change(ContactForm(givenName = "Carla", birthday = "17.5."))
            assertEquals(ContactFormProblem.INVALID_BIRTHDAY, vm.uiState.value.problem)
            vm.change(ContactForm(givenName = "Carla", birthday = "1990-05-17"))
            assertNull(vm.uiState.value.problem)
            coVerify(exactly = 0) { repo.create(any()) }
        }

    @Test
    fun `typing into a fresh form shows no problem before Save is tried`() {
        val vm = ContactEditViewModel(repo)
        vm.start(null)
        vm.change(ContactForm(birthday = "nope"))
        assertNull(vm.uiState.value.problem)
    }

    @Test
    fun `Save while saving does not send a second request`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<ContactsResult<Contact>>()
            coEvery { repo.create(any()) } coAnswers { answer.await() }
            val vm = ContactEditViewModel(repo)
            vm.start(null)
            vm.change(ContactForm(givenName = "Carla"))
            vm.save()
            advanceUntilIdle()
            vm.save()
            advanceUntilIdle()
            answer.complete(ContactsResult.Ok(anna))
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.create(any()) }
            assertEquals(anna, vm.uiState.value.saved)
        }

    @Test
    fun `a change made elsewhere reloads the contact and keeps the user's edits for the next save`() =
        runTest(dispatcher) {
            val current = anna.copy(organization = "Neu AG", version = 5)
            coEvery { repo.update(anna, any()) } returns ContactsResult.Failed(412)
            coEvery { repo.get(anna.id) } returns ContactsResult.Ok(current)
            coEvery { repo.update(current, any()) } returns ContactsResult.Ok(current.copy(title = "CEO", version = 6))
            val vm = ContactEditViewModel(repo)
            vm.start(anna)
            val edited = ContactForm.of(anna).copy(title = "CEO")
            vm.change(edited)

            vm.save()
            advanceUntilIdle()
            assertEquals(ContactSaveError.CONFLICT, vm.uiState.value.error)
            assertEquals(current, vm.uiState.value.base)
            assertEquals(edited, vm.uiState.value.form)
            assertNull(vm.uiState.value.saved)

            vm.save()
            advanceUntilIdle()
            coVerify { repo.update(current, match { it.title == "CEO" }) }
            assertNull(vm.uiState.value.error)
            assertEquals(current.copy(title = "CEO", version = 6), vm.uiState.value.saved)
        }

    @Test
    fun `a 409 is a conflict too, and a failed reload keeps the old base`() =
        runTest(dispatcher) {
            coEvery { repo.update(anna, any()) } returns ContactsResult.Failed(409)
            coEvery { repo.get(anna.id) } returns ContactsResult.Offline
            val vm = ContactEditViewModel(repo)
            vm.start(anna)
            vm.save()
            advanceUntilIdle()
            assertEquals(ContactSaveError.CONFLICT, vm.uiState.value.error)
            assertEquals(anna, vm.uiState.value.base)
        }

    @Test
    fun `refusals from a read-only source, other failures and no connection are told apart`() =
        runTest(dispatcher) {
            val google = anna.copy(source = "GOOGLE")
            val cases =
                listOf(
                    ContactsResult.Failed(403) to ContactSaveError.NOT_ALLOWED,
                    ContactsResult.Failed(405) to ContactSaveError.NOT_ALLOWED,
                    ContactsResult.Failed(400) to ContactSaveError.INVALID,
                    ContactsResult.Failed(404) to ContactSaveError.FAILED,
                    ContactsResult.Failed(null) to ContactSaveError.FAILED,
                    ContactsResult.Offline to ContactSaveError.OFFLINE,
                )
            for ((result, error) in cases) {
                coEvery { repo.update(google, any()) } returns result
                val vm = ContactEditViewModel(repo)
                vm.start(google)
                vm.save()
                advanceUntilIdle()
                assertEquals("$result", error, vm.uiState.value.error)
                assertFalse(vm.uiState.value.saving)
                assertNull(vm.uiState.value.saved)
                // Not a conflict: nothing is reloaded.
                coVerify(exactly = 0) { repo.get(any()) }
            }
        }

    @Test
    fun `a save refused as invalid keeps what the server said is wrong`() =
        runTest(dispatcher) {
            val detail = "emails[0].address is not an email address"
            coEvery { repo.create(any()) } returns ContactsResult.Failed(400, detail)
            val vm = ContactEditViewModel(repo)
            vm.start(null)
            vm.change(ContactForm(emails = listOf(LabeledField("anna.example.org"))))
            vm.save()
            advanceUntilIdle()
            assertEquals(ContactSaveError.INVALID, vm.uiState.value.error)
            assertEquals(detail, vm.uiState.value.errorDetail)
            vm.cancel()
            assertNull(vm.uiState.value.error)
            assertNull(vm.uiState.value.errorDetail)
        }

    @Test
    fun `starting the same form session again keeps what was typed, a new session starts over`() {
        val vm = ContactEditViewModel(repo)
        vm.start(anna, "s1")
        vm.change(ContactForm.of(anna).copy(givenName = "Annette"))
        vm.start(anna, "s1")
        assertEquals("Annette", vm.uiState.value.form.givenName)
        vm.start(anna, "s2")
        assertEquals(ContactForm.of(anna), vm.uiState.value.form)
    }

    @Test
    fun `a failed save is cleared from the banner when Save is tried again`() =
        runTest(dispatcher) {
            coEvery { repo.create(any()) } returns ContactsResult.Offline andThen ContactsResult.Ok(anna)
            val vm = ContactEditViewModel(repo)
            vm.start(null)
            vm.change(ContactForm(givenName = "Carla"))
            vm.save()
            advanceUntilIdle()
            assertEquals(ContactSaveError.OFFLINE, vm.uiState.value.error)
            vm.save()
            assertNull(vm.uiState.value.error)
            advanceUntilIdle()
            assertEquals(anna, vm.uiState.value.saved)
        }

    @Test
    fun `delete moves the contact to the Trash`() =
        runTest(dispatcher) {
            coEvery { repo.delete(anna) } returns ContactsResult.Ok(Unit)
            val vm = ContactEditViewModel(repo)
            vm.delete(anna)
            assertTrue(vm.uiState.value.saving)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.deleted)
            assertFalse(vm.uiState.value.saving)
            assertNull(vm.uiState.value.error)
            coVerify(exactly = 1) { repo.delete(anna) }
        }

    @Test
    fun `a refused or failed delete says why and deletes nothing`() =
        runTest(dispatcher) {
            val cases =
                listOf(
                    ContactsResult.Failed(403) to ContactSaveError.NOT_ALLOWED,
                    ContactsResult.Failed(412) to ContactSaveError.CONFLICT,
                    ContactsResult.Failed(404) to ContactSaveError.FAILED,
                    ContactsResult.Offline to ContactSaveError.OFFLINE,
                )
            for ((result, error) in cases) {
                coEvery { repo.delete(anna) } returns result
                val vm = ContactEditViewModel(repo)
                vm.delete(anna)
                advanceUntilIdle()
                assertEquals("$result", error, vm.uiState.value.error)
                assertFalse(vm.uiState.value.deleted)
                assertFalse(vm.uiState.value.saving)
            }
        }

    @Test
    fun `delete while a save or delete runs is ignored`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<ContactsResult<Unit>>()
            coEvery { repo.delete(anna) } coAnswers { answer.await() }
            val vm = ContactEditViewModel(repo)
            vm.delete(anna)
            advanceUntilIdle()
            vm.delete(anna)
            answer.complete(ContactsResult.Ok(Unit))
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.delete(anna) }
        }

    @Test
    fun `finished clears what the screen handled, so the next screen starts clean`() =
        runTest(dispatcher) {
            coEvery { repo.update(anna, any()) } returns ContactsResult.Ok(anna)
            coEvery { repo.delete(anna) } returns ContactsResult.Failed(400) andThen ContactsResult.Ok(Unit)
            val vm = ContactEditViewModel(repo)
            vm.start(anna)
            vm.save()
            advanceUntilIdle()
            vm.finished()
            assertNull(vm.uiState.value.saved)

            vm.delete(anna)
            advanceUntilIdle()
            assertEquals(ContactSaveError.FAILED, vm.uiState.value.error)
            vm.finished()
            assertNull(vm.uiState.value.error)

            vm.delete(anna)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.deleted)
            vm.finished()
            assertFalse(vm.uiState.value.deleted)
        }
}
