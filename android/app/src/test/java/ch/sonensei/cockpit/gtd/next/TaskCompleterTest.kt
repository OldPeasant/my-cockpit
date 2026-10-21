package ch.sonensei.cockpit.gtd.next

import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** The completion state machine shared by the Next and Today view models, with server answers the test controls. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskCompleterTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val call = GtdTask(id(1), "Call the dentist", TaskList.NEXT, 0, 1)
    private val water = GtdTask(id(2), "Water the plants", TaskList.NEXT, 1, 3)
    private val repository = mockk<TaskCompletionRepository>()

    private fun completed(task: GtdTask) = CompletionResult.Completed(Completion(task.id, task.title, task.version + 1))

    private fun test(block: suspend TestScope.(TaskCompleter) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            block(TaskCompleter(backgroundScope, repository))
        }

    @Test
    fun `completing the same task twice sends it to the server once`() =
        test { completer ->
            val answer = CompletableDeferred<CompletionResult>()
            coEvery { repository.complete(call) } coAnswers { answer.await() }

            completer.complete(call)
            completer.complete(call)
            answer.complete(completed(call))
            completer.complete(call)

            coVerify(exactly = 1) { repository.complete(any()) }
            assertEquals(setOf(call.id), completer.state.value.hidden)
            assertEquals(CompletionSnackbar.Undo(Completion(call.id, call.title, 2)), completer.state.value.snackbar)
        }

    @Test
    fun `a new completion clears the previous snackbar at once`() =
        test { completer ->
            coEvery { repository.complete(call) } returns CompletionResult.NotDone(CompletionMessage.FAILED)
            val answer = CompletableDeferred<CompletionResult>()
            coEvery { repository.complete(water) } coAnswers { answer.await() }

            completer.complete(call)
            assertEquals(CompletionSnackbar.Failed(CompletionMessage.FAILED), completer.state.value.snackbar)
            completer.complete(water)

            assertNull(completer.state.value.snackbar)
            assertEquals(setOf(water.id), completer.state.value.hidden)
        }

    @Test
    fun `a refused completion can be tried again`() =
        test { completer ->
            coEvery { repository.complete(call) } returnsMany
                listOf(CompletionResult.NotDone(CompletionMessage.OFFLINE), completed(call))

            completer.complete(call)
            assertEquals(emptySet<UUID>(), completer.state.value.hidden)
            assertEquals(CompletionSnackbar.Failed(CompletionMessage.OFFLINE), completer.state.value.snackbar)

            completer.complete(call)
            coVerify(exactly = 2) { repository.complete(call) }
            assertEquals(setOf(call.id), completer.state.value.hidden)
            assertEquals(CompletionSnackbar.Undo(Completion(call.id, call.title, 2)), completer.state.value.snackbar)
        }

    @Test
    fun `undo hides the offer at once and keeps the task hidden until the server reopened it`() =
        test { completer ->
            coEvery { repository.complete(call) } returns completed(call)
            val answer = CompletableDeferred<CompletionResult>()
            coEvery { repository.undo(any()) } coAnswers { answer.await() }

            completer.complete(call)
            completer.undo(completer.state.value.snackbar as CompletionSnackbar.Undo)
            assertNull(completer.state.value.snackbar)
            assertEquals(setOf(call.id), completer.state.value.hidden)

            answer.complete(CompletionResult.Reopened)
            assertEquals(emptySet<UUID>(), completer.state.value.hidden)
            assertNull(completer.state.value.snackbar)
        }

    @Test
    fun `completing a task while its undo is pending does nothing`() =
        test { completer ->
            coEvery { repository.complete(call) } returns completed(call)
            coEvery { repository.undo(any()) } coAnswers { CompletableDeferred<CompletionResult>().await() }

            completer.complete(call)
            completer.undo(completer.state.value.snackbar as CompletionSnackbar.Undo)
            completer.complete(call)

            coVerify(exactly = 1) { repository.complete(any()) }
        }

    @Test
    fun `a refused undo keeps the task hidden and says why`() =
        test { completer ->
            coEvery { repository.complete(call) } returns completed(call)
            coEvery { repository.undo(any()) } returns CompletionResult.NotDone(CompletionMessage.CONFLICT)

            completer.complete(call)
            val offer = completer.state.value.snackbar as CompletionSnackbar.Undo
            completer.undo(offer)

            assertEquals(setOf(call.id), completer.state.value.hidden)
            assertEquals(CompletionSnackbar.Failed(CompletionMessage.CONFLICT), completer.state.value.snackbar)
            coVerify(exactly = 1) { repository.undo(Completion(call.id, call.title, 2)) }
        }

    @Test
    fun `a reopened task can be completed again with the version undo left`() =
        test { completer ->
            coEvery { repository.complete(any()) } answers { completed(firstArg()) }
            coEvery { repository.undo(any()) } returns CompletionResult.Reopened

            completer.complete(call)
            completer.undo(completer.state.value.snackbar as CompletionSnackbar.Undo)
            val reloaded = call.copy(version = 3)
            completer.complete(reloaded)

            coVerify { repository.complete(reloaded) }
            assertEquals(setOf(call.id), completer.state.value.hidden)
            assertEquals(CompletionSnackbar.Undo(Completion(call.id, call.title, 4)), completer.state.value.snackbar)
        }

    @Test
    fun `answers arriving out of order only bring back the refused task`() =
        test { completer ->
            val first = CompletableDeferred<CompletionResult>()
            val second = CompletableDeferred<CompletionResult>()
            coEvery { repository.complete(call) } coAnswers { first.await() }
            coEvery { repository.complete(water) } coAnswers { second.await() }

            completer.complete(call)
            completer.complete(water)
            assertEquals(setOf(call.id, water.id), completer.state.value.hidden)

            second.complete(completed(water))
            assertEquals(CompletionSnackbar.Undo(Completion(water.id, water.title, 4)), completer.state.value.snackbar)
            first.complete(CompletionResult.NotDone(CompletionMessage.CONFLICT))

            assertEquals(setOf(water.id), completer.state.value.hidden)
            assertEquals(CompletionSnackbar.Failed(CompletionMessage.CONFLICT), completer.state.value.snackbar)
        }

    @Test
    fun `a completed task is hidden only up to the version the completion gave it`() =
        test { completer ->
            coEvery { repository.complete(call) } returns completed(call)
            completer.complete(call)

            val state = completer.state.value
            assertTrue(state.hides(call))
            assertTrue(state.hides(call.copy(version = 2)))
            assertFalse(state.hides(call.copy(version = 3)))
            assertFalse(state.hides(water))
        }

    @Test
    fun `a task is hidden while its undo is pending, whatever its version`() =
        test { completer ->
            coEvery { repository.complete(call) } returns completed(call)
            val answer = CompletableDeferred<CompletionResult>()
            coEvery { repository.undo(any()) } coAnswers { answer.await() }

            completer.complete(call)
            completer.undo(completer.state.value.snackbar as CompletionSnackbar.Undo)
            assertTrue(completer.state.value.hides(call.copy(version = 3)))

            answer.complete(CompletionResult.NotDone(CompletionMessage.CONFLICT))
            assertFalse(completer.state.value.hides(call.copy(version = 3)))
            assertTrue(completer.state.value.hides(call))
        }

    @Test
    fun `a successful undo stops hiding every cached copy of the task`() =
        test { completer ->
            coEvery { repository.complete(call) } returns completed(call)
            coEvery { repository.undo(any()) } returns CompletionResult.Reopened

            completer.complete(call)
            completer.undo(completer.state.value.snackbar as CompletionSnackbar.Undo)

            assertFalse(completer.state.value.hides(call))
            assertFalse(completer.state.value.hides(call.copy(version = 2)))
            assertEquals(emptyMap<UUID, Long>(), completer.state.value.completed)
        }

    @Test
    fun `a task reopened elsewhere whose second completion is refused shows again`() =
        test { completer ->
            val reopened = call.copy(version = 3)
            coEvery { repository.complete(call) } returns completed(call)
            coEvery { repository.complete(reopened) } returns CompletionResult.NotDone(CompletionMessage.CONFLICT)

            completer.complete(call)
            completer.complete(reopened)

            coVerify(exactly = 1) { repository.complete(reopened) }
            assertFalse(completer.state.value.hides(reopened))
            assertTrue(completer.state.value.hides(call))
            assertEquals(CompletionSnackbar.Failed(CompletionMessage.CONFLICT), completer.state.value.snackbar)
        }

    @Test
    fun `dismissing a snackbar that is no longer shown changes nothing`() =
        test { completer ->
            coEvery { repository.complete(any()) } answers { completed(firstArg()) }
            completer.complete(call)
            val shown = completer.state.value.snackbar!!

            completer.dismiss(CompletionSnackbar.Failed(CompletionMessage.OFFLINE))
            assertEquals(shown, completer.state.value.snackbar)
            completer.dismiss(shown)
            assertNull(completer.state.value.snackbar)
            completer.dismiss(shown)
            assertNull(completer.state.value.snackbar)
            assertEquals(setOf(call.id), completer.state.value.hidden)
        }
}
