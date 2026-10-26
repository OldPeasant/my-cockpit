package ch.sonensei.cockpit.newitems

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import ch.sonensei.cockpit.auth.SessionCache
import ch.sonensei.cockpit.mail.list.MailSummary
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** The unified-inbox mails the New view loaded last, with the cursor of the page after them. */
data class CachedMail(
    val messages: List<MailSummary>,
    val nextCursor: String?,
    val loadedAt: Instant,
)

/**
 * The mails the New view showed last, so it can show them read-only while the server is unreachable. Kept in memory
 * only (mail is not cached on the device, SPEC.md §9.3), for as long as the app runs; a logout drops it.
 */
@Singleton
class NewMailCache
    @Inject
    constructor() {
        @Volatile
        var last: CachedMail? = null

        fun clear() {
            last = null
        }
    }

/** The filter the New view showed last, so it opens with it again. */
fun interface NewFilterStore {
    fun read(): NewFilter

    fun write(filter: NewFilter) = Unit
}

/** [NewFilterStore] in private app preferences. */
class PreferencesNewFilterStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : NewFilterStore {
        private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun read(): NewFilter = NewFilter.entries.firstOrNull { it.name == prefs.getString(KEY_FILTER, null) } ?: NewFilter.ALL

        override fun write(filter: NewFilter) = prefs.edit { putString(KEY_FILTER, filter.name) }

        private companion object {
            const val PREFS_NAME = "new_view"
            const val KEY_FILTER = "filter"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
object NewItemsModule {
    @Provides
    fun filterStore(store: PreferencesNewFilterStore): NewFilterStore = store

    /** The mails shown offline belong to the user: a logout drops them. */
    @Provides
    @IntoSet
    fun newMailSessionCache(cache: NewMailCache): SessionCache = SessionCache { cache.clear() }
}
