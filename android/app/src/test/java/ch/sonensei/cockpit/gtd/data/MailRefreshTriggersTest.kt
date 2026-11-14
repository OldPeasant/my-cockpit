package ch.sonensei.cockpit.gtd.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which change-stream signals reload the mail views, and how a burst is coalesced (virtual time). */
@OptIn(ExperimentalCoroutinesApi::class)
class MailRefreshTriggersTest {
    private val signals = MutableSharedFlow<ChangeSignal>(extraBufferCapacity = 64)
    private val triggers = MailRefreshTriggers(signals, quietMs = 1_000L)

    /** The virtual times at which the triggers fired, collected for the rest of the test. */
    private fun TestScope.fired(): List<Long> {
        val times = mutableListOf<Long>()
        val clock = testScheduler
        backgroundScope.launch(UnconfinedTestDispatcher(clock)) { triggers.changes().collect { times += clock.currentTime } }
        return times
    }

    private fun changed(type: String) = ChangeSignal.Changed(type)

    @Test
    fun `a new mail fires once the quiet time has passed, not before`() =
        runTest {
            val times = fired()

            signals.tryEmit(changed("EMAIL"))
            advanceTimeBy(999)
            assertEquals(emptyList<Long>(), times)

            advanceTimeBy(2)
            assertEquals(listOf(1_000L), times)
        }

    @Test
    fun `a folder change and a reconnect fire too`() =
        runTest {
            val times = fired()

            signals.tryEmit(changed("MAIL_FOLDER"))
            advanceTimeBy(5_000)
            signals.tryEmit(ChangeSignal.Reconnected)
            advanceTimeBy(5_000)

            assertEquals(listOf(1_000L, 6_000L), times)
        }

    @Test
    fun `GTD changes and the first connection do not fire`() =
        runTest {
            val times = fired()

            signals.tryEmit(changed("TASK"))
            signals.tryEmit(changed("PROJECT"))
            signals.tryEmit(changed("CONTEXT"))
            signals.tryEmit(changed("NOTE"))
            signals.tryEmit(ChangeSignal.Connected)
            advanceTimeBy(10_000)

            assertEquals(emptyList<Long>(), times)
        }

    @Test
    fun `a sync bringing many mails fires once, after the last of them`() =
        runTest {
            val times = fired()

            repeat(20) {
                signals.tryEmit(changed("EMAIL"))
                advanceTimeBy(100)
            }
            signals.tryEmit(changed("MAIL_FOLDER"))
            advanceTimeBy(10_000)

            // The folder change came at 2000 ms; the quiet time counts from there.
            assertEquals(listOf(3_000L), times)
        }

    @Test
    fun `two syncs further apart than the quiet time fire twice`() =
        runTest {
            val times = fired()

            signals.tryEmit(changed("EMAIL"))
            signals.tryEmit(changed("MAIL_FOLDER"))
            advanceTimeBy(1_500)
            signals.tryEmit(changed("EMAIL"))
            advanceTimeBy(5_000)

            assertEquals(listOf(1_000L, 2_500L), times)
        }

    @Test
    fun `a GTD change during the quiet time does not delay the reload`() =
        runTest {
            val times = fired()

            signals.tryEmit(changed("EMAIL"))
            advanceTimeBy(500)
            signals.tryEmit(changed("TASK"))
            advanceTimeBy(5_000)

            assertEquals(listOf(1_000L), times)
        }

    @Test
    fun `the triggers without a change stream never fire`() =
        runTest {
            assertEquals(emptyList<Unit>(), MailRefreshTriggers.NONE.changes().toList())
        }
}
