package ch.sonensei.cockpit.core.settings

import ch.sonensei.cockpit.core.network.generated.apis.SettingsApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.DayOfWeek
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class UserSettingsRepositoryTest {
    private val api = mockk<SettingsApi>()
    private val store = MemoryStore()

    private fun settingsJson(
        theme: String = "dark",
        weekStart: String = "SUNDAY",
    ): JsonObject =
        Json
            .parseToJsonElement(
                """
                {"theme":"$theme","weekStart":"$weekStart","timeZone":"America/New_York",
                 "energyLabels":{"LOW":"Tired","MEDIUM":"Medium","HIGH":"Fresh"},
                 "creativityLabels":{"ROUTINE":"Routine","MIXED":"Mixed","CREATIVE":"Creative"},
                 "mailImagePolicy":"block","mailImageAllowedSenders":[],"dashboardWidgets":["inbox"]}
                """.trimIndent(),
            ).jsonObject

    private fun ok(
        body: JsonObject,
        etag: String,
    ): Response<JsonObject> = Response.success(body, Headers.headersOf("ETag", etag))

    private fun error(code: Int): Response<JsonObject> = Response.error(code, "{}".toResponseBody())

    private fun TestScope.repository() = UserSettingsRepository(api, store, backgroundScope)

    @Test
    fun `refresh loads, parses and caches the settings`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { api.getSettings() } returns ok(settingsJson(), "\"1\"")
            val repository = repository()

            assertEquals(SettingsLoadResult.LOADED, repository.refresh())

            val settings = repository.settings.value!!
            assertEquals(ThemeSetting.DARK, settings.theme)
            assertEquals(DayOfWeek.SUNDAY, settings.weekStart)
            assertEquals(ZoneId.of("America/New_York"), settings.timeZone)
            // Renamed labels only; default labels are left to the app's translation.
            assertEquals(mapOf("LOW" to "Tired", "HIGH" to "Fresh"), settings.energyLabels)
            assertEquals(emptyMap<String, String>(), settings.creativityLabels)
            assertEquals("\"1\"", store.cached?.etag)
            assertEquals(settingsJson(), store.cached?.json)
        }

    @Test
    fun `the cached settings are shown at start and stay when the server cannot be reached`() =
        runTest(UnconfinedTestDispatcher()) {
            store.cached = CachedSettings(settingsJson(theme = "light"), "\"7\"")
            coEvery { api.getSettings() } throws IOException("offline")
            val repository = repository()
            advanceUntilIdle()

            assertEquals(ThemeSetting.LIGHT, repository.settings.value?.theme)
            assertEquals(SettingsLoadResult.OFFLINE, repository.refresh())
            assertEquals(ThemeSetting.LIGHT, repository.settings.value?.theme)
        }

    @Test
    fun `a server error keeps the last settings`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { api.getSettings() } returnsMany listOf(ok(settingsJson(), "\"1\""), error(503), error(401))
            val repository = repository()
            repository.refresh()

            assertEquals(SettingsLoadResult.OFFLINE, repository.refresh())
            assertEquals(SettingsLoadResult.FAILED, repository.refresh())
            assertEquals(ThemeSetting.DARK, repository.settings.value?.theme)
        }

    @Test
    fun `update sends only the changed key with If-Match and takes the answer`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { api.getSettings() } returns ok(settingsJson(), "\"1\"")
            coEvery { api.updateSettings(any(), any()) } returns ok(settingsJson(theme = "light"), "\"2\"")
            val repository = repository()
            repository.refresh()

            val result = repository.update(UserSettingsChange(theme = ThemeSetting.LIGHT))

            assertEquals(SettingsUpdateResult.Updated, result)
            coVerify { api.updateSettings(JsonObject(mapOf("theme" to JsonPrimitive("light"))), "\"1\"") }
            assertEquals(ThemeSetting.LIGHT, repository.settings.value?.theme)
            assertEquals("\"2\"", store.cached?.etag)
        }

    @Test
    fun `a 412 reloads the settings and reports a conflict`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { api.getSettings() } returnsMany listOf(ok(settingsJson(), "\"1\""), ok(settingsJson(theme = "system"), "\"5\""))
            coEvery { api.updateSettings(any(), any()) } returns error(412)
            val repository = repository()
            repository.refresh()

            val result = repository.update(UserSettingsChange(theme = ThemeSetting.LIGHT))

            assertEquals(SettingsUpdateResult.Conflict, result)
            coVerify(exactly = 2) { api.getSettings() }
            assertEquals(ThemeSetting.SYSTEM, repository.settings.value?.theme)
            assertEquals("\"5\"", store.cached?.etag)
        }

    @Test
    fun `update without loaded settings sends nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = repository()

            assertEquals(SettingsUpdateResult.NotLoaded, repository.update(UserSettingsChange(theme = ThemeSetting.DARK)))
            coVerify(exactly = 0) { api.updateSettings(any(), any()) }
        }

    @Test
    fun `update maps offline, rejected and server errors`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { api.getSettings() } returns ok(settingsJson(), "\"1\"")
            val repository = repository()
            repository.refresh()
            val change = UserSettingsChange(theme = ThemeSetting.LIGHT)

            coEvery { api.updateSettings(any(), any()) } throws IOException("offline")
            assertEquals(SettingsUpdateResult.Offline, repository.update(change))
            coEvery { api.updateSettings(any(), any()) } returns error(400)
            assertEquals(SettingsUpdateResult.Rejected, repository.update(change))
            coEvery { api.updateSettings(any(), any()) } returns error(502)
            assertEquals(SettingsUpdateResult.Offline, repository.update(change))
            assertEquals(ThemeSetting.DARK, repository.settings.value?.theme)
        }

    @Test
    fun `clear forgets the settings and the cache`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { api.getSettings() } returns ok(settingsJson(), "\"1\"")
            val repository = repository()
            repository.refresh()

            repository.clear()

            assertNull(repository.settings.value)
            assertNull(store.cached)
            assertEquals(SettingsUpdateResult.NotLoaded, repository.update(UserSettingsChange(theme = ThemeSetting.DARK)))
        }

    @Test
    fun `start refreshes at each new sign-in but not for the state at start`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { api.getSettings() } returns ok(settingsJson(), "\"1\"")
            val repository = repository()
            val signedIn = MutableStateFlow(true)
            repository.start(signedIn)
            advanceUntilIdle()
            coVerify(exactly = 0) { api.getSettings() }

            signedIn.value = false
            advanceUntilIdle()
            signedIn.value = true
            advanceUntilIdle()

            coVerify(exactly = 1) { api.getSettings() }
        }

    private class MemoryStore : UserSettingsStore {
        var cached: CachedSettings? = null

        override suspend fun read() = cached

        override suspend fun write(settings: CachedSettings) {
            cached = settings
        }

        override suspend fun clear() {
            cached = null
        }
    }
}
