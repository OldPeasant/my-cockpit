package ch.sonensei.cockpit.calendar.agenda

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** The agenda's offline read cache in the view model: what is stored, and how cached events are shown (SPEC.md §9.3). */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelCacheTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<CalendarRepository>()
    private val zone = ZoneId.of("Europe/Zurich")

    // Wednesday 21 October 2026, 10:00 in Zurich.
    private val now = Instant.parse("2026-10-21T08:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val today = LocalDate.parse("2026-10-21")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", null, true, false, 3)

    private fun event(
        n: Int,
        date: LocalDate,
    ) = Occurrence(id(n), work.id, "Event $n", null, true, null, null, date, date.plusDays(1))

    /** An [AgendaCache] in memory that records what the view model writes. */
    private class FakeCache(
        var agenda: CachedAgenda? = null,
    ) : AgendaCache {
        val appends = mutableListOf<Triple<LocalDate, LocalDate, List<Occurrence>>>()

        override suspend fun session() = 0L

        override suspend fun store(
            session: Long,
            agenda: CachedAgenda,
        ) {
            this.agenda = agenda
        }

        override suspend fun append(
            session: Long,
            from: LocalDate,
            to: LocalDate,
            occurrences: List<Occurrence>,
        ) {
            appends += Triple(from, to, occurrences)
        }

        override suspend fun read() = agenda
    }

    private fun viewModel(cache: AgendaCache) = CalendarViewModel(repo, clock, Locale.GERMANY, cache)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(work))
        coEvery { repo.occurrences(any(), any(), any()) } returns CalendarResult.Ok(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a loaded agenda and the days loaded on scrolling are cached`() =
        runTest(dispatcher) {
            val cache = FakeCache()
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returns CalendarResult.Ok(listOf(event(1, today)))
            coEvery { repo.occurrences(today.plusDays(30), today.plusDays(60), zone) } returns
                CalendarResult.Ok(listOf(event(2, today.plusDays(40))))
            val vm = viewModel(cache)

            vm.refresh()
            advanceUntilIdle()
            assertEquals(CachedAgenda(today, today.plusDays(30), listOf(work), listOf(event(1, today)), now), cache.agenda)

            vm.loadMore()
            advanceUntilIdle()
            assertEquals(listOf(Triple(today.plusDays(30), today.plusDays(60), listOf(event(2, today.plusDays(40))))), cache.appends)
        }

    @Test
    fun `day and week loads and failed loads are not cached`() =
        runTest(dispatcher) {
            val cache = FakeCache()
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returns CalendarResult.Failed(400)
            val vm = viewModel(cache)
            vm.refresh()
            advanceUntilIdle()
            vm.select(CalendarView.DAY)
            advanceUntilIdle()
            vm.select(CalendarView.WEEK)
            advanceUntilIdle()

            assertNull(cache.agenda)
        }

    @Test
    fun `offline, the agenda shows the cached days from today on, read-only`() =
        runTest(dispatcher) {
            val loadedAt = now.minusSeconds(86_400)
            val yesterday = today.minusDays(1)
            val cache =
                FakeCache(
                    CachedAgenda(
                        yesterday,
                        yesterday.plusDays(60),
                        listOf(work),
                        listOf(event(1, yesterday), event(2, today), event(3, today.plusDays(45))),
                        loadedAt,
                    ),
                )
            coEvery { repo.calendars() } returns CalendarResult.Offline
            coEvery { repo.occurrences(any(), any(), any()) } returns CalendarResult.Offline
            val vm = viewModel(cache)

            vm.refresh()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(listOf(event(2, today), event(3, today.plusDays(45))), state.occurrences)
            assertEquals(today, state.from)
            assertEquals(yesterday.plusDays(60), state.to)
            assertEquals(listOf(work), state.calendars)
            assertEquals(loadedAt, state.cachedAt)
            assertEquals(CalendarMessage.OFFLINE, state.message)
            assertFalse(state.loaded)
            assertTrue(state.shown)
            assertFalse(state.loading)

            // Read-only: no more days, no visibility change.
            vm.loadMore()
            vm.setVisible(work.id, false)
            advanceUntilIdle()
            coVerify(exactly = 0) { repo.occurrences(yesterday.plusDays(60), any(), any()) }
            coVerify(exactly = 0) { repo.setVisible(any(), any()) }
            assertTrue(
                vm.uiState.value.calendars
                    .single()
                    .visible,
            )
        }

    @Test
    fun `the next successful load replaces the cached events and stores them`() =
        runTest(dispatcher) {
            val cache = FakeCache(CachedAgenda(today, today.plusDays(30), listOf(work), listOf(event(1, today)), now.minusSeconds(60)))
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returnsMany
                listOf(CalendarResult.Offline, CalendarResult.Ok(listOf(event(2, today))))
            val vm = viewModel(cache)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(listOf(event(1, today)), vm.uiState.value.occurrences)

            vm.refresh()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(listOf(event(2, today)), state.occurrences)
            assertNull(state.cachedAt)
            assertNull(state.message)
            assertTrue(state.loaded)
            assertEquals(listOf(event(2, today)), cache.agenda?.occurrences)
        }

    @Test
    fun `offline after a load keeps the loaded events rather than the cache`() =
        runTest(dispatcher) {
            val cache = FakeCache()
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returnsMany
                listOf(CalendarResult.Ok(listOf(event(2, today))), CalendarResult.Offline)
            val vm = viewModel(cache)
            vm.refresh()
            advanceUntilIdle()
            cache.agenda = CachedAgenda(today, today.plusDays(30), listOf(work), listOf(event(1, today)), now.minusSeconds(60))

            vm.refresh()
            advanceUntilIdle()

            assertEquals(listOf(event(2, today)), vm.uiState.value.occurrences)
            assertNull(vm.uiState.value.cachedAt)
            assertEquals(CalendarMessage.OFFLINE, vm.uiState.value.message)
        }

    @Test
    fun `offline, a day or week inside the cached days is shown from the cache, one outside is not`() =
        runTest(dispatcher) {
            val cache =
                FakeCache(CachedAgenda(today, today.plusDays(30), listOf(work), listOf(event(1, today), event(2, today.plusDays(1))), now))
            coEvery { repo.occurrences(any(), any(), any()) } returns CalendarResult.Offline
            val vm = viewModel(cache)
            vm.refresh()
            advanceUntilIdle()

            vm.select(CalendarView.DAY)
            advanceUntilIdle()
            assertEquals(listOf(event(1, today)), vm.uiState.value.occurrences)
            assertEquals(today.plusDays(1), vm.uiState.value.to)
            assertEquals(now, vm.uiState.value.cachedAt)

            // The week of 19 October starts before the cached days.
            vm.select(CalendarView.WEEK)
            advanceUntilIdle()
            assertEquals(emptyList<Occurrence>(), vm.uiState.value.occurrences)
            assertNull(vm.uiState.value.cachedAt)
            assertEquals(CalendarMessage.OFFLINE, vm.uiState.value.message)
        }

    @Test
    fun `offline without a cache shows nothing and says why`() =
        runTest(dispatcher) {
            coEvery { repo.occurrences(any(), any(), any()) } returns CalendarResult.Offline
            val vm = viewModel(FakeCache())

            vm.refresh()
            advanceUntilIdle()

            assertEquals(emptyList<Occurrence>(), vm.uiState.value.occurrences)
            assertNull(vm.uiState.value.cachedAt)
            assertFalse(vm.uiState.value.shown)
            assertEquals(CalendarMessage.OFFLINE, vm.uiState.value.message)
        }

    @Test
    fun `a refused load does not fall back to the cache`() =
        runTest(dispatcher) {
            val cache = FakeCache(CachedAgenda(today, today.plusDays(30), listOf(work), listOf(event(1, today)), now))
            coEvery { repo.occurrences(any(), any(), any()) } returns CalendarResult.Failed(403)
            val vm = viewModel(cache)

            vm.refresh()
            advanceUntilIdle()

            assertEquals(emptyList<Occurrence>(), vm.uiState.value.occurrences)
            assertNull(vm.uiState.value.cachedAt)
            assertEquals(CalendarMessage.FAILED, vm.uiState.value.message)
        }

    /** A clock that can be moved to a later day while the view model is open. */
    private class MovableClock(
        var now: Instant,
        private val zone: ZoneId,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MovableClock(now, zone)

        override fun instant(): Instant = now
    }

    @Test
    fun `an agenda refreshed on a later day replaces the cache with the days from the new today`() =
        runTest(dispatcher) {
            val cache = FakeCache()
            val movable = MovableClock(now, zone)
            val tomorrow = today.plusDays(1)
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returns CalendarResult.Ok(listOf(event(1, today)))
            coEvery { repo.occurrences(tomorrow, tomorrow.plusDays(30), zone) } returns
                CalendarResult.Ok(listOf(event(2, tomorrow.plusDays(1))))
            val vm = CalendarViewModel(repo, movable, Locale.GERMANY, cache)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(today, cache.agenda?.from)

            val later = now.plusSeconds(86_400)
            movable.now = later
            vm.refresh()
            advanceUntilIdle()

            val expected = CachedAgenda(tomorrow, tomorrow.plusDays(30), listOf(work), listOf(event(2, tomorrow.plusDays(1))), later)
            assertEquals(expected, cache.agenda)
        }

    @Test
    fun `offline, a cache that ends before today is not shown, one ending tomorrow shows today`() =
        runTest(dispatcher) {
            coEvery { repo.calendars() } returns CalendarResult.Offline
            coEvery { repo.occurrences(any(), any(), any()) } returns CalendarResult.Offline
            val endsToday = CachedAgenda(today.minusDays(30), today, listOf(work), listOf(event(1, today.minusDays(1))), now)
            val endsTomorrow = CachedAgenda(today.minusDays(29), today.plusDays(1), listOf(work), listOf(event(2, today)), now)
            val past = viewModel(FakeCache(endsToday))
            val lastDay = viewModel(FakeCache(endsTomorrow))

            past.refresh()
            lastDay.refresh()
            advanceUntilIdle()

            assertNull(past.uiState.value.cachedAt)
            assertFalse(past.uiState.value.shown)
            assertEquals(emptyList<Occurrence>(), past.uiState.value.occurrences)
            assertEquals(CalendarMessage.OFFLINE, past.uiState.value.message)

            assertEquals(now, lastDay.uiState.value.cachedAt)
            assertEquals(listOf(event(2, today)), lastDay.uiState.value.occurrences)
            assertEquals(today.plusDays(1), lastDay.uiState.value.to)
        }

    @Test
    fun `offline, a week fully inside the cached days shows its cached events only`() =
        runTest(dispatcher) {
            // The week of Monday 19 October; the cache starts on that Monday.
            val monday = LocalDate.parse("2026-10-19")
            val nextMonday = monday.plusDays(7)
            val cache =
                FakeCache(
                    CachedAgenda(
                        monday,
                        monday.plusDays(30),
                        listOf(work),
                        listOf(event(1, monday), event(2, nextMonday.minusDays(1)), event(3, nextMonday)),
                        now.minusSeconds(3_600),
                    ),
                )
            coEvery { repo.calendars() } returns CalendarResult.Offline
            coEvery { repo.occurrences(any(), any(), any()) } returns CalendarResult.Offline
            val vm = viewModel(cache)
            vm.refresh()
            advanceUntilIdle()

            vm.select(CalendarView.WEEK)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(monday, state.from)
            assertEquals(nextMonday, state.to)
            assertEquals(listOf(event(1, monday), event(2, nextMonday.minusDays(1))), state.occurrences)
            assertEquals(now.minusSeconds(3_600), state.cachedAt)
            assertEquals(CalendarMessage.OFFLINE, state.message)
            // Offline loads leave the cache as it was.
            assertEquals(monday, cache.agenda?.from)
        }

    @Test
    fun `an agenda loaded without the calendar list keeps the cached calendars`() =
        runTest(dispatcher) {
            val cache = FakeCache(CachedAgenda(today, today.plusDays(30), listOf(work), listOf(event(1, today)), now.minusSeconds(60)))
            coEvery { repo.calendars() } returns CalendarResult.Offline
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returns CalendarResult.Ok(listOf(event(2, today)))
            val vm = viewModel(cache)

            vm.refresh()
            advanceUntilIdle()

            assertEquals(CachedAgenda(today, today.plusDays(30), listOf(work), listOf(event(2, today)), now), cache.agenda)
        }

    @Test
    fun `an agenda loaded without the calendar list and without a cache stores no calendars`() =
        runTest(dispatcher) {
            val cache = FakeCache()
            coEvery { repo.calendars() } returns CalendarResult.Failed(500)
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returns CalendarResult.Ok(listOf(event(2, today)))
            val vm = viewModel(cache)

            vm.refresh()
            advanceUntilIdle()

            assertEquals(CachedAgenda(today, today.plusDays(30), emptyList(), listOf(event(2, today)), now), cache.agenda)
        }

    @Test
    fun `offline events with a loaded calendar list show the cached events with the loaded calendars`() =
        runTest(dispatcher) {
            val renamed = work.copy(name = "Office", visible = false, version = 4)
            val home = CalendarInfo(id(91), "Home", null, true, false, 1)
            val cache = FakeCache(CachedAgenda(today, today.plusDays(30), listOf(work), listOf(event(1, today)), now.minusSeconds(60)))
            coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(renamed, home))
            coEvery { repo.occurrences(any(), any(), any()) } returns CalendarResult.Offline
            val vm = viewModel(cache)

            vm.refresh()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(listOf(event(1, today)), state.occurrences)
            assertEquals(listOf(renamed, home), state.calendars)
            assertEquals(now.minusSeconds(60), state.cachedAt)
            assertEquals(CalendarMessage.OFFLINE, state.message)
            // Offline loads leave the cache as it was.
            assertEquals(listOf(work), cache.agenda?.calendars)
        }

    @Test
    fun `offline events without a calendar list keep the calendars loaded in this session over the cached ones`() =
        runTest(dispatcher) {
            val renamed = work.copy(name = "Office", version = 4)
            val cache = FakeCache()
            coEvery { repo.calendars() } returnsMany listOf(CalendarResult.Ok(listOf(renamed)), CalendarResult.Offline)
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returns CalendarResult.Ok(listOf(event(2, today)))
            coEvery { repo.occurrences(today, today.plusDays(1), zone) } returns CalendarResult.Offline
            val vm = viewModel(cache)
            vm.refresh()
            advanceUntilIdle()
            // An older cache (e.g. written by an earlier session) still holds the calendar as it was.
            cache.agenda = CachedAgenda(today, today.plusDays(30), listOf(work), listOf(event(1, today)), now.minusSeconds(60))

            vm.select(CalendarView.DAY)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(listOf(event(1, today)), state.occurrences)
            assertEquals(now.minusSeconds(60), state.cachedAt)
            assertEquals(listOf(renamed), state.calendars)
        }

    @Test
    fun `offline events with a loaded calendar list keep a visibility choice still being written`() =
        runTest(dispatcher) {
            val home = CalendarInfo(id(91), "Home", null, true, false, 1)
            val written = CompletableDeferred<CalendarResult<CalendarInfo>>()
            val cache = FakeCache()
            coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(work, home))
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returns CalendarResult.Ok(listOf(event(2, today)))
            coEvery { repo.occurrences(today, today.plusDays(1), zone) } returns CalendarResult.Offline
            coEvery { repo.setVisible(work, false) } coAnswers { written.await() }
            val vm = viewModel(cache)
            vm.refresh()
            advanceUntilIdle()
            cache.agenda = CachedAgenda(today, today.plusDays(30), listOf(work), listOf(event(1, today)), now.minusSeconds(60))
            vm.setVisible(work.id, false)
            advanceUntilIdle()

            // The calendar list answers with the version from before the change; the events come from the cache.
            vm.select(CalendarView.DAY)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(listOf(event(1, today)), state.occurrences)
            assertEquals(now.minusSeconds(60), state.cachedAt)
            assertEquals(listOf(work.copy(visible = false), home), state.calendars)
            assertEquals(mapOf(work.id to false), state.pendingVisibility)
            written.complete(CalendarResult.Offline)
            advanceUntilIdle()
        }

    @Test
    fun `days loaded on scrolling that fail are not appended to the cache`() =
        runTest(dispatcher) {
            val cache = FakeCache()
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returns CalendarResult.Ok(listOf(event(1, today)))
            coEvery { repo.occurrences(today.plusDays(30), today.plusDays(60), zone) } returnsMany
                listOf(CalendarResult.Offline, CalendarResult.Failed(500))
            val vm = viewModel(cache)
            vm.refresh()
            advanceUntilIdle()

            vm.loadMore()
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()

            assertEquals(emptyList<Triple<LocalDate, LocalDate, List<Occurrence>>>(), cache.appends)
            assertEquals(today.plusDays(30), cache.agenda?.to)
        }
}
