package ch.sonensei.cockpit.habits

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
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class HabitsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<HabitsRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun habit(
        n: Int,
        due: Boolean = true,
        done: Boolean = false,
        streak: Int = 0,
    ) = Habit(
        id(n),
        "Habit $n",
        HabitSchedule.DAILY,
        null,
        emptyList(),
        1,
        LocalDate.of(2026, 10, 22),
        if (done) 1 else 0,
        due,
        done,
        streak,
        false,
        null,
    )

    private val water = habit(1)
    private val stretch = habit(2, due = false)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.list() } returns HabitsResult.Ok(listOf(water, stretch))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads the habits, due today first`() =
        runTest(dispatcher) {
            val vm = HabitsViewModel(repo)
            assertTrue(vm.uiState.value.loading)
            runCurrent()

            val state = vm.uiState.value
            assertFalse(state.loading)
            assertTrue(state.loaded)
            assertEquals(listOf(water), state.dueToday)
            assertEquals(listOf(stretch), state.notDueToday)
        }

    @Test
    fun `a tap checks a habit off and shows its new stats`() =
        runTest(dispatcher) {
            val checked = water.copy(doneToday = true, todayCount = 1, currentStreak = 1)
            coEvery { repo.check(water, done = true) } returns HabitsResult.Ok(checked)
            val vm = HabitsViewModel(repo)
            runCurrent()

            vm.toggle(water.id)
            assertEquals(setOf(water.id), vm.uiState.value.checking)
            advanceUntilIdle()

            assertEquals(listOf(checked, stretch), vm.uiState.value.habits)
            assertTrue(
                vm.uiState.value.checking
                    .isEmpty(),
            )
        }

    @Test
    fun `a tap on a done habit removes today's check`() =
        runTest(dispatcher) {
            val done = water.copy(doneToday = true, todayCount = 1, currentStreak = 4)
            coEvery { repo.list() } returns HabitsResult.Ok(listOf(done))
            coEvery { repo.check(done, done = false) } returns HabitsResult.Ok(water.copy(currentStreak = 3))
            val vm = HabitsViewModel(repo)
            runCurrent()

            vm.toggle(water.id)
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.check(done, done = false) }
            assertFalse(
                vm.uiState.value.habits[0]
                    .doneToday,
            )
        }

    @Test
    fun `a second tap while the first is on its way is ignored`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<HabitsResult<Habit>>()
            coEvery { repo.check(water, done = true) } coAnswers { answer.await() }
            val vm = HabitsViewModel(repo)
            runCurrent()

            vm.toggle(water.id)
            runCurrent()
            vm.toggle(water.id)
            answer.complete(HabitsResult.Ok(water.copy(doneToday = true)))
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.check(any(), any()) }
            assertTrue(
                vm.uiState.value.habits[0]
                    .doneToday,
            )
        }

    @Test
    fun `a failed check-off keeps the habit as it was and says why`() =
        runTest(dispatcher) {
            coEvery { repo.check(water, done = true) } returnsMany listOf(HabitsResult.Offline, HabitsResult.Failed(400))
            val vm = HabitsViewModel(repo)
            runCurrent()

            vm.toggle(water.id)
            advanceUntilIdle()
            assertEquals(HabitsMessage.CHECK_OFFLINE, vm.uiState.value.message)
            assertFalse(
                vm.uiState.value.habits[0]
                    .doneToday,
            )
            assertTrue(
                vm.uiState.value.checking
                    .isEmpty(),
            )

            vm.toggle(water.id)
            advanceUntilIdle()
            assertEquals(HabitsMessage.CHECK_FAILED, vm.uiState.value.message)
            vm.dismissMessage()
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `a failed load can be retried`() =
        runTest(dispatcher) {
            coEvery { repo.list() } returnsMany listOf(HabitsResult.Offline, HabitsResult.Failed(500), HabitsResult.Ok(listOf(water)))
            val vm = HabitsViewModel(repo)
            runCurrent()
            assertEquals(HabitsMessage.LOAD_OFFLINE, vm.uiState.value.message)
            assertFalse(vm.uiState.value.loaded)

            vm.reload()
            runCurrent()
            assertEquals(HabitsMessage.LOAD_FAILED, vm.uiState.value.message)

            vm.reload()
            runCurrent()
            assertNull(vm.uiState.value.message)
            assertEquals(listOf(water), vm.uiState.value.habits)
        }

    @Test
    fun `an unknown habit is not checked`() =
        runTest(dispatcher) {
            val vm = HabitsViewModel(repo)
            runCurrent()
            vm.toggle(id(9))
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.check(any(), any()) }
        }
}
