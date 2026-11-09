package ch.sonensei.cockpit.journal

import ch.sonensei.cockpit.notes.NoteImages
import ch.sonensei.cockpit.notes.SaveStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** Other days, mood, tags, the month calendar and "on this day" of the journal's view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class JournalDaysViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 11, 1)
    private val yesterday = today.minusDays(1)
    private val clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneId.of("Europe/Zurich"))
    private val repo = mockk<JournalRepository>()
    private val images = mockk<NoteImages> { every { loader } returns mockk() }

    private fun entry(
        date: LocalDate,
        body: String,
        mood: Int? = null,
        tags: List<String> = emptyList(),
        version: Long = 1,
    ) = JournalEntry(
        UUID.fromString("00000000-0000-7000-8000-00000000000${date.dayOfMonth % 10}"),
        date,
        body,
        mood,
        tags,
        Instant.EPOCH,
        version,
    )

    private val todays = entry(today, "Today's text", mood = 3, tags = listOf("home"))
    private val yesterdays = entry(yesterday, "Yesterday's text", mood = 5)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.get(today) } returns JournalResult.Ok(todays)
        coEvery { repo.get(yesterday) } returns JournalResult.Ok(yesterdays)
        coEvery { repo.days(any(), any()) } returns JournalResult.Ok(emptyMap())
        coEvery { repo.onThisDay(any()) } returns JournalResult.Ok(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.(JournalViewModel) -> Unit) =
        runTest(dispatcher) {
            val vm = JournalViewModel(repo, images, CoroutineScope(dispatcher), clock)
            runCurrent()
            block(vm)
        }

    @Test
    fun `the previous day opens with its text, mood and tags, and the next day returns`() =
        test { vm ->
            assertTrue(vm.uiState.value.isToday)
            assertFalse(vm.uiState.value.canGoForward)

            vm.previousDay()
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals(yesterday, state.date)
            assertEquals("Yesterday's text", state.body)
            assertEquals(5, state.mood)
            assertTrue(state.editable)
            assertTrue(state.canGoForward)
            assertFalse(state.isToday)

            vm.nextDay()
            advanceUntilIdle()
            assertEquals(today, vm.uiState.value.date)
            assertEquals(listOf("home"), vm.uiState.value.tags)
            assertEquals("home", vm.uiState.value.tagsText)
        }

    @Test
    fun `no day after today can be opened`() =
        test { vm ->
            vm.nextDay()
            vm.openDay(today.plusDays(10))
            advanceUntilIdle()
            assertEquals(today, vm.uiState.value.date)
            coVerify(exactly = 1) { repo.get(any()) }
        }

    @Test
    fun `Today and the date picker open any day, which can be edited`() =
        test { vm ->
            val earlier = LocalDate.of(2025, 3, 14)
            coEvery { repo.get(earlier) } returns JournalResult.Ok(null)
            coEvery { repo.saveEntry(earlier, null, any()) } answers
                { JournalSaveResult.Saved(entry(earlier, thirdArg<JournalDraft>().bodyMd)) }

            vm.openDay(earlier)
            advanceUntilIdle()
            assertEquals(earlier, vm.uiState.value.date)
            assertEquals(YearMonth.of(2025, 3), vm.uiState.value.month)
            vm.setBody("Pi day")
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.saveEntry(earlier, null, JournalDraft("Pi day")) }
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)

            vm.openToday()
            advanceUntilIdle()
            assertEquals(today, vm.uiState.value.date)
            assertEquals(YearMonth.of(2026, 11), vm.uiState.value.month)
        }

    @Test
    fun `changes waiting for their autosave are saved before another day opens`() =
        test { vm ->
            coEvery { repo.saveEntry(today, todays, any()) } answers {
                JournalSaveResult.Saved(todays.copy(bodyMd = thirdArg<JournalDraft>().bodyMd, version = 2))
            }
            vm.setBody("Today's text, longer")
            vm.previousDay()
            advanceUntilIdle()

            coVerify(exactly = 1) { repo.saveEntry(today, todays, JournalDraft("Today's text, longer", 3, listOf("home"))) }
            assertEquals(yesterday, vm.uiState.value.date)
        }

    @Test
    fun `a day that could not be saved stays open`() =
        test { vm ->
            coEvery { repo.saveEntry(any(), any(), any()) } returns JournalSaveResult.Offline
            vm.setBody("Unsaved words")
            vm.previousDay()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(today, state.date)
            assertEquals("Unsaved words", state.body)
            assertEquals(SaveStatus.OFFLINE, state.status)
            assertEquals(JournalMessage.DAY_NOT_SAVED, state.message)
            coVerify(exactly = 0) { repo.get(yesterday) }
        }

    @Test
    fun `a day with a conflict waiting for a decision stays open`() =
        test { vm ->
            val theirs = todays.copy(bodyMd = "From the web", version = 7)
            coEvery { repo.saveEntry(any(), any(), any()) } returns JournalSaveResult.Conflict(theirs)
            vm.setBody("Mine")
            advanceUntilIdle()
            vm.previousDay()
            advanceUntilIdle()

            assertEquals(today, vm.uiState.value.date)
            assertEquals(JournalMessage.DAY_NOT_SAVED, vm.uiState.value.message)
            coVerify(exactly = 1) { repo.saveEntry(any(), any(), any()) }
        }

    @Test
    fun `a day whose load failed can be left`() =
        runTest(dispatcher) {
            coEvery { repo.get(today) } returns JournalResult.Offline
            val vm = JournalViewModel(repo, images, CoroutineScope(dispatcher), clock)
            runCurrent()
            vm.previousDay()
            advanceUntilIdle()
            assertEquals(yesterday, vm.uiState.value.date)
            assertNull(vm.uiState.value.message)
            assertTrue(vm.uiState.value.editable)
        }

    @Test
    fun `the mood is set, changed and cleared, and saved with the text and tags`() =
        test { vm ->
            coEvery { repo.saveEntry(today, any(), any()) } answers {
                val draft = thirdArg<JournalDraft>()
                JournalSaveResult.Saved(todays.copy(mood = draft.mood, version = 2))
            }
            vm.setMood(5)
            assertEquals(5, vm.uiState.value.mood)
            assertEquals(SaveStatus.UNSAVED, vm.uiState.value.status)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.saveEntry(today, todays, JournalDraft("Today's text", 5, listOf("home"))) }
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)

            vm.setMood(5)
            assertNull(vm.uiState.value.mood)
            vm.setMood(9)
            assertNull(vm.uiState.value.mood)
        }

    @Test
    fun `tags are typed as a list, saved without duplicates and tidied when the field is left`() =
        test { vm ->
            coEvery { repo.saveEntry(today, any(), any()) } answers {
                JournalSaveResult.Saved(todays.copy(tags = thirdArg<JournalDraft>().tags.sortedBy { it.lowercase() }, version = 2))
            }
            vm.setTags("home, #Travel,  travel , ")
            assertEquals(listOf("home", "Travel"), vm.uiState.value.tags)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.saveEntry(today, todays, JournalDraft("Today's text", 3, listOf("home", "Travel"))) }
            // The server orders the tags: that is no change.
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)

            vm.tidyTags()
            assertEquals("home, Travel", vm.uiState.value.tagsText)
        }

    @Test
    fun `a day without an entry is created by a mood alone`() =
        test { vm ->
            coEvery { repo.get(yesterday) } returns JournalResult.Ok(null)
            coEvery { repo.saveEntry(yesterday, null, any()) } returns JournalSaveResult.Saved(entry(yesterday, "", mood = 2))
            vm.previousDay()
            advanceUntilIdle()
            vm.setMood(2)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.saveEntry(yesterday, null, JournalDraft("", 2, emptyList())) }
        }

    @Test
    fun `keeping my version after a conflict keeps my mood and tags, the other version takes theirs`() =
        test { vm ->
            val theirs = todays.copy(bodyMd = "Web", mood = 1, tags = listOf("web"), version = 9)
            coEvery { repo.saveEntry(today, todays, any()) } returns JournalSaveResult.Conflict(theirs)
            vm.setMood(4)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.conflict != null)

            vm.useTheirs()
            val state = vm.uiState.value
            assertEquals("Web", state.body)
            assertEquals(1, state.mood)
            assertEquals(listOf("web"), state.tags)
            assertEquals("web", state.tagsText)
            assertEquals(SaveStatus.SAVED, state.status)
        }

    @Test
    fun `the calendar marks the month's days with an entry and follows the opened day`() =
        test { vm ->
            val october = mapOf(LocalDate.of(2026, 10, 3) to 4, LocalDate.of(2026, 10, 31) to null)
            coEvery { repo.days(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)) } returns JournalResult.Ok(october)
            coVerify { repo.days(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)) }

            vm.previousDay()
            advanceUntilIdle()
            assertEquals(YearMonth.of(2026, 10), vm.uiState.value.month)
            assertEquals(october, vm.uiState.value.monthDays)
            assertFalse(vm.uiState.value.monthLoading)
            assertTrue(vm.uiState.value.canShowNextMonth)
        }

    @Test
    fun `the calendar browses months up to the current one and retries a failed month`() =
        test { vm ->
            val september = YearMonth.of(2026, 9)
            coEvery { repo.days(september.atDay(1), september.atEndOfMonth()) } returnsMany
                listOf(JournalResult.Offline, JournalResult.Ok(mapOf(september.atDay(9) to 3)))
            vm.showMonth(YearMonth.of(2027, 1))
            advanceUntilIdle()
            assertEquals(YearMonth.of(2026, 11), vm.uiState.value.month)
            assertFalse(vm.uiState.value.canShowNextMonth)

            vm.showMonth(september)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.monthFailed)
            assertEquals(today, vm.uiState.value.date)

            vm.showMonth(september)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.monthFailed)
            assertEquals(mapOf(september.atDay(9) to 3), vm.uiState.value.monthDays)
        }

    @Test
    fun `a saved entry is marked in the calendar with its mood`() =
        test { vm ->
            coEvery { repo.saveEntry(today, any(), any()) } returns JournalSaveResult.Saved(todays.copy(mood = 2, version = 2))
            vm.setMood(2)
            advanceUntilIdle()
            assertEquals(mapOf(today to 2), vm.uiState.value.monthDays)
        }

    @Test
    fun `on this day lists the earlier years of the shown day`() =
        test { vm ->
            val lastYear = entry(today.minusYears(1), "A year ago")
            coEvery { repo.onThisDay(yesterday) } returns JournalResult.Ok(listOf(entry(yesterday.minusYears(2), "Two years")))
            coEvery { repo.onThisDay(today) } returns JournalResult.Ok(listOf(lastYear))
            vm.previousDay()
            advanceUntilIdle()
            assertEquals(
                listOf("Two years"),
                vm.uiState.value.onThisDay
                    .map { it.bodyMd },
            )

            coEvery { repo.get(today.minusDays(3)) } returns JournalResult.Ok(null)
            coEvery { repo.onThisDay(today.minusDays(3)) } returns JournalResult.Failed(500)
            vm.openDay(today.minusDays(3))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.onThisDayFailed)
            assertTrue(
                vm.uiState.value.onThisDay
                    .isEmpty(),
            )
        }
}
