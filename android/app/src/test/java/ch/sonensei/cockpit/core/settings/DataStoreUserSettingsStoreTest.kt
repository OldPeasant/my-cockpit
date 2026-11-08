package ch.sonensei.cockpit.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The offline cache of the settings document: kept as JSON text, unreadable content counts as no cache. */
class DataStoreUserSettingsStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val document =
        Json
            .parseToJsonElement("""{"theme":"light","weekStart":"SUNDAY","energyLabels":{"HIGH":"Wired"}}""")
            .jsonObject

    private fun TestScope.dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(folder.root, "user_settings.preferences_pb") },
        )

    @Test
    fun `an empty store has no cached settings`() =
        runTest {
            assertNull(DataStoreUserSettingsStore(dataStore()).read())
        }

    @Test
    fun `written settings are read back with their ETag`() =
        runTest {
            val store = DataStoreUserSettingsStore(dataStore())

            store.write(CachedSettings(document, "\"4\""))

            assertEquals(CachedSettings(document, "\"4\""), store.read())
        }

    @Test
    fun `settings written without an ETag drop the previous one`() =
        runTest {
            val store = DataStoreUserSettingsStore(dataStore())
            store.write(CachedSettings(document, "\"4\""))

            store.write(CachedSettings(document, null))

            assertEquals(CachedSettings(document, null), store.read())
        }

    @Test
    fun `clear removes the cached settings`() =
        runTest {
            val store = DataStoreUserSettingsStore(dataStore())
            store.write(CachedSettings(document, "\"4\""))

            store.clear()

            assertNull(store.read())
        }

    @Test
    fun `unreadable or non-object JSON counts as no cache`() =
        runTest {
            val dataStore = dataStore()
            val store = DataStoreUserSettingsStore(dataStore)
            val json = stringPreferencesKey("settings_json")

            dataStore.edit { it[json] = "{\"theme\":\"li" }
            assertNull(store.read())

            dataStore.edit { it[json] = "[\"light\"]" }
            assertNull(store.read())

            dataStore.edit { it[json] = "\"light\"" }
            assertNull(store.read())
        }
}
