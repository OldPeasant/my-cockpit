package ch.sonensei.cockpit.core.network

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import ch.sonensei.cockpit.core.network.ServerUrlValidation.Invalid
import ch.sonensei.cockpit.core.network.ServerUrlValidation.Reason
import ch.sonensei.cockpit.core.network.ServerUrlValidation.Valid
import ch.sonensei.cockpit.core.network.generated.apis.AuthApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ServerUrlTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `accepts https and normalizes the trailing slash`() {
        assertEquals(Valid("https://cockpit.example.org/".toHttpUrl()), ServerUrlValidator.validate("https://cockpit.example.org"))
        assertEquals(Valid("https://example.org/cockpit/".toHttpUrl()), ServerUrlValidator.validate(" https://example.org/cockpit "))
    }

    @Test
    fun `allows http only for localhost and the emulator host`() {
        listOf("http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080", "http://10.0.2.2:8080").forEach {
            assertEquals(it, true, ServerUrlValidator.validate(it) is Valid)
        }
        listOf("http://cockpit.example.org", "http://192.168.1.10:8080").forEach {
            assertEquals(it, Invalid(Reason.HTTPS_REQUIRED), ServerUrlValidator.validate(it))
        }
    }

    @Test
    fun `rejects malformed URLs`() {
        listOf(
            "",
            "cockpit.example.org",
            "ftp://example.org",
            "https://user:pw@example.org",
            "https://example.org/?a=1",
            "https://example.org/#x",
        ).forEach { assertEquals(it, Invalid(Reason.MALFORMED), ServerUrlValidator.validate(it)) }
    }

    @Test
    fun `stores a valid URL in DataStore and ignores invalid ones`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val file = File(tmp.root, "server_config.preferences_pb")
            val repository = ServerUrlRepository(PreferenceDataStoreFactory.create(scope = scope) { file })

            assertNull(repository.serverUrl.first())
            assertEquals(Invalid(Reason.HTTPS_REQUIRED), repository.setServerUrl("http://cockpit.example.org"))
            assertNull(repository.serverUrl.first())
            repository.setServerUrl("https://cockpit.example.org/base")
            assertEquals("https://cockpit.example.org/base/".toHttpUrl(), repository.serverUrl.first())
            scope.cancel()
        }

    @Test
    fun `cleartext host matching is exact and case-insensitive`() {
        listOf("http://LOCALHOST:8080", "http://localhost", "http://10.0.2.2/cockpit").forEach {
            assertEquals(it, true, ServerUrlValidator.validate(it) is Valid)
        }
        listOf("http://localhost.example.org", "http://10.0.2.3:8080", "http://127.0.0.2", "http://my-localhost").forEach {
            assertEquals(it, Invalid(Reason.HTTPS_REQUIRED), ServerUrlValidator.validate(it))
        }
    }

    @Test
    fun `keeps port and path and rejects blank input`() {
        assertEquals(
            Valid("https://cockpit.example.org:8443/a/b/".toHttpUrl()),
            ServerUrlValidator.validate("https://cockpit.example.org:8443/a/b/"),
        )
        assertEquals(Valid("http://localhost:8080/".toHttpUrl()), ServerUrlValidator.validate("http://localhost:8080/"))
        listOf("   ", "https://", "https://example.org:99999").forEach {
            assertEquals(it, Invalid(Reason.MALFORMED), ServerUrlValidator.validate(it))
        }
    }

    @Test
    fun `the stored URL survives reopening the DataStore and can be replaced`() =
        runBlocking {
            val file = File(tmp.root, "server_config.preferences_pb")
            val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            ServerUrlRepository(PreferenceDataStoreFactory.create(scope = firstScope) { file }).setServerUrl("https://first.example.org")
            firstScope.cancel()
            firstScope.coroutineContext[kotlinx.coroutines.Job]?.join()

            val secondScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val reopened = ServerUrlRepository(PreferenceDataStoreFactory.create(scope = secondScope) { file })
            assertEquals("https://first.example.org/".toHttpUrl(), reopened.serverUrl.first())
            reopened.setServerUrl("http://10.0.2.2:8080")
            assertEquals("http://10.0.2.2:8080/".toHttpUrl(), reopened.serverUrl.first())
            // An invalid URL leaves the previous one in place.
            assertEquals(Invalid(Reason.MALFORMED), reopened.setServerUrl("not a url"))
            assertEquals("http://10.0.2.2:8080/".toHttpUrl(), reopened.serverUrl.first())
            secondScope.cancel()
        }

    @Test
    fun `a stored URL that no longer validates counts as not configured`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "legacy.preferences_pb") }
            dataStore.edit { it[stringPreferencesKey("server_url")] = "http://cockpit.example.org/" }

            assertNull(ServerUrlRepository(dataStore).serverUrl.first())
            scope.cancel()
        }

    @Test
    fun `keeps the query and encoded path when pointing a request at the server`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(204).build())
            server.start()
            val client = CockpitHttp.okHttpClient(ServerUrlInterceptor { server.url("/cockpit/") }, null, null)
            val request =
                Request
                    .Builder()
                    .url("${PLACEHOLDER_BASE_URL}api/v1/events/a%20b?since=2026-10-20T12%3A00%3A00Z&limit=5")
                    .build()

            client.newCall(request).execute().use { assertEquals(204, it.code) }

            val recorded = server.takeRequest().url
            assertEquals("/cockpit/api/v1/events/a%20b", recorded.encodedPath)
            assertEquals("since=2026-10-20T12%3A00%3A00Z&limit=5", recorded.encodedQuery)
            assertEquals(server.port, recorded.port)
        }
    }

    @Test
    fun `requests go to the configured server below its path`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .addHeader("Content-Type", "application/json")
                        .body("""{"username":"alex"}""")
                        .build(),
                )
                server.start()
                val client = CockpitHttp.okHttpClient(ServerUrlInterceptor { server.url("/cockpit/") }, null, null)
                val api = CockpitHttp.retrofit(client).create(AuthApi::class.java)

                assertEquals("alex", api.getCurrentUser().body()?.username)
                assertEquals("/cockpit/api/v1/auth/me", server.takeRequest().url.encodedPath)
            }
        }

    @Test
    fun `fails requests while no server is configured`() {
        val client = CockpitHttp.okHttpClient(ServerUrlInterceptor { null }, null, null)
        val api = CockpitHttp.retrofit(client).create(AuthApi::class.java)

        assertThrows(IOException::class.java) { runBlocking { api.getCurrentUser() } }
    }
}
