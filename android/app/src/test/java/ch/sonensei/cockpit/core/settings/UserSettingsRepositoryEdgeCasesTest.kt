package ch.sonensei.cockpit.core.settings

import ch.sonensei.cockpit.core.network.generated.apis.SettingsApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/** Ordering, logout and storage failures around [UserSettingsRepository]. */
@OptIn(ExperimentalCoroutinesApi::class)
class UserSettingsRepositoryEdgeCasesTest {
    private val api = mockk<SettingsApi>()
    private val store = MemoryStore()

    private fun settingsJson(theme: String = "dark"): JsonObject =
        Json
            .parseToJsonElement(
                """{"theme":"$theme","weekStart":"MONDAY","timeZone":"Europe/Zurich","energyLabels":{"LOW":"Sleepy"}}""",
            ).jsonObject

    private fun ok(
        body: JsonObject?,
        etag: String,
    ): Response<JsonObject> = Response.success(body, Headers.headersOf("ETag", etag))

    private fun error(code: Int): Response<JsonObject> = Response.error(code, "{}".toResponseBody())

    private fun TestScope.repository(withStore: UserSettingsStore = store) = UserSettingsRepository(api, withStore, backgroundScope)

    @Test
    fun `a load answered after a logout does not bring the old user's settings back`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            coEvery { api.getSettings() } coAnswers {
                gate.await()
                ok(settingsJson(), "\"1\"")
            }
            val repository = repository()

            val pending = async { repository.refresh() }
            repository.clear()
            gate.complete(Unit)

            assertEquals(SettingsLoadResult.FAILED, pending.await())
            assertNull(repository.settings.value)
            assertNull(store.cached)

            // The next sign-in loads normally again.
            coEvery { api.getSettings() } returns ok(settingsJson(theme = "light"), "\"9\"")
            assertEquals(SettingsLoadResult.LOADED, repository.refresh())
            assertEquals(ThemeSetting.LIGHT, repository.settings.value?.theme)
        }

    @Test
    fun `a refresh answered after an update does not overwrite the update's newer answer`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { api.getSettings() } returns ok(settingsJson(), "\"1\"")
            val repository = repository()
            repository.refresh()

            // A GET is sent (e.g. MainActivity.onStart), the server answers it with the state before the PATCH,
            // but the answer arrives only after the PATCH's.
            val gate = CompletableDeferred<Unit>()
            coEvery { api.getSettings() } coAnswers {
                gate.await()
                ok(settingsJson(theme = "dark"), "\"1\"")
            }
            coEvery { api.updateSettings(any(), "\"1\"") } returns ok(settingsJson(theme = "light"), "\"2\"")

            val pending = async { repository.refresh() }
            assertEquals(SettingsUpdateResult.Updated, repository.update(UserSettingsChange(theme = ThemeSetting.LIGHT)))
            gate.complete(Unit)
            pending.await()

            // An older answer never overwrites a newer one.
            assertEquals(ThemeSetting.LIGHT, repository.settings.value?.theme)
            assertEquals("\"2\"", store.cached?.etag)
        }

    @Test
    fun `of two overlapping refreshes the later one wins even when the earlier answer arrives last`() =
        runTest(UnconfinedTestDispatcher()) {
            val first = CompletableDeferred<Response<JsonObject>>()
            val second = CompletableDeferred<Response<JsonObject>>()
            val answers = ArrayDeque(listOf(first, second))
            coEvery { api.getSettings() } coAnswers { answers.removeFirst().await() }
            val repository = repository()

            val older = async { repository.refresh() }
            val newer = async { repository.refresh() }
            second.complete(ok(settingsJson(theme = "light"), "\"2\""))
            assertEquals(SettingsLoadResult.LOADED, newer.await())
            first.complete(ok(settingsJson(theme = "dark"), "\"1\""))

            assertEquals(SettingsLoadResult.FAILED, older.await())
            assertEquals(ThemeSetting.LIGHT, repository.settings.value?.theme)
            assertEquals("\"2\"", store.cached?.etag)
        }

    @Test
    fun `of two overlapping refreshes answered in order both are taken and the later one stays`() =
        runTest(UnconfinedTestDispatcher()) {
            val first = CompletableDeferred<Response<JsonObject>>()
            val second = CompletableDeferred<Response<JsonObject>>()
            val answers = ArrayDeque(listOf(first, second))
            coEvery { api.getSettings() } coAnswers { answers.removeFirst().await() }
            val repository = repository()

            val older = async { repository.refresh() }
            val newer = async { repository.refresh() }
            first.complete(ok(settingsJson(theme = "dark"), "\"1\""))
            assertEquals(SettingsLoadResult.LOADED, older.await())
            second.complete(ok(settingsJson(theme = "light"), "\"2\""))

            assertEquals(SettingsLoadResult.LOADED, newer.await())
            assertEquals(ThemeSetting.LIGHT, repository.settings.value?.theme)
            assertEquals("\"2\"", store.cached?.etag)
        }

    @Test
    fun `a refresh started while an update is in flight is taken after the update`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { api.getSettings() } returns ok(settingsJson(), "\"1\"")
            val repository = repository()
            repository.refresh()

            val patchGate = CompletableDeferred<Unit>()
            coEvery { api.updateSettings(any(), "\"1\"") } coAnswers {
                patchGate.await()
                ok(settingsJson(theme = "light"), "\"2\"")
            }
            // Sent after the PATCH, so the server answers it with an even newer state (e.g. a change made on the web).
            coEvery { api.getSettings() } returns ok(settingsJson(theme = "system"), "\"3\"")

            val update = async { repository.update(UserSettingsChange(theme = ThemeSetting.LIGHT)) }
            val refresh = async { repository.refresh() }
            patchGate.complete(Unit)

            assertEquals(SettingsUpdateResult.Updated, update.await())
            assertEquals(SettingsLoadResult.LOADED, refresh.await())
            assertEquals(ThemeSetting.SYSTEM, repository.settings.value?.theme)
            assertEquals("\"3\"", store.cached?.etag)
        }

    @Test
    fun `a later refresh that fails does not make an earlier answer be dropped`() =
        runTest(UnconfinedTestDispatcher()) {
            val first = CompletableDeferred<Response<JsonObject>>()
            val second = CompletableDeferred<Response<JsonObject>>()
            val answers = ArrayDeque(listOf(first, second))
            coEvery { api.getSettings() } coAnswers { answers.removeFirst().await() }
            val repository = repository()

            val older = async { repository.refresh() }
            val newer = async { repository.refresh() }
            second.complete(error(503))
            assertEquals(SettingsLoadResult.OFFLINE, newer.await())
            first.complete(ok(settingsJson(theme = "light"), "\"1\""))

            // A failed answer sets no state, so the earlier answer is still the newest one and is taken.
            assertEquals(SettingsLoadResult.LOADED, older.await())
            assertEquals(ThemeSetting.LIGHT, repository.settings.value?.theme)
            assertEquals("\"1\"", store.cached?.etag)
        }

    @Test
    fun `409 and 428 also reload the settings and report a conflict without retrying the write`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { api.getSettings() } returns ok(settingsJson(), "\"1\"")
            val repository = repository()
            repository.refresh()
            val change = UserSettingsChange(theme = ThemeSetting.LIGHT)

            coEvery { api.updateSettings(any(), any()) } returns error(409)
            assertEquals(SettingsUpdateResult.Conflict, repository.update(change))
            coEvery { api.updateSettings(any(), any()) } returns error(428)
            assertEquals(SettingsUpdateResult.Conflict, repository.update(change))

            // One initial load plus one reload per conflict; each write was sent once.
            coVerify(exactly = 3) { api.getSettings() }
            coVerify(exactly = 2) { api.updateSettings(any(), any()) }
            assertEquals(ThemeSetting.DARK, repository.settings.value?.theme)
        }

    @Test
    fun `a broken cache does not stop the settings from loading, applying or clearing`() =
        runTest(UnconfinedTestDispatcher()) {
            val broken =
                object : UserSettingsStore {
                    override suspend fun read(): CachedSettings? = throw IOException("cache unreadable")

                    override suspend fun write(settings: CachedSettings): Unit = throw IOException("device full")

                    override suspend fun clear(): Unit = throw IOException("cache locked")
                }
            coEvery { api.getSettings() } returns ok(settingsJson(), "\"1\"")
            val repository = repository(broken)
            advanceUntilIdle()
            assertNull(repository.settings.value)

            assertEquals(SettingsLoadResult.LOADED, repository.refresh())
            assertEquals(ThemeSetting.DARK, repository.settings.value?.theme)
            assertEquals(mapOf("LOW" to "Sleepy"), repository.settings.value?.energyLabels)

            repository.clear()
            assertNull(repository.settings.value)
        }

    @Test
    fun `a success without a body is not taken`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { api.getSettings() } returnsMany listOf(ok(settingsJson(), "\"1\""), ok(null, "\"2\""))
            val repository = repository()
            repository.refresh()

            assertEquals(SettingsLoadResult.FAILED, repository.refresh())
            assertEquals(ThemeSetting.DARK, repository.settings.value?.theme)

            coEvery { api.updateSettings(any(), any()) } returns ok(null, "\"3\"")
            assertEquals(SettingsUpdateResult.Rejected, repository.update(UserSettingsChange(theme = ThemeSetting.LIGHT)))
            assertEquals(ThemeSetting.DARK, repository.settings.value?.theme)
            assertEquals("\"1\"", store.cached?.etag)
        }

    @Test
    fun `an unreadable server answer fails the load and rejects the update`() =
        runTest(UnconfinedTestDispatcher()) {
            coEvery { api.getSettings() } returns ok(settingsJson(), "\"1\"")
            val repository = repository()
            repository.refresh()

            coEvery { api.getSettings() } throws SerializationException("not a settings document")
            assertEquals(SettingsLoadResult.FAILED, repository.refresh())
            coEvery { api.updateSettings(any(), any()) } throws SerializationException("not a settings document")
            assertEquals(SettingsUpdateResult.Rejected, repository.update(UserSettingsChange(theme = ThemeSetting.LIGHT)))
            assertEquals(ThemeSetting.DARK, repository.settings.value?.theme)
        }

    @Test
    fun `an empty change is not sent`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = repository()

            assertEquals(SettingsUpdateResult.Updated, repository.update(UserSettingsChange()))
            coVerify(exactly = 0) { api.updateSettings(any(), any()) }
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
