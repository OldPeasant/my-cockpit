package ch.sonensei.cockpit.core.network

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of checking a server URL the user typed. */
sealed interface ServerUrlValidation {
    /** [url] is normalized: its path ends with `/`, so API paths resolve below it. */
    data class Valid(
        val url: HttpUrl,
    ) : ServerUrlValidation

    data class Invalid(
        val reason: Reason,
    ) : ServerUrlValidation

    enum class Reason {
        /** Not an http(s) URL, or it has a user, query or fragment. */
        MALFORMED,

        /** Plain http to a host other than this device or the emulator's host. */
        HTTPS_REQUIRED,
    }
}

object ServerUrlValidator {
    /** Hosts that may use plain http: this device, and the development machine as seen from the emulator. */
    val CLEARTEXT_HOSTS = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

    fun validate(raw: String): ServerUrlValidation {
        val url =
            raw
                .trim()
                .toHttpUrlOrNull()
                ?.takeIf { it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null }
                ?: return ServerUrlValidation.Invalid(ServerUrlValidation.Reason.MALFORMED)
        if (!url.isHttps && url.host !in CLEARTEXT_HOSTS) {
            return ServerUrlValidation.Invalid(ServerUrlValidation.Reason.HTTPS_REQUIRED)
        }
        val normalized = if (url.encodedPath.endsWith("/")) url else url.newBuilder().addPathSegment("").build()
        return ServerUrlValidation.Valid(normalized)
    }
}

/** The server base URL the user configured, kept in DataStore. */
@Singleton
class ServerUrlRepository
    @Inject
    constructor(
        @param:ServerConfigStore private val dataStore: DataStore<Preferences>,
        private val session: AuthSession,
    ) {
        /** The configured URL, or null before the user set one or while the settings cannot be read. */
        val serverUrl: Flow<HttpUrl?> =
            readablePrefs()
                .map { prefs -> prefs[SERVER_URL]?.let { (ServerUrlValidator.validate(it) as? ServerUrlValidation.Valid)?.url } }

        /**
         * Stores [raw] if it is valid; returns the validation either way. Moving to another origin (scheme, host, port)
         * logs out first, so the tokens never reach a server that did not issue them. The logout waits for a running
         * refresh (which still goes to the previous server) and happens before the new URL is stored, so requests that
         * see the new URL see no tokens. Without a readable previous URL the tokens' server is unknown, so being logged
         * in then logs out too. Main-safe: the wait for a running refresh happens on [Dispatchers.IO].
         */
        suspend fun setServerUrl(raw: String): ServerUrlValidation {
            val result = ServerUrlValidator.validate(raw)
            if (result is ServerUrlValidation.Valid) {
                val previous = readablePrefs().first()[SERVER_URL]
                // No known previous server (never set, or the settings were unreadable) while logged in is an anomaly:
                // the tokens' issuer is unknown, so they must not go to the new server either.
                if (previous == null || !sameOrigin(previous.toHttpUrlOrNull(), result.url)) {
                    // The lock may be held for a whole refresh: wait for it off the caller's (possibly main) thread.
                    withContext(Dispatchers.IO) {
                        synchronized(session.refreshLock) {
                            if (session.isLoggedIn) session.logout(LogoutReason.SERVER_CHANGED)
                        }
                    }
                }
                dataStore.edit { it[SERVER_URL] = result.url.toString() }
            }
            return result
        }

        /** The settings; an unreadable file counts as empty (not configured) instead of failing the reader. */
        private fun readablePrefs(): Flow<Preferences> =
            dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

        private fun sameOrigin(
            a: HttpUrl?,
            b: HttpUrl,
        ): Boolean = a != null && a.scheme == b.scheme && a.host == b.host && a.port == b.port

        private companion object {
            val SERVER_URL = stringPreferencesKey("server_url")
        }
    }

/** Base URL Retrofit is built with; [ServerUrlInterceptor] replaces it with the configured server on every call. */
internal const val PLACEHOLDER_BASE_URL = "https://server.invalid/"

/** Points each request at the configured server, keeping the server's path prefix (e.g. `https://host/cockpit/`). */
class ServerUrlInterceptor(
    private val serverUrl: () -> HttpUrl?,
) : Interceptor {
    @Inject
    constructor(repository: ServerUrlRepository) : this({ runBlocking { repository.serverUrl.first() } })

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val server = serverUrl() ?: throw IOException("No server URL configured")
        val url =
            server
                .newBuilder()
                .addEncodedPathSegments(request.url.encodedPath.removePrefix("/"))
                .encodedQuery(request.url.encodedQuery)
                .build()
        return chain.proceed(request.newBuilder().url(url).build())
    }
}
