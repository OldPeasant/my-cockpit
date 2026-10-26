package ch.sonensei.cockpit.calendar.agenda

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.auth.SessionCache
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/** The agenda cache in the production Hilt graph: the view model's cache is the Room one, and a logout clears it. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class AgendaCacheGraphTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var cache: AgendaCache

    @Inject lateinit var roomCache: RoomAgendaCache

    @Inject lateinit var database: AgendaCacheDatabase

    @Inject lateinit var caches: Set<@JvmSuppressWildcards SessionCache>

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val day = LocalDate.parse("2026-10-21")
    private val work =
        CalendarInfo(UUID.fromString("00000000-0000-7000-8000-000000000090"), "Work", null, visible = true, readOnly = false, version = 1)

    @Before
    fun setUp() = hilt.inject()

    @After
    fun tearDown() = database.close()

    @Test
    fun `the agenda cache is the one Room cache`() {
        assertSame(roomCache, cache)
    }

    @Test
    fun `the session caches cleared on logout include the agenda cache`() =
        runBlocking {
            // The other session caches cancel WorkManager work.
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            val agenda = CachedAgenda(day, day.plusDays(30), listOf(work), emptyList(), Instant.parse("2026-10-21T08:00:00Z"))
            cache.store(cache.session(), agenda)
            assertEquals(agenda, cache.read())

            caches.forEach { it.clear() }

            assertNull(cache.read())
        }
}
