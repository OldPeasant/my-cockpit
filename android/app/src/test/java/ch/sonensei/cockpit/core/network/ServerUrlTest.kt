package ch.sonensei.cockpit.core.network

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
