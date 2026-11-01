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
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `a first load that fails shows the error, and retry loads the Trash`() =
        runTest(dispatcher) {
            coEvery { repo.list() } returns TrashResult.Offline andThen TrashResult.Ok(listOf(item(1)))
            val vm = TrashViewModel(repo)

            vm.refresh()
            advanceUntilIdle()
            var state = vm.uiState.value
            assertEquals(TrashMessage.OFFLINE, state.message)
            assertFalse(state.loaded)
            assertFalse(state.loading)
            assertTrue(state.items.isEmpty())

            vm.refresh()
            assertTrue(vm.uiState.value.loading)
            advanceUntilIdle()
            state = vm.uiState.value
            assertNull(state.message)
            assertTrue(state.loaded)
            assertEquals(listOf(item(1)), state.items)
        }

    @Test
    fun `an unreadable answer on the first load is a failure, not an empty Trash`() =
        runTest(dispatcher) {
            coEvery { repo.list() } returns TrashResult.Failed(null)
            val vm = TrashViewModel(repo).also { it.refresh() }
            advanceUntilIdle()
            assertEquals(TrashMessage.FAILED, vm.uiState.value.message)
            assertFalse(vm.uiState.value.loaded)
        }

    @Test
    fun `the list stays marked as loaded when a later reload fails`() =
        runTest(dispatcher) {
            val vm = loaded(item(1))
            advanceUntilIdle()
            coEvery { repo.list() } returns TrashResult.Failed(500)
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.loaded)
            assertEquals(TrashMessage.FAILED, vm.uiState.value.message)
        }

    @Test
    fun `pull to refresh is flagged while it runs and replaces the list`() =
        runTest(dispatcher) {
            val vm = loaded(item(1), item(2))
            advanceUntilIdle()
            coEvery { repo.list() } returns TrashResult.Ok(listOf(item(3)))

            vm.refresh(pulled = true)
            assertTrue(vm.uiState.value.refreshing)
            assertTrue(vm.uiState.value.loading)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.refreshing)
            assertFalse(vm.uiState.value.loading)
            assertEquals(listOf(item(3)), vm.uiState.value.items)
        }

    @Test
    fun `a newer load replaces a slower older one`() =
        runTest(dispatcher) {
            val vm = TrashViewModel(repo)
            val slow = CompletableDeferred<TrashResult<List<TrashItem>>>()
            coEvery { repo.list() } coAnswers { slow.await() } andThen TrashResult.Ok(listOf(item(2)))

            vm.refresh()
            runCurrent()
            vm.refresh()
            advanceUntilIdle()
            slow.complete(TrashResult.Ok(listOf(item(1))))
            advanceUntilIdle()

            assertEquals(listOf(item(2)), vm.uiState.value.items)
            assertFalse(vm.uiState.value.loading)
        }

    @Test
    fun `a failed delete keeps the item and says why, one deleted elsewhere just leaves`() =
        runTest(dispatcher) {
            val vm = loaded(item(1), item(2))
            advanceUntilIdle()
            coEvery { repo.purge(item(1)) } returns TrashResult.Failed(409)
            coEvery { repo.purge(item(2)) } returns TrashResult.Failed(404)

            vm.askPurge(item(1))
            vm.confirmPurge()
            advanceUntilIdle()
            val failed = vm.uiState.value.snackbar as TrashSnackbar.Failed
            assertEquals(item(1), failed.item)
            assertEquals(TrashFailure.FAILED, failed.failure)
            assertEquals(listOf(item(1), item(2)), vm.uiState.value.items)

            vm.askPurge(item(2))
            vm.confirmPurge()
            advanceUntilIdle()
            assertEquals(listOf(item(1)), vm.uiState.value.items)
            assertEquals(failed, vm.uiState.value.snackbar)
        }

    @Test
    fun `confirming closes the dialog at once, confirming nothing does nothing`() =
        runTest(dispatcher) {
            val vm = loaded(item(1))
            advanceUntilIdle()
            val gate = CompletableDeferred<TrashResult<Unit>>()
            coEvery { repo.purge(item(1)) } coAnswers { gate.await() }

            vm.confirmPurge()
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.purge(any()) }

            vm.askPurge(item(1))
            vm.confirmPurge()
            assertNull(vm.uiState.value.confirmingPurge)
            assertTrue(item(1).key in vm.uiState.value.busy)
            gate.complete(TrashResult.Ok(Unit))
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.busy
                    .isEmpty(),
            )
        }

    @Test
    fun `different items can change at the same time`() =
        runTest(dispatcher) {
            val vm = loaded(item(1), item(2, "NOTE"))
            advanceUntilIdle()
            val gate = CompletableDeferred<TrashResult<Unit>>()
            coEvery { repo.restore(any()) } coAnswers { gate.await() }

            vm.restore(item(1))
            vm.restore(item(2, "NOTE"))
            runCurrent()
            assertEquals(setOf(item(1).key, item(2, "NOTE").key), vm.uiState.value.busy)
            gate.complete(TrashResult.Ok(Unit))
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.items
                    .isEmpty(),
            )
            coVerify(exactly = 2) { repo.restore(any()) }
        }

    @Test
    fun `each note is new, and dismissing an older one keeps the newer`() =
        runTest(dispatcher) {
            val vm = loaded(item(1), item(2))
            advanceUntilIdle()
            coEvery { repo.restore(any()) } returns TrashResult.Ok(Unit)

            vm.restore(item(1))
            advanceUntilIdle()
            val first = vm.uiState.value.snackbar!!
            vm.restore(item(2))
            advanceUntilIdle()
            val second = vm.uiState.value.snackbar!!
            assertNotEquals(first.id, second.id)

            vm.dismiss(first)
            assertEquals(second, vm.uiState.value.snackbar)
            vm.dismiss(second)
            assertNull(vm.uiState.value.snackbar)
        }

    @Test
    fun `a change during a pull to refresh restarts it as a pulled refresh`() =
        runTest(dispatcher) {
            val vm = loaded(item(1), item(2))
            advanceUntilIdle()
            val stale = CompletableDeferred<TrashResult<List<TrashItem>>>()
            val fresh = CompletableDeferred<TrashResult<List<TrashItem>>>()
            coEvery { repo.list() } coAnswers { stale.await() } coAndThen { fresh.await() }
            coEvery { repo.restore(item(1)) } returns TrashResult.Ok(Unit)

            vm.refresh(pulled = true)
            runCurrent()
            vm.restore(item(1))
            runCurrent()
            assertTrue(vm.uiState.value.refreshing)
            coVerify(exactly = 3) { repo.list() }

            fresh.complete(TrashResult.Ok(listOf(item(2))))
            advanceUntilIdle()
            assertFalse(vm.uiState.value.refreshing)
            assertEquals(listOf(item(2)), vm.uiState.value.items)
        }
}
