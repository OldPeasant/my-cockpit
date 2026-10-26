package ch.sonensei.cockpit.newitems

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.newitems.NewTestData.mail
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** The remembered filter (app preferences), the in-memory mail cache and what is open on top of the list. */
@RunWith(AndroidJUnit4::class)
class NewStoresTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val cached = CachedMail(listOf(mail("Invoice", "2026-10-20T09:00:00Z", 3)), "page-2", Instant.parse("2026-10-20T12:00:00Z"))

    @Before
    fun clearPreferences() = context.getSharedPreferences("new_view", Context.MODE_PRIVATE).edit { clear() }

    @Test
    fun theFilterIsAllUntilOneIsChosen() {
        assertEquals(NewFilter.ALL, PreferencesNewFilterStore(context).read())
    }

    @Test
    fun aChosenFilterIsReadBackByANewStore() {
        PreferencesNewFilterStore(context).write(NewFilter.MAIL)

        assertEquals(NewFilter.MAIL, PreferencesNewFilterStore(context).read())

        PreferencesNewFilterStore(context).write(NewFilter.INBOX)
        assertEquals(NewFilter.INBOX, PreferencesNewFilterStore(context).read())
    }

    @Test
    fun anUnknownStoredFilterFallsBackToAll() {
        context.getSharedPreferences("new_view", Context.MODE_PRIVATE).edit { putString("filter", "ARCHIVE") }

        assertEquals(NewFilter.ALL, PreferencesNewFilterStore(context).read())
    }

    @Test
    fun theModuleHandsOutThePreferencesStore() {
        val store = PreferencesNewFilterStore(context)

        assertEquals(store, NewItemsModule.filterStore(store))
    }

    @Test
    fun aLogoutDropsTheCachedMail() =
        runTest {
            val cache = NewMailCache()
            cache.store(cache.session(), cached)

            NewItemsModule.newMailSessionCache(cache).clear()

            assertNull(cache.last)
        }

    @Test
    fun aWriteFromBeforeALogoutIsDropped() {
        val cache = NewMailCache()
        val before = cache.session()

        cache.clear()
        cache.store(before, cached)

        assertNull(cache.last)
        cache.store(cache.session(), cached)
        assertEquals(cached, cache.last)
    }

    @Test
    fun malformedStoredFiltersFallBackToAll() {
        listOf("", "mail", " MAIL", "INBOX,MAIL").forEach { value ->
            context.getSharedPreferences("new_view", Context.MODE_PRIVATE).edit { putString("filter", value) }

            assertEquals(value, NewFilter.ALL, PreferencesNewFilterStore(context).read())
        }
    }

    @Test
    fun whatIsOpenSurvivesEncoding() {
        val id = UUID.fromString("00000000-0000-4000-9000-000000000003")

        assertEquals(NewOpen.Task(id), NewOpen.parse(NewOpen.Task(id).encode()))
        assertEquals(NewOpen.Mail(id), NewOpen.parse(NewOpen.Mail(id).encode()))
    }

    @Test
    fun malformedOpenValuesAreIgnored() {
        listOf("", "task", "task:", "task:not-a-uuid", "note:00000000-0000-4000-9000-000000000003").forEach {
            assertNull(it, NewOpen.parse(it))
        }
    }
}
