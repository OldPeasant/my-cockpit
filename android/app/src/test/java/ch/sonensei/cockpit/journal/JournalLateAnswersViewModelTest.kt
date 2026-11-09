package ch.sonensei.cockpit.journal

import ch.sonensei.cockpit.notes.NoteImages
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * Late answers not covered by [JournalDaysRaceViewModelTest]: the earlier years of a day already left, and month
 * answers that were computed before a save (the saved mood wins, saves of other months stay out). The order is driven
 * with deferred answers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JournalLateAnswersViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 11, 1)
    private val yesterday = today.minusDays(1)
    private val november = YearMonth.of(2026, 11)
    private val october = YearMonth.of(2026, 10)
    private val clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneId.of("Europe/Zurich"))
    private val repo = mockk<JournalRepository>()
    private val images = mockk<NoteImages> { every { loader } returns mockk() }

    private fun entry(
        date: LocalDate,
        body: String,
        mood: Int? = null,
        version: Long = 1,
    ) = JournalEntry(
        UUID.fromString("00000000-0000-7000-8000-0000000001${date.dayOfMonth.toString().padStart(2, '0')}"),
        date,
        body,
        mood,
        emptyList(),
        Instant.EPOCH,
        version,
    )

    private val todays = entry(today, "Market in the old town", mood = 3)
    private val yesterdays = entry(yesterday, "Read on the balcony", mood = 2)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.get(today) } returns JournalResult.Ok(todays)
        coEvery { repo.get(yesterday) } returns JournalResult.Ok(yesterdays)
        coEvery { repo.days(any(), any()) } returns JournalResult.Ok(emptyMap())
        coEvery { repo.onThisDay(any()) } returns JournalResult.Ok(emptyList())
        coEvery { repo.saveEntry(today, any(), any()) } answers {
            val draft = thirdArg<JournalDraft>()
            JournalSaveResult.Saved(todays.copy(bodyMd = draft.bodyMd, mood = draft.mood, version = 2))
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(
        stub: () -> Unit = {},
        block: suspend TestScope.(JournalViewModel) -> Unit,
    ) = runTest(dispatcher) {
        stub()
        val vm = JournalViewModel(repo, images, CoroutineScope(dispatcher), clock)
        runCurrent()
        block(vm)
    }

    @Test
    fun `the earlier years of a day left answering late do not show on the day opened after it`() {
        val todaysEarlier = CompletableDeferred<JournalResult<List<JournalEntry>>>()
        val yesterdaysEarlier = listOf(entry(yesterday.minusYears(1), "Balcony a year ago"))
        test(stub = {
            coEvery { repo.onThisDay(today) } coAnswers { todaysEarlier.await() }
            coEvery { repo.onThisDay(yesterday) } returns JournalResult.Ok(yesterdaysEarlier)
        }) { vm ->
            vm.previousDay()
            advanceUntilIdle()
            assertEquals(yesterday, vm.uiState.value.date)
            assertEquals(yesterdaysEarlier, vm.uiState.value.onThisDay)

            todaysEarlier.complete(JournalResult.Ok(listOf(entry(today.minusYears(1), "Market a year ago"))))
            advanceUntilIdle()

            assertEquals(yesterdaysEarlier, vm.uiState.value.onThisDay)
            assertFalse(vm.uiState.value.onThisDayFailed)
        }
    }

    @Test
    fun `a failed earlier-years answer of a day left does not mark the opened day as failed`() {
        val todaysEarlier = CompletableDeferred<JournalResult<List<JournalEntry>>>()
        test(stub = { coEvery { repo.onThisDay(today) } coAnswers { todaysEarlier.await() } }) { vm ->
            vm.previousDay()
            advanceUntilIdle()

            todaysEarlier.complete(JournalResult.Offline)
            advanceUntilIdle()

            assertEquals(yesterday, vm.uiState.value.date)
            assertFalse(vm.uiState.value.onThisDayFailed)
        }
    }

    @Test
    fun `the earlier years count as loading until their answer arrives, also after the entry loaded`() {
        val todaysEarlier = CompletableDeferred<JournalResult<List<JournalEntry>>>()
        test(stub = { coEvery { repo.onThisDay(today) } coAnswers { todaysEarlier.await() } }) { vm ->
            advanceUntilIdle()
            assertFalse(vm.uiState.value.loading)
            assertTrue(vm.uiState.value.onThisDayLoading)

            todaysEarlier.complete(JournalResult.Ok(emptyList()))
            advanceUntilIdle()

            assertFalse(vm.uiState.value.onThisDayLoading)
            assertEquals(emptyList<JournalEntry>(), vm.uiState.value.onThisDay)
        }
    }

    @Test
    fun `a failed earlier-years answer ends their loading and marks them failed`() {
        test(stub = { coEvery { repo.onThisDay(today) } returns JournalResult.Offline }) { vm ->
            advanceUntilIdle()

            assertFalse(vm.uiState.value.onThisDayLoading)
            assertTrue(vm.uiState.value.onThisDayFailed)
        }
    }

    @Test
    fun `a late earlier-years answer of a day left does not end the loading of the day opened after it`() {
        val todaysEarlier = CompletableDeferred<JournalResult<List<JournalEntry>>>()
        val yesterdaysEarlier = CompletableDeferred<JournalResult<List<JournalEntry>>>()
        test(stub = {
            coEvery { repo.onThisDay(today) } coAnswers { todaysEarlier.await() }
            coEvery { repo.onThisDay(yesterday) } coAnswers { yesterdaysEarlier.await() }
        }) { vm ->
            vm.previousDay()
            advanceUntilIdle()
            assertEquals(yesterday, vm.uiState.value.date)
            assertTrue(vm.uiState.value.onThisDayLoading)

            todaysEarlier.complete(JournalResult.Ok(listOf(entry(today.minusYears(1), "Market a year ago"))))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.onThisDayLoading)

            yesterdaysEarlier.complete(JournalResult.Ok(emptyList()))
            advanceUntilIdle()
            assertFalse(vm.uiState.value.onThisDayLoading)
        }
    }

    @Test
    fun `a month computed before a save shows the saved mood, not the older one`() {
        val monthLoad = CompletableDeferred<JournalResult<Map<LocalDate, Int?>>>()
        test(stub = {
            coEvery { repo.days(november.atDay(1), november.atEndOfMonth()) } coAnswers { monthLoad.await() }
        }) { vm ->
            vm.setMood(5)
            advanceUntilIdle()
            assertEquals(5, vm.uiState.value.monthDays[today])

            // The server answered with the mood the entry had before the save.
            monthLoad.complete(JournalResult.Ok(mapOf(today to 3, november.atDay(2) to 1)))
            advanceUntilIdle()

            assertEquals(mapOf(today to 5, november.atDay(2) to 1), vm.uiState.value.monthDays)
        }
    }

    @Test
    fun `a save in another month is not added to the browsed month answering after it`() {
        val octoberLoad = CompletableDeferred<JournalResult<Map<LocalDate, Int?>>>()
        test(stub = {
            coEvery { repo.days(october.atDay(1), october.atEndOfMonth()) } coAnswers { octoberLoad.await() }
        }) { vm ->
            advanceUntilIdle()
            vm.showMonth(october)
            runCurrent()

            vm.setMood(4)
            advanceUntilIdle()
            octoberLoad.complete(JournalResult.Ok(mapOf(yesterday to 2)))
            advanceUntilIdle()

            assertEquals(october, vm.uiState.value.month)
            assertEquals(mapOf(yesterday to 2), vm.uiState.value.monthDays)
        }
    }

    @Test
    fun `a save made before the month was browsed away and back is not merged into the new answer`() {
        test { vm ->
            vm.setMood(4)
            advanceUntilIdle()
            assertEquals(4, vm.uiState.value.monthDays[today])

            // The entry was deleted elsewhere afterwards; a fresh month request no longer knows it.
            vm.showMonth(october)
            advanceUntilIdle()
            vm.showMonth(november)
            advanceUntilIdle()

            assertEquals(emptyMap<LocalDate, Int?>(), vm.uiState.value.monthDays)
        }
    }
}
