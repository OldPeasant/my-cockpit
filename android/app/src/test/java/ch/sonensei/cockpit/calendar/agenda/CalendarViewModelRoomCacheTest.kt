package ch.sonensei.cockpit.calendar.agenda

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * The view model writing to the real Room cache: an answer whose load started before a logout is not stored
 * (SPEC.md §9.3). The server answers are held back with [CompletableDeferred] so the logout falls inside the load.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CalendarViewModelRoomCacheTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database =
        Room
            .inMemoryDatabaseBuilder(context, AgendaCacheDatabase::class.java)
            .build()
    private val cache = RoomAgendaCache(database)
    private val repo = mockk<CalendarRepository>()

    private val zone = ZoneId.of("Europe/Zurich")
    private val now = Instant.parse("2026-10-21T08:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val today = LocalDate.parse("2026-10-21")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", null, true, false, 3)

    private fun event(
        n: Int,
        date: LocalDate,
    ) = Occurrence(id(n), work.id, "Event $n", null, true, null, null, date, date.plusDays(1))

    @Before
    fun setUp() {
        // Unconfined: the view model continues right where the held-back answer is completed, or on Room's threads.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(work))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    /** Waits (in real time: Room writes on its own threads) until [condition] holds. */
    private suspend fun awaitUntil(condition: suspend () -> Boolean) =
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }

    @Test
    fun `an agenda answer whose load started before a logout is not cached, the next load is`() =
        runBlocking {
            val answer = CompletableDeferred<CalendarResult<List<Occurrence>>>()
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } coAnswers { answer.await() } andThen
                CalendarResult.Ok(listOf(event(2, today)))
            val vm = CalendarViewModel(repo, clock, Locale.GERMANY, cache)

            vm.refresh()
            cache.clear()
            answer.complete(CalendarResult.Ok(listOf(event(1, today))))
            awaitUntil { vm.uiState.value.loaded && !vm.uiState.value.loading }

            // The answer is shown, but it belongs to the session before the logout: not cached.
            assertEquals(listOf(event(1, today)), vm.uiState.value.occurrences)
            assertNull(cache.read())

            vm.refresh()
            awaitUntil { vm.uiState.value.occurrences == listOf(event(2, today)) && !vm.uiState.value.loading }
            assertEquals(CachedAgenda(today, today.plusDays(30), listOf(work), listOf(event(2, today)), now), cache.read())
        }

    @Test
    fun `days loaded on scrolling whose load started before a logout are not cached`() =
        runBlocking {
            val chunk = CompletableDeferred<CalendarResult<List<Occurrence>>>()
            coEvery { repo.occurrences(today, today.plusDays(30), zone) } returns CalendarResult.Ok(listOf(event(1, today)))
            coEvery { repo.occurrences(today.plusDays(30), today.plusDays(60), zone) } coAnswers { chunk.await() }
            val vm = CalendarViewModel(repo, clock, Locale.GERMANY, cache)
            vm.refresh()
            awaitUntil { vm.uiState.value.loaded && !vm.uiState.value.loading }
            awaitUntil { cache.read() != null }

            vm.loadMore()
            cache.clear()
            // A new session caches the first days again before the old chunk answers: the chunk would continue them.
            cache.store(cache.session(), CachedAgenda(today, today.plusDays(30), listOf(work), emptyList(), now))
            chunk.complete(CalendarResult.Ok(listOf(event(2, today.plusDays(40)))))
            awaitUntil { !vm.uiState.value.loadingMore }

            assertEquals(today.plusDays(60), vm.uiState.value.to)
            assertEquals(CachedAgenda(today, today.plusDays(30), listOf(work), emptyList(), now), cache.read())
        }
}
