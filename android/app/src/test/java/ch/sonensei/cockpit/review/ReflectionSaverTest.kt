package ch.sonensei.cockpit.review

import ch.sonensei.cockpit.journal.JournalEntry
import ch.sonensei.cockpit.journal.JournalRepository
import ch.sonensei.cockpit.journal.JournalResult
import ch.sonensei.cockpit.journal.JournalSaveResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class ReflectionSaverTest {
    private val journal = mockk<JournalRepository>()

    // 23:30 UTC on Nov 7 is already Nov 8 in Zurich: the entry goes to the local day.
    private val clock = Clock.fixed(Instant.parse("2026-11-07T23:30:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 11, 8)
    private val saver = ReflectionSaver(journal, { "Weekly review" }, clock) { ZoneId.of("Europe/Zurich") }

    private fun entry(
        body: String,
        version: Long = 1,
    ) = JournalEntry(UUID.fromString("00000000-0000-7000-8000-000000000001"), today, body, 4, listOf("home"), Instant.EPOCH, version)

    @Test
    fun `a day without an entry gets a new one with the reflection under the heading`() =
        runTest {
            coEvery { journal.get(today) } returns JournalResult.Ok(null)
            coEvery { journal.save(today, null, any()) } returns JournalSaveResult.Saved(entry("x"))
            assertEquals(ReflectionOutcome.SAVED, saver.save("A calm week."))
            coVerify { journal.save(today, null, "## Weekly review\n\nA calm week.\n") }
        }

    @Test
    fun `an existing entry keeps its text and gets the reflection appended`() =
        runTest {
            val existing = entry("Morning run.\n\n")
            coEvery { journal.get(today) } returns JournalResult.Ok(existing)
            coEvery { journal.save(today, existing, any()) } returns JournalSaveResult.Saved(existing)
            assertEquals(ReflectionOutcome.SAVED, saver.save("A calm week."))
            coVerify { journal.save(today, existing, "Morning run.\n\n## Weekly review\n\nA calm week.\n") }
        }

    @Test
    fun `an entry changed elsewhere is appended to in its current form`() =
        runTest {
            val newer = entry("Written on the web.", version = 2)
            coEvery { journal.get(today) } returns JournalResult.Ok(null)
            coEvery { journal.save(today, null, any()) } returns JournalSaveResult.Conflict(newer)
            coEvery { journal.save(today, newer, any()) } returns JournalSaveResult.Saved(newer)
            assertEquals(ReflectionOutcome.SAVED, saver.save("Notes"))
            coVerify { journal.save(today, newer, "Written on the web.\n\n## Weekly review\n\nNotes\n") }
        }

    @Test
    fun `offline and refused saves are reported`() =
        runTest {
            coEvery { journal.get(today) } returns JournalResult.Offline
            assertEquals(ReflectionOutcome.OFFLINE, saver.save("Notes"))
            coEvery { journal.get(today) } returns JournalResult.Ok(null)
            coEvery { journal.save(today, null, any()) } returns JournalSaveResult.Failed(400)
            assertEquals(ReflectionOutcome.FAILED, saver.save("Notes"))
        }

    @Test
    fun `a refused load of today's entry fails without saving`() =
        runTest {
            coEvery { journal.get(today) } returns JournalResult.Failed(403)
            assertEquals(ReflectionOutcome.FAILED, saver.save("Notes"))
            coVerify(exactly = 0) { journal.save(any(), any(), any()) }
        }

    @Test
    fun `a save that loses the connection is reported offline`() =
        runTest {
            coEvery { journal.get(today) } returns JournalResult.Ok(null)
            coEvery { journal.save(today, null, any()) } returns JournalSaveResult.Offline
            assertEquals(ReflectionOutcome.OFFLINE, saver.save("Notes"))
        }

    @Test
    fun `a whitespace-only existing entry is treated as empty`() =
        runTest {
            val blank = entry("  \n\n ")
            coEvery { journal.get(today) } returns JournalResult.Ok(blank)
            coEvery { journal.save(today, blank, any()) } returns JournalSaveResult.Saved(blank)
            assertEquals(ReflectionOutcome.SAVED, saver.save("  Notes  "))
            coVerify { journal.save(today, blank, "## Weekly review\n\nNotes\n") }
        }

    @Test
    fun `repeated conflicts give up instead of looping`() =
        runTest {
            coEvery { journal.get(today) } returns JournalResult.Ok(null)
            coEvery { journal.save(today, any(), any()) } returns JournalSaveResult.Conflict(null)
            assertEquals(ReflectionOutcome.FAILED, saver.save("Notes"))
            coVerify(exactly = 2) { journal.save(today, any(), any()) }
        }
}
