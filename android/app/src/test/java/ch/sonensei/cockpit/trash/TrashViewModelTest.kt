package ch.sonensei.cockpit.trash

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<TrashRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun item(
        n: Int,
        type: String = "TASK",
    ) = TrashItem(id(n), type, "Item $n", null, Instant.parse("2026-10-01T09:00:00Z"), Instant.parse("2026-10-31T09:00:00Z"))

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun loaded(vararg items: TrashItem): TrashViewModel {
        coEvery { repo.list() } returns TrashResult.Ok(items.toList())
        return TrashViewModel(repo).also { it.refresh() }
    }

    @Test
    fun `loads the Trash and shows loading meanwhile`() =
        runTest(dispatcher) {
            val vm = loaded(item(1), item(2, "NOTE"))
            assertTrue(vm.uiState.value.loading)
            assertFalse(vm.uiState.value.loaded)

            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.loading)
            assertTrue(state.loaded)
            assertEquals(listOf(item(1), item(2, "NOTE")), state.items)
            assertNull(state.message)
        }

    @Test
    fun `a failed load keeps the last items and says why`() =
        runTest(dispatcher) {
            val vm = loaded(item(1))
            advanceUntilIdle()
            coEvery { repo.list() } returns TrashResult.Offline
            vm.refresh()
            advanceUntilIdle()
            assertEquals(TrashMessage.OFFLINE, vm.uiState.value.message)
            assertEquals(listOf(item(1)), vm.uiState.value.items)

            coEvery { repo.list() } returns TrashResult.Failed(400)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(TrashMessage.FAILED, vm.uiState.value.message)
        }

    @Test
    fun `restore removes the item and confirms it`() =
        runTest(dispatcher) {
            val vm = loaded(item(1), item(2))
            advanceUntilIdle()
            coEvery { repo.restore(item(1)) } returns TrashResult.Ok(Unit)

            vm.restore(item(1))
            assertTrue(item(1).key in vm.uiState.value.busy)
            advanceUntilIdle()

            assertEquals(listOf(item(2)), vm.uiState.value.items)
            assertEquals(item(1), (vm.uiState.value.snackbar as TrashSnackbar.Restored).item)
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )
        }

    @Test
    fun `delete forever waits for the confirmation`() =
        runTest(dispatcher) {
            val vm = loaded(item(1))
            advanceUntilIdle()
            coEvery { repo.purge(item(1)) } returns TrashResult.Ok(Unit)

            vm.askPurge(item(1))
            assertEquals(item(1), vm.uiState.value.confirmingPurge)
            vm.cancelPurge()
            advanceUntilIdle()
            assertNull(vm.uiState.value.confirmingPurge)
            coVerify(exactly = 0) { repo.purge(any()) }

            vm.askPurge(item(1))
            vm.confirmPurge()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.purge(item(1)) }
            assertTrue(
                vm.uiState.value.items
                    .isEmpty(),
            )
            assertTrue(vm.uiState.value.snackbar is TrashSnackbar.Purged)
        }

    @Test
    fun `a failed change keeps the item, an item gone elsewhere leaves the list`() =
        runTest(dispatcher) {
            val vm = loaded(item(1), item(2))
            advanceUntilIdle()
            coEvery { repo.restore(item(1)) } returns TrashResult.Offline
            coEvery { repo.restore(item(2)) } returns TrashResult.Failed(404)

            vm.restore(item(1))
            advanceUntilIdle()
            assertEquals(TrashFailure.OFFLINE, (vm.uiState.value.snackbar as TrashSnackbar.Failed).failure)
            vm.dismiss(vm.uiState.value.snackbar!!)

            vm.restore(item(2))
            advanceUntilIdle()
            assertEquals(listOf(item(1)), vm.uiState.value.items)
            assertNull(vm.uiState.value.snackbar)
        }

    @Test
    fun `a second tap while a change runs is ignored`() =
        runTest(dispatcher) {
            val vm = loaded(item(1))
            advanceUntilIdle()
            val gate = CompletableDeferred<TrashResult<Unit>>()
            coEvery { repo.restore(item(1)) } coAnswers { gate.await() }

            vm.restore(item(1))
            runCurrent()
            vm.restore(item(1))
            gate.complete(TrashResult.Ok(Unit))
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.restore(item(1)) }
        }

    @Test
    fun `a load that started before a restore cannot bring the item back`() =
        runTest(dispatcher) {
            val vm = loaded(item(1), item(2))
            advanceUntilIdle()
            val stale = CompletableDeferred<TrashResult<List<TrashItem>>>()
            coEvery { repo.list() } coAnswers { stale.await() } andThen TrashResult.Ok(listOf(item(2)))
            coEvery { repo.restore(item(1)) } returns TrashResult.Ok(Unit)

            vm.refresh()
            runCurrent()
            vm.restore(item(1))
            advanceUntilIdle()
            stale.complete(TrashResult.Ok(listOf(item(1), item(2))))
            advanceUntilIdle()

            assertEquals(listOf(item(2)), vm.uiState.value.items)
        }
}
