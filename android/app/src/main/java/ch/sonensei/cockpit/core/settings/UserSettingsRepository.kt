package ch.sonensei.cockpit.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.auth.SessionCache
import ch.sonensei.cockpit.core.network.generated.apis.SettingsApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What [UserSettingsRepository.refresh] did. */
enum class SettingsLoadResult { LOADED, OFFLINE, FAILED }

/** What [UserSettingsRepository.update] did. */
sealed interface SettingsUpdateResult {
    data object Updated : SettingsUpdateResult

    /** The settings changed elsewhere since they were read (409/412): they were loaded again, nothing was changed. */
    data object Conflict : SettingsUpdateResult

    /** The server refused a value (400) or the request (another 4xx). */
    data object Rejected : SettingsUpdateResult

    data object Offline : SettingsUpdateResult

    /** Nothing loaded yet, so there is no ETag to send; [UserSettingsRepository.refresh] first. */
    data object NotLoaded : SettingsUpdateResult
}

/** The last settings document the server sent, with its ETag, kept for an offline start. */
data class CachedSettings(
    val json: JsonObject,
    val etag: String?,
)

/** Where the last settings are kept between app starts. */
interface UserSettingsStore {
    suspend fun read(): CachedSettings?

    suspend fun write(settings: CachedSettings)

    suspend fun clear()
}

/**
 * The synced user settings for the whole app (SPEC.md §4.7): the last known value as a [StateFlow], read from the cache
 * at start and refreshed from the server on start, at each sign-in and whenever the app comes to the front (the server
 * sends no change event for settings). [update] writes with If-Match; a 412 loads the settings again.
 */
@Singleton
class UserSettingsRepository internal constructor(
    private val api: SettingsApi,
    private val store: UserSettingsStore,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(api: SettingsApi, store: UserSettingsStore) :
        this(api, store, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val state = MutableStateFlow<UserSettings?>(null)

    /** The current settings; `null` until the cache or the server delivered them (or after a logout). */
    val settings: StateFlow<UserSettings?> = state.asStateFlow()

    /** The ETag of the shown settings, for If-Match. */
    @Volatile private var etag: String? = null

    /** Serializes loads, writes and clearing, so an older answer never overwrites a newer one. */
    private val lock = Mutex()

    /** Counts clears; a load started before a logout does not bring the old user's settings back. */
    private var generation = 0L

    private val cacheRead =
        scope.launch {
            val cached =
                try {
                    store.read()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            lock.withLock {
                // A server answer that arrived first is newer than the cache.
                if (cached != null && state.value == null) apply(cached)
            }
        }

    /**
     * Refreshes at every new sign-in in [sessions] (`true` = signed in); started once by the app. The state at start is
     * skipped: the main screen refreshes whenever it comes to the front, and a refresh while signed out would only fail.
     */
    fun start(sessions: Flow<Boolean>) {
        scope.launch {
            sessions.distinctUntilChanged().drop(1).collect { signedIn -> if (signedIn) refresh() }
        }
    }

    /** Loads the settings from the server; on failure the last known ones stay. */
    suspend fun refresh(): SettingsLoadResult {
        cacheRead.join()
        val started = lock.withLock { generation }
        val response =
            try {
                api.getSettings()
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                return SettingsLoadResult.OFFLINE
            } catch (_: SerializationException) {
                return SettingsLoadResult.FAILED
            }
        return lock.withLock {
            if (started != generation) return@withLock SettingsLoadResult.FAILED
            accept(response)
        }
    }

    /** Sends [change] with the ETag of the shown settings. */
    suspend fun update(change: UserSettingsChange): SettingsUpdateResult {
        if (change.isEmpty) return SettingsUpdateResult.Updated
        cacheRead.join()
        val result =
            lock.withLock {
                val ifMatch = etag ?: return@withLock SettingsUpdateResult.NotLoaded
                val response =
                    try {
                        api.updateSettings(change.toJson(), ifMatch)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: IOException) {
                        return@withLock SettingsUpdateResult.Offline
                    } catch (_: SerializationException) {
                        return@withLock SettingsUpdateResult.Rejected
                    }
                when {
                    response.isSuccessful ->
                        if (accept(response) == SettingsLoadResult.LOADED) SettingsUpdateResult.Updated else SettingsUpdateResult.Rejected
                    response.code() in CONFLICTS -> SettingsUpdateResult.Conflict
                    response.code() >= SERVER_ERROR -> SettingsUpdateResult.Offline
                    else -> SettingsUpdateResult.Rejected
                }
            }
        // Outside the lock: refresh takes it itself.
        if (result == SettingsUpdateResult.Conflict) refresh()
        return result
    }

    /** Forgets the settings (logout): the next user starts from the defaults until their settings are loaded. */
    suspend fun clear() {
        cacheRead.join()
        lock.withLock {
            generation++
            state.value = null
            etag = null
            try {
                store.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not removed (storage error); the next sign-in overwrites it.
            }
        }
    }

    /** Takes a 200 answer; called under [lock]. */
    private suspend fun accept(response: Response<JsonObject>): SettingsLoadResult {
        if (!response.isSuccessful) {
            return if (response.code() >= SERVER_ERROR) SettingsLoadResult.OFFLINE else SettingsLoadResult.FAILED
        }
        val body = response.body() ?: return SettingsLoadResult.FAILED
        val cached = CachedSettings(body, response.headers()["ETag"])
        apply(cached)
        try {
            store.write(cached)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Not cached (e.g. the device is full); the settings still apply for this run.
        }
        return SettingsLoadResult.LOADED
    }

    private fun apply(cached: CachedSettings) {
        etag = cached.etag
        state.value = UserSettings.parse(cached.json)
    }

    private companion object {
        /** 409 changed concurrently, 412 stale ETag, 428 no ETag: all mean "read again first". */
        val CONFLICTS = setOf(409, 412, 428)
        const val SERVER_ERROR = 500
    }
}

/** Keeps the settings document as JSON text in its own DataStore file. */
internal class DataStoreUserSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : UserSettingsStore {
    override suspend fun read(): CachedSettings? {
        val prefs = dataStore.data.first()
        val text = prefs[JSON] ?: return null
        val json =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: return null
        return CachedSettings(json, prefs[ETAG])
    }

    override suspend fun write(settings: CachedSettings) {
        dataStore.edit { prefs ->
            prefs[JSON] = settings.json.toString()
            if (settings.etag != null) prefs[ETAG] = settings.etag else prefs.remove(ETAG)
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val JSON = stringPreferencesKey("settings_json")
        val ETAG = stringPreferencesKey("settings_etag")
    }
}

/** Signed in or not, as the token store says; for [UserSettingsRepository.start]. */
fun PersistentTokenStore.signedIn(): Flow<Boolean> = session.map { it != null }

@Module
@InstallIn(SingletonComponent::class)
object UserSettingsModule {
    @Provides
    @Singleton
    fun store(
        @ApplicationContext context: Context,
    ): UserSettingsStore =
        DataStoreUserSettingsStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { context.preferencesDataStoreFile("user_settings") },
            ),
        )

    /** The settings belong to the user: a logout drops them. */
    @Provides
    @IntoSet
    fun settingsSessionCache(repository: UserSettingsRepository): SessionCache = SessionCache { repository.clear() }
}
