package ch.sonensei.cockpit.calendar.agenda

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The agenda cache in Room: what is stored, appended, read back, and dropped by a logout. */
@RunWith(AndroidJUnit4::class)
class AgendaCacheTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val database =
        Room
            .inMemoryDatabaseBuilder(context, AgendaCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val cache = RoomAgendaCache(database)

    private val day = LocalDate.parse("2026-10-21")
    private val loadedAt = Instant.parse("2026-10-21T08:00:00Z")

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", 0xFF336699, visible = true, readOnly = false, version = 3)
    private val holidays = CalendarInfo(id(91), "Holidays", null, visible = false, readOnly = true, version = 1)

    private val meeting =
        Occurrence(
            eventId = id(1),
            calendarId = work.id,
            title = "Planning",
            location = "Room 4",
            allDay = false,
            start = Instant.parse("2026-10-21T09:00:00.123Z"),
            end = Instant.parse("2026-10-21T10:00:00Z"),
            startDate = null,
            endDate = null,
            seriesId = id(5),
            occurrence = "2026-10-21T09:00:00Z",
            version = 7,
        )
    private val trip = Occurrence(id(2), work.id, "Trip", null, true, null, null, day.plusDays(29), day.plusDays(32))
    private val later = Occurrence(id(3), work.id, "Later", null, true, null, null, day.plusDays(40), day.plusDays(41))

    @After
    fun tearDown() = database.close()

    @Test
    fun `a stored agenda reads back field for field, in order`() =
        runBlocking {
            cache.store(cache.session(), CachedAgenda(day, day.plusDays(30), listOf(work, holidays), listOf(meeting, trip), loadedAt))

            assertEquals(CachedAgenda(day, day.plusDays(30), listOf(work, holidays), listOf(meeting, trip), loadedAt), cache.read())
        }

    @Test
    fun `nothing stored reads as null`() =
        runBlocking {
            assertNull(cache.read())
        }

    @Test
    fun `a new store replaces the cached agenda`() =
        runBlocking {
            cache.store(cache.session(), CachedAgenda(day, day.plusDays(30), listOf(work, holidays), listOf(meeting, trip), loadedAt))
            val next = CachedAgenda(day.plusDays(1), day.plusDays(31), listOf(work), listOf(later), loadedAt.plusSeconds(3600))

            cache.store(cache.session(), next)

            assertEquals(next, cache.read())
        }

    @Test
    fun `days loaded on scrolling are appended once, keeping the first load time`() =
        runBlocking {
            cache.store(cache.session(), CachedAgenda(day, day.plusDays(30), listOf(work), listOf(meeting, trip), loadedAt))

            // The trip overlaps both chunks: it is in both answers but cached once, in its first place.
            cache.append(cache.session(), day.plusDays(30), day.plusDays(60), listOf(trip, later))

            assertEquals(CachedAgenda(day, day.plusDays(60), listOf(work), listOf(meeting, trip, later), loadedAt), cache.read())
        }

    @Test
    fun `days that do not continue the cached ones are not appended`() =
        runBlocking {
            val stored = CachedAgenda(day, day.plusDays(30), listOf(work), listOf(meeting), loadedAt)
            cache.store(cache.session(), stored)

            cache.append(cache.session(), day.plusDays(31), day.plusDays(61), listOf(later))

            assertEquals(stored, cache.read())
        }

    @Test
    fun `days appended to an empty cache are not stored`() =
        runBlocking {
            cache.append(cache.session(), day, day.plusDays(30), listOf(later))

            assertNull(cache.read())
        }

    @Test
    fun `a logout drops the cache and the writes of loads started before it`() =
        runBlocking {
            val before = cache.session()
            cache.store(before, CachedAgenda(day, day.plusDays(30), listOf(work), listOf(meeting), loadedAt))

            cache.clear()
            assertNull(cache.read())

            // A load that started before the logout answers afterwards: its data belongs to the previous session.
            cache.store(before, CachedAgenda(day, day.plusDays(30), listOf(work), listOf(meeting), loadedAt))
            assertNull(cache.read())

            val after = cache.session()
            cache.store(after, CachedAgenda(day, day.plusDays(30), listOf(holidays), emptyList(), loadedAt))
            assertEquals(listOf(holidays), cache.read()?.calendars)
        }

    @Test
    fun `the session cache contributed for the logout clears the Room cache`() =
        runBlocking {
            cache.store(cache.session(), CachedAgenda(day, day.plusDays(30), listOf(work), listOf(meeting), loadedAt))

            AgendaCacheModule.agendaSessionCache(cache).clear()

            assertNull(cache.read())
        }

    @Test
    fun `days loaded on scrolling after a logout are not appended`() =
        runBlocking {
            val before = cache.session()
            cache.store(before, CachedAgenda(day, day.plusDays(30), listOf(work), listOf(meeting), loadedAt))
            cache.clear()
            val after = cache.session()
            cache.store(after, CachedAgenda(day, day.plusDays(30), listOf(holidays), emptyList(), loadedAt))

            // A chunk requested before the logout continues the new cache's days, but belongs to the previous session.
            cache.append(before, day.plusDays(30), day.plusDays(60), listOf(later))

            assertEquals(CachedAgenda(day, day.plusDays(30), listOf(holidays), emptyList(), loadedAt), cache.read())
        }

    @Test
    fun `a write that fails leaves the cached agenda as it was and does not throw`() =
        runBlocking {
            val stored = CachedAgenda(day, day.plusDays(30), listOf(work, holidays), listOf(meeting), loadedAt)
            cache.store(cache.session(), stored)
            // Every new occurrence row fails, as on a full disk; the calendars were already replaced in the same write.
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER no_space BEFORE INSERT ON cached_occurrence BEGIN SELECT RAISE(ABORT, 'database or disk is full'); END",
            )

            cache.store(cache.session(), CachedAgenda(day, day.plusDays(30), listOf(holidays), listOf(trip), loadedAt.plusSeconds(60)))
            cache.append(cache.session(), day.plusDays(30), day.plusDays(60), listOf(later))

            assertEquals(stored, cache.read())
        }

    @Test
    fun `a cache that cannot be read reads as null and is not written`() =
        runBlocking {
            cache.store(cache.session(), CachedAgenda(day, day.plusDays(30), listOf(work), listOf(meeting), loadedAt))
            database.openHelper.writableDatabase.execSQL("DROP TABLE cached_occurrence")

            assertNull(cache.read())
            cache.store(cache.session(), CachedAgenda(day, day.plusDays(30), listOf(work), listOf(meeting), loadedAt))
            cache.append(cache.session(), day.plusDays(30), day.plusDays(60), listOf(later))
            assertNull(cache.read())
        }

    @Test
    fun `cached rows that cannot be parsed read as null`() =
        runBlocking {
            cache.store(cache.session(), CachedAgenda(day, day.plusDays(30), listOf(work), listOf(meeting), loadedAt))
            val db = database.openHelper.writableDatabase

            db.execSQL("UPDATE cached_occurrence SET eventId = 'not-a-uuid'")
            assertNull(cache.read())

            cache.store(cache.session(), CachedAgenda(day, day.plusDays(30), listOf(work), listOf(meeting), loadedAt))
            db.execSQL("UPDATE agenda_cache_meta SET fromDate = 'not-a-date'")
            assertNull(cache.read())

            cache.store(cache.session(), CachedAgenda(day, day.plusDays(30), listOf(work), listOf(meeting), loadedAt))
            db.execSQL("UPDATE cached_occurrence SET start = 'yesterday'")
            assertNull(cache.read())
        }
}
