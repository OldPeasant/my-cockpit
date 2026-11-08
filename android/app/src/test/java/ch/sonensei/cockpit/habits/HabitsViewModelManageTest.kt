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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** The archived view, the create/edit form and archiving in the view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class HabitsViewModelManageTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<HabitsRepository>()

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private fun habit(
        n: Int,
        name: String = "Habit $n",
        archived: Boolean = false,
        version: Long = 1,
    ) = Habit(
        id(n),
        name,
        HabitSchedule.DAILY,
        null,
        emptyList(),
        1,
        LocalDate.of(2026, 10, 22),
        0,
        true,
        false,
        0,
        false,
        null,
        archived = archived,
        version = version,
    )

    private val water = habit(1, "Drink water")
    private val stretch = habit(2, "Stretch")
    private val piano = habit(3, "Piano", archived = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.list(false) } returns HabitsResult.Ok(listOf(water, stretch))
        coEvery { repo.list(true) } returns HabitsResult.Ok(listOf(piano))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newViewModel() = HabitsViewModel(repo)

    // --- Archived view ---

    @Test
    fun `the archived view loads the archived habits, the active view loads the active ones again`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            runCurrent()

            vm.showArchived(true)
            assertTrue(vm.uiState.value.archived)
            assertTrue(vm.uiState.value.loading)
            assertFalse(vm.uiState.value.loaded)
            assertEquals(emptyList<Habit>(), vm.uiState.value.habits)
            runCurrent()
            assertEquals(listOf(piano), vm.uiState.value.habits)
            assertTrue(vm.uiState.value.loaded)

            vm.showArchived(false)
            runCurrent()
            assertFalse(vm.uiState.value.archived)
            assertEquals(listOf(water, stretch), vm.uiState.value.habits)
            coVerify(exactly = 2) { repo.list(false) }
            coVerify(exactly = 1) { repo.list(true) }
        }

    @Test
    fun `choosing the view already shown loads nothing`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            runCurrent()

            vm.showArchived(false)
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.list(false) }
            coVerify(exactly = 0) { repo.list(true) }
        }

    @Test
    fun `an answer for the view no longer shown is dropped`() =
        runTest(dispatcher) {
            val active = CompletableDeferred<HabitsResult<List<Habit>>>()
            coEvery { repo.list(false) } coAnswers { active.await() }
            val vm = newViewModel()
            runCurrent()

            vm.showArchived(true)
            runCurrent()
            assertEquals(listOf(piano), vm.uiState.value.habits)

            active.complete(HabitsResult.Ok(listOf(water, stretch)))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.archived)
            assertEquals(listOf(piano), vm.uiState.value.habits)
            assertFalse(vm.uiState.value.loading)
        }

    @Test
    fun `a failed answer for the view no longer shown is dropped too`() =
        runTest(dispatcher) {
            val archived = CompletableDeferred<HabitsResult<List<Habit>>>()
            coEvery { repo.list(true) } coAnswers { archived.await() }
            val vm = newViewModel()
            runCurrent()

            vm.showArchived(true)
            runCurrent()
            vm.showArchived(false)
            runCurrent()
            archived.complete(HabitsResult.Offline)
            advanceUntilIdle()

            assertNull(vm.uiState.value.message)
            assertEquals(listOf(water, stretch), vm.uiState.value.habits)
        }

    @Test
    fun `switching the view closes an open form`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            runCurrent()
            vm.startEdit(water.id)

            vm.showArchived(true)

            assertNull(vm.uiState.value.editor)
        }

    @Test
    fun `an archived habit is not checked off`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            runCurrent()
            vm.showArchived(true)
            runCurrent()

            vm.toggle(piano.id)
            advanceUntilIdle()

            coVerify(exactly = 0) { repo.check(any()) }
        }

    // --- Create / edit form ---

    @Test
    fun `a new habit starts with an empty form, which closes on cancel`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            runCurrent()

            vm.startCreate()
            assertEquals(HabitEditor(habitId = null, draft = HabitDraft()), vm.uiState.value.editor)

            vm.cancelEdit()
            assertNull(vm.uiState.value.editor)
        }

    @Test
    fun `new habits cannot be started in the archived view`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            runCurrent()
            vm.showArchived(true)
            runCurrent()

            vm.startCreate()

            assertNull(vm.uiState.value.editor)
        }

    @Test
    fun `an edit starts with the habit's values, an unknown habit opens nothing`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            runCurrent()

            vm.startEdit(id(9))
            assertNull(vm.uiState.value.editor)

            vm.startEdit(stretch.id)
            assertEquals(HabitEditor(habitId = stretch.id, draft = HabitDraft.of(stretch)), vm.uiState.value.editor)
        }

    @Test
    fun `changing the form keeps its values and clears an earlier save error`() =
        runTest(dispatcher) {
            coEvery { repo.create(any()) } returns HabitsResult.Offline
            val vm = newViewModel()
            runCurrent()
            vm.startCreate()
            vm.updateDraft(HabitDraft(name = "Read"))
            vm.save()
            advanceUntilIdle()
            assertEquals(
                HabitEditorError.OFFLINE,
                vm.uiState.value.editor!!
                    .error,
            )

            vm.updateDraft(HabitDraft(name = "Read more"))

            assertEquals(
                "Read more",
                vm.uiState.value.editor!!
                    .draft.name,
            )
            assertNull(
                vm.uiState.value.editor!!
                    .error,
            )
        }

    @Test
    fun `changing the draft without an open form does nothing`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            runCurrent()

            vm.updateDraft(HabitDraft(name = "Read"))
            vm.save()
            advanceUntilIdle()

            assertNull(vm.uiState.value.editor)
            coVerify(exactly = 0) { repo.create(any()) }
        }

    @Test
    fun `an invalid form is not sent and from then on shows its problem`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            runCurrent()
            vm.startCreate()
            assertFalse(
                vm.uiState.value.editor!!
                    .tried,
            )

            vm.save()
            advanceUntilIdle()

            val editor = vm.uiState.value.editor!!
            assertTrue(editor.tried)
            assertFalse(editor.saving)
            assertEquals(HabitDraftProblem.NAME, editor.draft.problem)
            coVerify(exactly = 0) { repo.create(any()) }
            coVerify(exactly = 0) { repo.update(any(), any()) }
        }

    @Test
    fun `a created habit is added in name order and the form closes`() =
        runTest(dispatcher) {
            val draft = HabitDraft(name = "meditate")
            val created = habit(4, "meditate")
            coEvery { repo.create(draft) } returns HabitsResult.Ok(created)
            val vm = newViewModel()
            runCurrent()
            vm.startCreate()
            vm.updateDraft(draft)

            vm.save()
            assertTrue(
                vm.uiState.value.editor!!
                    .saving,
            )
            advanceUntilIdle()

            assertNull(vm.uiState.value.editor)
            assertEquals(
                listOf("Drink water", "meditate", "Stretch"),
                vm.uiState.value.habits
                    .map { it.name },
            )
            coVerify(exactly = 1) { repo.create(draft) }
        }

    @Test
    fun `while saving the form cannot be changed, cancelled or saved again`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<HabitsResult<Habit>>()
            val draft = HabitDraft(name = "Read")
            coEvery { repo.create(draft) } coAnswers { answer.await() }
            val vm = newViewModel()
            runCurrent()
            vm.startCreate()
            vm.updateDraft(draft)
            vm.save()
            runCurrent()

            vm.updateDraft(HabitDraft(name = "Something else"))
            vm.cancelEdit()
            vm.save()
            runCurrent()

            val editor = vm.uiState.value.editor!!
            assertTrue(editor.saving)
            assertEquals(draft, editor.draft)
            answer.complete(HabitsResult.Ok(habit(4, "Read")))
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.create(any()) }
            assertNull(vm.uiState.value.editor)
        }

    @Test
    fun `an edited habit replaces the old one in place`() =
        runTest(dispatcher) {
            val draft = HabitDraft.of(water).copy(name = "Water", targetCount = 3)
            val saved = water.copy(name = "Water", targetCount = 3, version = 2)
            coEvery { repo.update(water, draft) } returns HabitsResult.Ok(saved)
            val vm = newViewModel()
            runCurrent()
            vm.startEdit(water.id)
            vm.updateDraft(draft)

            vm.save()
            advanceUntilIdle()

            assertNull(vm.uiState.value.editor)
            assertEquals(listOf(saved, stretch), vm.uiState.value.habits)
            coVerify(exactly = 0) { repo.create(any()) }
        }

    @Test
    fun `a failed save keeps the form open and says why`() =
        runTest(dispatcher) {
            coEvery { repo.create(any()) } returnsMany listOf(HabitsResult.Offline, HabitsResult.Failed(400))
            val vm = newViewModel()
            runCurrent()
            vm.startCreate()
            vm.updateDraft(HabitDraft(name = "Read"))

            vm.save()
            advanceUntilIdle()
            assertEquals(
                HabitEditorError.OFFLINE,
                vm.uiState.value.editor!!
                    .error,
            )
            assertFalse(
                vm.uiState.value.editor!!
                    .saving,
            )

            vm.save()
            advanceUntilIdle()
            assertEquals(
                HabitEditorError.FAILED,
                vm.uiState.value.editor!!
                    .error,
            )
            assertEquals(listOf(water, stretch), vm.uiState.value.habits)
        }

    @Test
    fun `a refused create with a conflict status is a plain failure, not a conflict`() =
        runTest(dispatcher) {
            coEvery { repo.create(any()) } returns HabitsResult.Failed(409)
            val vm = newViewModel()
            runCurrent()
            vm.startCreate()
            vm.updateDraft(HabitDraft(name = "Read"))

            vm.save()
            advanceUntilIdle()

            assertEquals(
                HabitEditorError.FAILED,
                vm.uiState.value.editor!!
                    .error,
            )
            coVerify(exactly = 1) { repo.list(false) }
        }

    @Test
    fun `an edit in conflict reloads the list, keeps the form, and saving again uses the reloaded version`() =
        runTest(dispatcher) {
            val newer = water.copy(name = "Drink more water", version = 5)
            coEvery { repo.list(false) } returnsMany
                listOf(HabitsResult.Ok(listOf(water, stretch)), HabitsResult.Ok(listOf(newer, stretch)))
            val draft = HabitDraft.of(water).copy(targetCount = 2)
            coEvery { repo.update(water, draft) } returns HabitsResult.Failed(412)
            coEvery { repo.update(newer, draft) } returns HabitsResult.Ok(newer.copy(targetCount = 2, version = 6))
            val vm = newViewModel()
            runCurrent()
            vm.startEdit(water.id)
            vm.updateDraft(draft)

            vm.save()
            advanceUntilIdle()

            val editor = vm.uiState.value.editor!!
            assertEquals(HabitEditorError.CONFLICT, editor.error)
            assertFalse(editor.saving)
            assertEquals(draft, editor.draft)
            assertEquals(listOf(newer, stretch), vm.uiState.value.habits)
            coVerify(exactly = 2) { repo.list(false) }

            vm.save()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.update(newer, draft) }
            assertNull(vm.uiState.value.editor)
            assertEquals(
                6L,
                vm.uiState.value.habits[0]
                    .version,
            )
        }

    @Test
    fun `saving an edit of a habit deleted elsewhere is a conflict without a request`() =
        runTest(dispatcher) {
            coEvery { repo.list(false) } returnsMany listOf(HabitsResult.Ok(listOf(water, stretch)), HabitsResult.Ok(listOf(stretch)))
            coEvery { repo.update(water, any()) } returns HabitsResult.Failed(404)
            val vm = newViewModel()
            runCurrent()
            vm.startEdit(water.id)
            vm.save()
            advanceUntilIdle()
            assertEquals(
                HabitEditorError.CONFLICT,
                vm.uiState.value.editor!!
                    .error,
            )
            assertEquals(listOf(stretch), vm.uiState.value.habits)

            vm.save()
            advanceUntilIdle()

            assertEquals(
                HabitEditorError.CONFLICT,
                vm.uiState.value.editor!!
                    .error,
            )
            coVerify(exactly = 1) { repo.update(any(), any()) }
        }

    @Test
    fun `an edit in the archived view replaces the archived habit in place`() =
        runTest(dispatcher) {
            val renamed = piano.copy(name = "Grand piano", version = 2)
            coEvery { repo.update(piano, any()) } returns HabitsResult.Ok(renamed)
            val vm = newViewModel()
            runCurrent()
            vm.showArchived(true)
            runCurrent()
            vm.startEdit(piano.id)
            vm.updateDraft(HabitDraft.of(piano).copy(name = "Grand piano"))

            vm.save()
            advanceUntilIdle()

            assertEquals(listOf(renamed), vm.uiState.value.habits)
            assertNull(vm.uiState.value.editor)
        }

    // --- Archive / unarchive ---

    @Test
    fun `archiving removes the habit from the active list`() =
        runTest(dispatcher) {
            coEvery { repo.setArchived(water, true) } returns HabitsResult.Ok(water.copy(archived = true))
            val vm = newViewModel()
            runCurrent()

            vm.toggleArchived(water.id)
            assertEquals(setOf(water.id), vm.uiState.value.checking)
            advanceUntilIdle()

            assertEquals(listOf(stretch), vm.uiState.value.habits)
            assertTrue(
                vm.uiState.value.checking
                    .isEmpty(),
            )
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `unarchiving removes the habit from the archived list`() =
        runTest(dispatcher) {
            coEvery { repo.setArchived(piano, false) } returns HabitsResult.Ok(piano.copy(archived = false))
            val vm = newViewModel()
            runCurrent()
            vm.showArchived(true)
            runCurrent()

            vm.toggleArchived(piano.id)
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.setArchived(piano, false) }
            assertEquals(emptyList<Habit>(), vm.uiState.value.habits)
        }

    @Test
    fun `archiving the habit being edited closes its form`() =
        runTest(dispatcher) {
            coEvery { repo.setArchived(water, true) } returns HabitsResult.Ok(water.copy(archived = true))
            val vm = newViewModel()
            runCurrent()
            vm.startEdit(water.id)

            vm.toggleArchived(water.id)
            advanceUntilIdle()

            assertNull(vm.uiState.value.editor)
        }

    @Test
    fun `archiving another habit keeps the open form`() =
        runTest(dispatcher) {
            coEvery { repo.setArchived(stretch, true) } returns HabitsResult.Ok(stretch.copy(archived = true))
            val vm = newViewModel()
            runCurrent()
            vm.startEdit(water.id)

            vm.toggleArchived(stretch.id)
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.editor)
        }

    @Test
    fun `a failed archive keeps the habit and says why`() =
        runTest(dispatcher) {
            coEvery { repo.setArchived(water, true) } returnsMany listOf(HabitsResult.Offline, HabitsResult.Failed(400))
            val vm = newViewModel()
            runCurrent()

            vm.toggleArchived(water.id)
            advanceUntilIdle()
            assertEquals(HabitsMessage.ARCHIVE_OFFLINE, vm.uiState.value.message)
            assertEquals(listOf(water, stretch), vm.uiState.value.habits)

            vm.toggleArchived(water.id)
            advanceUntilIdle()
            assertEquals(HabitsMessage.ARCHIVE_FAILED, vm.uiState.value.message)
            assertTrue(
                vm.uiState.value.checking
                    .isEmpty(),
            )
        }

    @Test
    fun `an archive in conflict says so and loads the list again`() =
        runTest(dispatcher) {
            val newer = water.copy(version = 4)
            coEvery { repo.list(false) } returnsMany
                listOf(HabitsResult.Ok(listOf(water, stretch)), HabitsResult.Ok(listOf(newer, stretch)))
            coEvery { repo.setArchived(water, true) } returns HabitsResult.Failed(409)
            val vm = newViewModel()
            runCurrent()

            vm.toggleArchived(water.id)
            advanceUntilIdle()

            assertEquals(HabitsMessage.CONFLICT, vm.uiState.value.message)
            assertEquals(listOf(newer, stretch), vm.uiState.value.habits)
            assertFalse(vm.uiState.value.loading)
            coVerify(exactly = 2) { repo.list(false) }
        }

    @Test
    fun `a check-off in conflict says so and loads the list again`() =
        runTest(dispatcher) {
            coEvery { repo.check(water) } returns HabitsResult.Failed(412)
            val vm = newViewModel()
            runCurrent()

            vm.toggle(water.id)
            advanceUntilIdle()

            assertEquals(HabitsMessage.CONFLICT, vm.uiState.value.message)
            coVerify(exactly = 2) { repo.list(false) }
        }

    @Test
    fun `a conflict reload read before a later archive finished does not bring the archived habit back`() =
        runTest(dispatcher) {
            val reload = CompletableDeferred<HabitsResult<List<Habit>>>()
            var lists = 0
            coEvery { repo.list(false) } coAnswers {
                if (lists++ == 0) HabitsResult.Ok(listOf(water, stretch)) else reload.await()
            }
            coEvery { repo.setArchived(water, true) } returns HabitsResult.Failed(412)
            coEvery { repo.setArchived(stretch, true) } returns HabitsResult.Ok(stretch.copy(archived = true, version = 2))
            val vm = newViewModel()
            runCurrent()

            vm.toggleArchived(water.id)
            runCurrent()
            assertTrue(vm.uiState.value.loading)
            vm.toggleArchived(stretch.id)
            runCurrent()
            assertEquals(listOf(water), vm.uiState.value.habits)
            reload.complete(HabitsResult.Ok(listOf(water.copy(version = 3), stretch)))
            advanceUntilIdle()

            assertEquals(listOf(water), vm.uiState.value.habits)
            assertFalse(vm.uiState.value.loading)
            coVerify(exactly = 2) { repo.list(false) }
        }

    @Test
    fun `a conflict reload read before a later check-off finished does not undo the check-off`() =
        runTest(dispatcher) {
            val reload = CompletableDeferred<HabitsResult<List<Habit>>>()
            var lists = 0
            coEvery { repo.list(false) } coAnswers {
                if (lists++ == 0) HabitsResult.Ok(listOf(water, stretch)) else reload.await()
            }
            val checked = stretch.copy(todayCount = 1, version = 2)
            coEvery { repo.check(water) } returns HabitsResult.Failed(409)
            coEvery { repo.check(stretch) } returns HabitsResult.Ok(checked)
            val vm = newViewModel()
            runCurrent()

            vm.toggle(water.id)
            runCurrent()
            vm.toggle(stretch.id)
            runCurrent()
            reload.complete(HabitsResult.Ok(listOf(water.copy(version = 3), stretch)))
            advanceUntilIdle()

            assertEquals(listOf(water, checked), vm.uiState.value.habits)
            assertFalse(vm.uiState.value.loading)
        }

    @Test
    fun `a conflict reload answer is dropped while a later change is still on its way`() =
        runTest(dispatcher) {
            val reload = CompletableDeferred<HabitsResult<List<Habit>>>()
            val check = CompletableDeferred<HabitsResult<Habit>>()
            var lists = 0
            coEvery { repo.list(false) } coAnswers {
                if (lists++ == 0) HabitsResult.Ok(listOf(water, stretch)) else reload.await()
            }
            val checked = stretch.copy(todayCount = 1, version = 2)
            coEvery { repo.check(water) } returns HabitsResult.Failed(412)
            coEvery { repo.check(stretch) } coAnswers { check.await() }
            val vm = newViewModel()
            runCurrent()

            vm.toggle(water.id)
            runCurrent()
            vm.toggle(stretch.id)
            runCurrent()
            reload.complete(HabitsResult.Ok(listOf(water.copy(version = 3), stretch)))
            runCurrent()
            assertEquals(listOf(water, stretch), vm.uiState.value.habits)
            assertFalse(vm.uiState.value.loading)

            check.complete(HabitsResult.Ok(checked))
            advanceUntilIdle()
            assertEquals(listOf(water, checked), vm.uiState.value.habits)
        }

    @Test
    fun `taps on a habit with a target of three count one more each time from the last answer, then reset`() =
        runTest(dispatcher) {
            val pushUps = habit(5, "Push-ups").copy(targetCount = 3)
            coEvery { repo.list(false) } returns HabitsResult.Ok(listOf(pushUps))
            val sent = mutableListOf<Int>()
            coEvery { repo.check(any()) } coAnswers {
                val tapped = firstArg<Habit>()
                sent += tapped.nextCount
                HabitsResult.Ok(
                    tapped.copy(
                        todayCount = tapped.nextCount,
                        doneToday = tapped.nextCount >= tapped.targetCount,
                        version = tapped.version + 1,
                    ),
                )
            }
            val vm = newViewModel()
            runCurrent()

            repeat(4) {
                vm.toggle(pushUps.id)
                advanceUntilIdle()
            }

            assertEquals(listOf(1, 2, 3, 0), sent)
            val last =
                vm.uiState.value.habits
                    .single()
            assertEquals(0, last.todayCount)
            assertFalse(last.doneToday)
        }

    @Test
    fun `a conflict reload that fails keeps the conflict message and the list on screen`() =
        runTest(dispatcher) {
            coEvery { repo.list(false) } returnsMany listOf(HabitsResult.Ok(listOf(water, stretch)), HabitsResult.Offline)
            coEvery { repo.setArchived(water, true) } returns HabitsResult.Failed(412)
            val vm = newViewModel()
            runCurrent()

            vm.toggleArchived(water.id)
            advanceUntilIdle()

            assertEquals(HabitsMessage.CONFLICT, vm.uiState.value.message)
            assertEquals(listOf(water, stretch), vm.uiState.value.habits)
            assertFalse(vm.uiState.value.loading)
            assertTrue(vm.uiState.value.loaded)
            coVerify(exactly = 2) { repo.list(false) }
        }

    @Test
    fun `a save answered after a view switch closed the form changes neither the shown list nor the form`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<HabitsResult<Habit>>()
            val draft = HabitDraft(name = "Read")
            coEvery { repo.create(draft) } coAnswers { answer.await() }
            val vm = newViewModel()
            runCurrent()
            vm.startCreate()
            vm.updateDraft(draft)
            vm.save()
            runCurrent()

            vm.showArchived(true)
            runCurrent()
            answer.complete(HabitsResult.Ok(habit(4, "Read")))
            advanceUntilIdle()

            assertTrue(vm.uiState.value.archived)
            assertEquals(listOf(piano), vm.uiState.value.habits)
            assertNull(vm.uiState.value.editor)
        }

    @Test
    fun `a second archive while the first is on its way is ignored, unknown habits are ignored`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<HabitsResult<Habit>>()
            coEvery { repo.setArchived(water, true) } coAnswers { answer.await() }
            val vm = newViewModel()
            runCurrent()

            vm.toggleArchived(water.id)
            runCurrent()
            vm.toggleArchived(water.id)
            vm.toggleArchived(id(9))
            answer.complete(HabitsResult.Ok(water.copy(archived = true)))
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.setArchived(any(), any()) }
        }
}
