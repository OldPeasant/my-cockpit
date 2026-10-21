package ch.sonensei.cockpit.gtd.lists

import ch.sonensei.cockpit.gtd.data.GtdConnectivity
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.RefreshResult
import ch.sonensei.cockpit.gtd.data.TaskList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

/** [liveLoad]'s states, [ActionOutcome.of] and the Waiting For order, without a server. */
class LiveLoadTest {
    private val connectivity = GtdConnectivity()
    private val triggers = MutableSharedFlow<Unit>()
    private val reloads = MutableSharedFlow<Unit>()

    /** Answers the loads in turn: a list, or a thrown exception standing for an unreachable or broken server. */
    private val answers = ArrayDeque<() -> List<String>>()

    /** Like the change triggers ([ch.sonensei.cockpit.gtd.data.GtdRefreshTriggers.on]), the first load is asked for on start. */
    private fun flow() = liveLoad(connectivity, triggers.onStart { emit(Unit) }, reloads) { answers.removeFirst()() }

    private fun offline(): () -> List<String> = { throw IOException("unreachable") }

    private fun broken(): () -> List<String> = { throw IllegalArgumentException("malformed answer") }

    @Test
    fun `a failed reload keeps the loaded data, read-only with the error`() =
        runBlocking {
            answers += { listOf("Contract") }
            answers += broken()
            answers += { listOf("Contract", "Invoice") }
            val states = flow().produceIn(this)
            withTimeout(TIMEOUT) {
                assertEquals(Live<List<String>>(), states.receive())
                assertEquals(Live(listOf("Contract")), states.receive())
                reloads.emit(Unit)
                val failed = states.receive()
                assertEquals(Live(listOf("Contract"), LoadError.FAILED), failed)
                assertTrue(failed.readOnly)
                assertFalse(failed.loading)
                triggers.emit(Unit)
                assertEquals(Live(listOf("Contract", "Invoice")), states.receive())
            }
            states.cancel()
        }

    @Test
    fun `an offline reload keeps the loaded data, a later trigger clears the error`() =
        runBlocking {
            answers += { listOf("Contract") }
            answers += offline()
            answers += { listOf("Contract") }
            val states = flow().produceIn(this)
            withTimeout(TIMEOUT) {
                states.receive()
                assertEquals(Live(listOf("Contract")), states.receive())
                triggers.emit(Unit)
                assertEquals(Live(listOf("Contract"), LoadError.OFFLINE), states.receive())
                triggers.emit(Unit)
                val back = states.receive()
                assertEquals(Live(listOf("Contract")), back)
                assertFalse(back.readOnly)
            }
            states.cancel()
        }

    @Test
    fun `a first load that fails is an error, and the retry shows loading before the answer`() =
        runBlocking {
            answers += broken()
            answers += { emptyList() }
            val states = flow().produceIn(this)
            withTimeout(TIMEOUT) {
                assertTrue(states.receive().loading)
                val failed = states.receive()
                assertEquals(Live<List<String>>(error = LoadError.FAILED), failed)
                assertFalse(failed.loading)
                reloads.emit(Unit)
                assertTrue(states.receive().loading)
                assertEquals(Live(emptyList<String>()), states.receive())
            }
            states.cancel()
        }

    @Test
    fun `conflicts are not found, conflict and precondition failed, other failures are failures`() {
        assertEquals(ActionOutcome.DONE, ActionOutcome.of(RefreshResult.Refreshed))
        assertEquals(ActionOutcome.OFFLINE, ActionOutcome.of(RefreshResult.Offline))
        assertEquals(ActionOutcome.CONFLICT, ActionOutcome.of(RefreshResult.Failed(404)))
        assertEquals(ActionOutcome.CONFLICT, ActionOutcome.of(RefreshResult.Failed(409)))
        assertEquals(ActionOutcome.CONFLICT, ActionOutcome.of(RefreshResult.Failed(412)))
        assertEquals(ActionOutcome.FAILED, ActionOutcome.of(RefreshResult.Failed(400)))
        assertEquals(ActionOutcome.FAILED, ActionOutcome.of(RefreshResult.Failed(403)))
        assertEquals(ActionOutcome.FAILED, ActionOutcome.of(RefreshResult.Failed(null)))
    }

    @Test
    fun `the if-match value is the quoted version`() {
        assertEquals("\"7\"", etag(7))
    }

    @Test
    fun `waiting order ties on the same day keep list order`() {
        val day = LocalDate.of(2026, 10, 11)

        fun task(
            n: Int,
            position: Int,
            since: LocalDate?,
        ) = GtdTask(
            UUID.fromString("00000000-0000-7000-8000-%012d".format(n)),
            "Task $n",
            TaskList.WAITING,
            position,
            1,
            waitingSince = since,
        )
        val second = task(1, 2, day)
        val first = task(2, 1, day)
        val undatedLate = task(3, 4, null)
        val undatedEarly = task(4, 3, null)
        val older = task(5, 5, day.minusDays(1))
        assertEquals(
            listOf(older, first, second, undatedEarly, undatedLate),
            byWaitingSince(listOf(second, undatedLate, first, undatedEarly, older)),
        )
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
