package ch.sonensei.cockpit.journal

import ch.sonensei.cockpit.notes.NoteImages
import ch.sonensei.cockpit.notes.NotePhoto
import ch.sonensei.cockpit.notes.SaveStatus
import io.mockk.coEvery
import io.mockk.coVerify
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

/**
 * Ordering edge cases of the journal's day navigation: loads, month loads, saves and uploads that answer late or
 * out of order. The order is driven explicitly with deferred answers, never with real delays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JournalDaysRaceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val today = LocalDate.of(2026, 11, 1)
    private val yesterday = today.minusDays(1)
    private val clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneId.of("Europe/Zurich"))
    private val repo = mockk<JournalRepository>()
    private val images = mockk<NoteImages> { every { loader } returns mockk() }
    private val photo = NotePhoto(byteArrayOf(1), "image/jpeg", "harbour.jpg")
    private val image = "![harbour.jpg](cockpit-file:00000000-0000-7000-8000-0000000000aa)"

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

    private val todays = entry(today, "Ferry to the island", mood = 3, tags = listOf("trip"))
    private val yesterdays = entry(yesterday, "Packed the bags", mood = 4)

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

    /** Runs [block] with a view model created after [stub] set up the repository, its first loads done. */
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
    fun `a day opened while today's entry still loads is not overwritten by that late load`() {
        val todayLoad = CompletableDeferred<JournalResult<JournalEntry?>>()
        test(stub = { coEvery { repo.get(today) } coAnswers { todayLoad.await() } }) { vm ->
            assertTrue(vm.uiState.value.loading)

            vm.previousDay()
            advanceUntilIdle()
            todayLoad.complete(JournalResult.Ok(todays))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(yesterday, state.date)
            assertEquals("Packed the bags", state.body)
            assertEquals(4, state.mood)
            assertTrue(state.editable)
        }
    }

    @Test
    fun `a late load of the day left does not overwrite the day opened after it`() {
        val earlier = LocalDate.of(2026, 10, 20)
        val yesterdayLoad = CompletableDeferred<JournalResult<JournalEntry?>>()
        test(stub = {
            coEvery { repo.get(yesterday) } coAnswers { yesterdayLoad.await() }
            coEvery { repo.get(earlier) } returns JournalResult.Ok(null)
        }) { vm ->
            vm.previousDay()
            runCurrent()
            assertEquals(yesterday, vm.uiState.value.date)
            assertTrue(vm.uiState.value.loading)

            vm.openDay(earlier)
            advanceUntilIdle()
            yesterdayLoad.complete(JournalResult.Ok(yesterdays))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(earlier, state.date)
            assertEquals("", state.body)
            assertNull(state.entry)
            assertTrue(state.editing)
        }
    }

    @Test
    fun `navigation waits for a save already running and then opens the day, saving only once`() {
        val save = CompletableDeferred<JournalSaveResult>()
        test(stub = { coEvery { repo.saveEntry(today, todays, any()) } coAnswers { save.await() } }) { vm ->
            vm.setBody("Ferry to the island, then a swim")
            vm.saveNow()
            runCurrent()
            assertEquals(SaveStatus.SAVING, vm.uiState.value.status)

            vm.previousDay()
            runCurrent()
            assertEquals(today, vm.uiState.value.date)
            coVerify(exactly = 0) { repo.get(yesterday) }

            save.complete(JournalSaveResult.Saved(todays.copy(bodyMd = "Ferry to the island, then a swim", version = 2)))
            advanceUntilIdle()

            assertEquals(yesterday, vm.uiState.value.date)
            assertNull(vm.uiState.value.message)
            coVerify(exactly = 1) { repo.saveEntry(any(), any(), any()) }
        }
    }

    @Test
    fun `Today while another day is still being opened cancels that navigation`() {
        val save = CompletableDeferred<JournalSaveResult>()
        test(stub = { coEvery { repo.saveEntry(today, todays, any()) } coAnswers { save.await() } }) { vm ->
            vm.setBody("Ferry, then rain")
            vm.previousDay()
            runCurrent()

            vm.openToday()
            save.complete(JournalSaveResult.Saved(todays.copy(bodyMd = "Ferry, then rain", version = 2)))
            advanceUntilIdle()

            assertEquals(today, vm.uiState.value.date)
            assertEquals("Ferry, then rain", vm.uiState.value.body)
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            coVerify(exactly = 0) { repo.get(yesterday) }
        }
    }

    @Test
    fun `opening the shown day again does not reload it`() =
        test { vm ->
            vm.openDay(today)
            vm.openToday()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.get(today) }
            assertEquals("Ferry to the island", vm.uiState.value.body)
        }

    @Test
    fun `no other day opens while a photo uploads`() {
        val upload = CompletableDeferred<JournalResult<String>>()
        test(stub = {
            coEvery { repo.uploadImage(photo) } coAnswers { upload.await() }
            coEvery { repo.saveEntry(today, todays, any()) } answers
                { JournalSaveResult.Saved(todays.copy(bodyMd = thirdArg<JournalDraft>().bodyMd, version = 2)) }
        }) { vm ->
            vm.addPhoto(photo)
            runCurrent()
            assertTrue(vm.uiState.value.uploading)

            vm.previousDay()
            vm.openDay(LocalDate.of(2026, 9, 1))
            advanceUntilIdle()
            assertEquals(today, vm.uiState.value.date)
            coVerify(exactly = 0) { repo.get(yesterday) }

            upload.complete(JournalResult.Ok(image))
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.body
                    .contains(image),
            )
            assertEquals(today, vm.uiState.value.date)
        }
    }

    /**
     * Assumption of the task: "Navigation is ignored while a photo uploads", so the photo lands in the day it was
     * taken for. A photo started while the shown day is still being saved for a navigation must not end up in the
     * day opened afterwards.
     */
    @Test
    fun `a photo started while the day is saved for navigation does not land in the next day`() {
        val save = CompletableDeferred<JournalSaveResult>()
        val upload = CompletableDeferred<JournalResult<String>>()
        test(stub = {
            coEvery { repo.saveEntry(today, todays, any()) } coAnswers { save.await() }
            coEvery { repo.saveEntry(yesterday, any(), any()) } answers
                { JournalSaveResult.Saved(yesterdays.copy(bodyMd = thirdArg<JournalDraft>().bodyMd, version = 2)) }
            coEvery { repo.uploadImage(photo) } coAnswers { upload.await() }
        }) { vm ->
            vm.setBody("Ferry to the island, windy")
            vm.previousDay()
            runCurrent()
            // The save of today runs; the day is still editable, so a photo can be picked.
            assertTrue(vm.uiState.value.editable)
            vm.addPhoto(photo)
            runCurrent()

            save.complete(JournalSaveResult.Saved(todays.copy(bodyMd = "Ferry to the island, windy", version = 2)))
            advanceUntilIdle()
            upload.complete(JournalResult.Ok(image))
            advanceUntilIdle()

            val state = vm.uiState.value
            if (state.date == yesterday) {
                assertEquals("Packed the bags", state.body)
                coVerify(exactly = 0) { repo.saveEntry(yesterday, any(), any()) }
            } else {
                assertEquals(today, state.date)
                assertTrue(state.body.contains(image))
            }
        }
    }

    @Test
    fun `a photo picked while another day is being opened is not uploaded and the user is told`() {
        val save = CompletableDeferred<JournalSaveResult>()
        test(stub = { coEvery { repo.saveEntry(today, todays, any()) } coAnswers { save.await() } }) { vm ->
            vm.setBody("Ferry to the island, windy")
            vm.previousDay()
            runCurrent()

            vm.addPhoto(photo)
            runCurrent()
            assertFalse(vm.uiState.value.uploading)
            assertEquals(JournalMessage.PHOTO_DAY_CHANGED, vm.uiState.value.message)
            coVerify(exactly = 0) { repo.uploadImage(any()) }

            save.complete(JournalSaveResult.Saved(todays.copy(bodyMd = "Ferry to the island, windy", version = 2)))
            advanceUntilIdle()
            assertEquals(yesterday, vm.uiState.value.date)
            assertEquals("Packed the bags", vm.uiState.value.body)
        }
    }

    @Test
    fun `a photo read for a day that is no longer shown is not added to the shown day`() =
        test { vm ->
            vm.previousDay()
            advanceUntilIdle()
            assertEquals(yesterday, vm.uiState.value.date)

            vm.addPhoto(photo, at = 3, date = today)
            advanceUntilIdle()

            assertEquals("Packed the bags", vm.uiState.value.body)
            assertEquals(JournalMessage.PHOTO_DAY_CHANGED, vm.uiState.value.message)
            coVerify(exactly = 0) { repo.uploadImage(any()) }
            coVerify(exactly = 0) { repo.saveEntry(any(), any(), any()) }
        }

    @Test
    fun `an entry saved while the month loads stays marked when the month answers without it`() {
        val november = YearMonth.of(2026, 11)
        val monthLoad = CompletableDeferred<JournalResult<Map<LocalDate, Int?>>>()
        test(stub = {
            coEvery { repo.get(today) } returns JournalResult.Ok(null)
            coEvery { repo.days(november.atDay(1), november.atEndOfMonth()) } coAnswers { monthLoad.await() }
            coEvery { repo.saveEntry(today, null, any()) } answers
                { JournalSaveResult.Saved(entry(today, "", mood = thirdArg<JournalDraft>().mood)) }
        }) { vm ->
            assertTrue(vm.uiState.value.monthLoading)
            vm.setMood(4)
            advanceUntilIdle()
            assertEquals(4, vm.uiState.value.monthDays[today])

            // The server computed the month before the save.
            monthLoad.complete(JournalResult.Ok(emptyMap()))
            advanceUntilIdle()

            assertEquals(mapOf(today to 4), vm.uiState.value.monthDays)
            assertFalse(vm.uiState.value.monthLoading)
        }
    }

    @Test
    fun `a month answering after a later month was asked for is ignored`() {
        val october = YearMonth.of(2026, 10)
        val september = YearMonth.of(2026, 9)
        val octoberLoad = CompletableDeferred<JournalResult<Map<LocalDate, Int?>>>()
        test(stub = {
            coEvery { repo.days(october.atDay(1), october.atEndOfMonth()) } coAnswers { octoberLoad.await() }
            coEvery { repo.days(september.atDay(1), september.atEndOfMonth()) } returns
                JournalResult.Ok(mapOf(september.atDay(12) to 2))
        }) { vm ->
            vm.showMonth(october)
            runCurrent()
            assertTrue(vm.uiState.value.monthLoading)

            vm.showMonth(september)
            advanceUntilIdle()
            octoberLoad.complete(JournalResult.Ok(mapOf(october.atDay(5) to 5)))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(september, state.month)
            assertEquals(mapOf(september.atDay(12) to 2), state.monthDays)
            assertFalse(state.monthLoading)
        }
    }

    @Test
    fun `a day opened in another month while a browsed month loads shows its own month`() {
        val september = YearMonth.of(2026, 9)
        val october = YearMonth.of(2026, 10)
        val septemberLoad = CompletableDeferred<JournalResult<Map<LocalDate, Int?>>>()
        test(stub = {
            coEvery { repo.days(september.atDay(1), september.atEndOfMonth()) } coAnswers { septemberLoad.await() }
            coEvery { repo.days(october.atDay(1), october.atEndOfMonth()) } returns JournalResult.Ok(mapOf(yesterday to 4))
        }) { vm ->
            vm.showMonth(september)
            runCurrent()
            vm.previousDay()
            advanceUntilIdle()
            septemberLoad.complete(JournalResult.Ok(mapOf(september.atDay(3) to 1)))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(october, state.month)
            assertEquals(mapOf(yesterday to 4), state.monthDays)
            assertFalse(state.monthLoading)
        }
    }

    @Test
    fun `a day opened in the month the calendar shows keeps its marks without reloading`() {
        val november = YearMonth.of(2026, 11)
        val earlier = LocalDate.of(2026, 10, 9)
        test(stub = {
            coEvery { repo.get(earlier) } returns JournalResult.Ok(null)
            coEvery { repo.days(any(), any()) } returns JournalResult.Ok(mapOf(LocalDate.of(2026, 10, 9) to 2))
        }) { vm ->
            vm.showMonth(YearMonth.of(2026, 10))
            advanceUntilIdle()
            vm.openDay(earlier)
            advanceUntilIdle()

            assertEquals(mapOf(earlier to 2), vm.uiState.value.monthDays)
            coVerify(exactly = 1) { repo.days(november.atDay(1), november.atEndOfMonth()) }
            coVerify(exactly = 1) { repo.days(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)) }
        }
    }

    @Test
    fun `a save is not marked in a different month the calendar was browsed to, and that month reloads later`() =
        test(stub = {
            coEvery { repo.saveEntry(yesterday, yesterdays, any()) } answers
                { JournalSaveResult.Saved(yesterdays.copy(mood = thirdArg<JournalDraft>().mood, version = 2)) }
        }) { vm ->
            vm.previousDay()
            advanceUntilIdle()
            assertEquals(YearMonth.of(2026, 10), vm.uiState.value.month)

            val september = YearMonth.of(2026, 9)
            vm.showMonth(september)
            vm.setMood(1)
            advanceUntilIdle()

            assertEquals(september, vm.uiState.value.month)
            assertFalse(yesterday in vm.uiState.value.monthDays)
            coEvery { repo.days(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)) } returns
                JournalResult.Ok(mapOf(yesterday to 1))
            vm.showMonth(YearMonth.of(2026, 10))
            advanceUntilIdle()
            assertEquals(mapOf(yesterday to 1), vm.uiState.value.monthDays)
        }

    @Test
    fun `clearing the mood keeps the day marked as having an entry`() =
        test(stub = {
            coEvery { repo.days(any(), any()) } returns JournalResult.Ok(mapOf(today to 3))
            coEvery { repo.saveEntry(today, any(), any()) } answers
                { JournalSaveResult.Saved(todays.copy(mood = thirdArg<JournalDraft>().mood, version = 2)) }
        }) { vm ->
            advanceUntilIdle()
            vm.setMood(3)
            advanceUntilIdle()
            assertNull(vm.uiState.value.mood)
            assertTrue(today in vm.uiState.value.monthDays)
            assertNull(vm.uiState.value.monthDays[today])
        }

    @Test
    fun `tags typed before a conflict are the tags kept, and none can be typed while it waits`() {
        val theirs = todays.copy(bodyMd = "From the web", tags = listOf("web"), version = 6)
        test(stub = {
            coEvery { repo.saveEntry(today, todays, any()) } returns JournalSaveResult.Conflict(theirs)
            coEvery { repo.saveEntry(today, theirs, any()) } answers {
                val draft = thirdArg<JournalDraft>()
                JournalSaveResult.Saved(theirs.copy(bodyMd = draft.bodyMd, mood = draft.mood, tags = draft.tags, version = 7))
            }
        }) { vm ->
            vm.setTags("trip, #Sea")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.conflict != null)

            vm.setTags("ignored")
            assertEquals("trip, #Sea", vm.uiState.value.tagsText)
            assertEquals(listOf("trip", "Sea"), vm.uiState.value.tags)
            vm.setMood(5)
            assertEquals(3, vm.uiState.value.mood)

            vm.keepMine()
            advanceUntilIdle()
            coVerify(exactly = 1) {
                repo.saveEntry(today, theirs, JournalDraft("Ferry to the island", 3, listOf("trip", "Sea")))
            }
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            vm.tidyTags()
            assertEquals("trip, Sea", vm.uiState.value.tagsText)
        }
    }

    @Test
    fun `tags that only differ in order or case from the saved ones send nothing`() =
        test { vm ->
            vm.setTags("TRIP")
            advanceUntilIdle()
            assertEquals(SaveStatus.SAVED, vm.uiState.value.status)
            coVerify(exactly = 0) { repo.saveEntry(any(), any(), any()) }
        }

    @Test
    fun `clearing the tags field saves the entry without tags`() =
        test(stub = {
            coEvery { repo.saveEntry(today, todays, any()) } answers
                { JournalSaveResult.Saved(todays.copy(tags = emptyList(), version = 2)) }
        }) { vm ->
            vm.setTags(" , ")
            assertEquals(emptyList<String>(), vm.uiState.value.tags)
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.saveEntry(today, todays, JournalDraft("Ferry to the island", 3, emptyList())) }
            vm.tidyTags()
            assertEquals("", vm.uiState.value.tagsText)
        }

    @Test
    fun `mood and tags cannot be changed while the day loads`() {
        val load = CompletableDeferred<JournalResult<JournalEntry?>>()
        test(stub = { coEvery { repo.get(today) } coAnswers { load.await() } }) { vm ->
            vm.setMood(2)
            vm.setTags("blocked")
            assertNull(vm.uiState.value.mood)
            assertEquals("", vm.uiState.value.tagsText)
            load.complete(JournalResult.Ok(todays))
            advanceUntilIdle()
            assertEquals(3, vm.uiState.value.mood)
            assertEquals("trip", vm.uiState.value.tagsText)
            coVerify(exactly = 0) { repo.saveEntry(any(), any(), any()) }
        }
    }

    /**
     * The same day asked for twice (today, yesterday, today again): the earlier-years list must show the answer of
     * the latest request, not a stale one that answers last.
     */
    @Test
    fun `a stale on-this-day answer for the same day does not replace the newer one`() {
        val first = CompletableDeferred<JournalResult<List<JournalEntry>>>()
        val second = CompletableDeferred<JournalResult<List<JournalEntry>>>()
        val answers = ArrayDeque(listOf(first, second))
        val lastYear = entry(today.minusYears(1), "Island last year")
        test(stub = { coEvery { repo.onThisDay(today) } coAnswers { answers.removeFirst().await() } }) { vm ->
            vm.previousDay()
            advanceUntilIdle()
            vm.nextDay()
            advanceUntilIdle()
            assertEquals(today, vm.uiState.value.date)

            second.complete(JournalResult.Ok(listOf(lastYear)))
            advanceUntilIdle()
            assertEquals(listOf(lastYear), vm.uiState.value.onThisDay)

            first.complete(JournalResult.Failed(500))
            advanceUntilIdle()
            assertFalse(vm.uiState.value.onThisDayFailed)
            assertEquals(listOf(lastYear), vm.uiState.value.onThisDay)
        }
    }

    @Test
    fun `a retried load also reloads the earlier years`() =
        test(stub = {
            coEvery { repo.get(today) } returnsMany listOf(JournalResult.Offline, JournalResult.Ok(todays))
            coEvery { repo.onThisDay(today) } returnsMany
                listOf(JournalResult.Offline, JournalResult.Ok(listOf(entry(today.minusYears(2), "Two years back"))))
        }) { vm ->
            advanceUntilIdle()
            assertTrue(vm.uiState.value.onThisDayFailed)
            vm.retryLoad()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.onThisDayFailed)
            assertEquals(
                listOf("Two years back"),
                vm.uiState.value.onThisDay
                    .map { it.bodyMd },
            )
        }

    @Test
    fun `after midnight Today opens the new day and the day before stays editable`() =
        runTest(dispatcher) {
            val nextDay = LocalDate.of(2026, 11, 2)
            val clock = MovableClock(Instant.parse("2026-11-01T22:30:00Z"))
            coEvery { repo.get(nextDay) } returns JournalResult.Ok(null)
            val vm = JournalViewModel(repo, images, CoroutineScope(dispatcher), clock)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.canGoForward)

            // 00:30 in Zurich on 2 November.
            clock.now = Instant.parse("2026-11-01T23:30:00Z")
            vm.nextDay()
            advanceUntilIdle()
            assertEquals(nextDay, vm.uiState.value.date)
            assertEquals(nextDay, vm.uiState.value.today)
            assertTrue(vm.uiState.value.isToday)

            vm.previousDay()
            advanceUntilIdle()
            assertEquals(today, vm.uiState.value.date)
            assertTrue(vm.uiState.value.editable)
            assertTrue(vm.uiState.value.canGoForward)
        }

    /** A clock whose instant the test moves; in Zurich's zone. */
    private class MovableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("Europe/Zurich")

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }
}
