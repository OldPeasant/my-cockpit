package ch.sonensei.cockpit.core.network

import ch.sonensei.cockpit.core.network.generated.apis.AuthApi
import ch.sonensei.cockpit.core.network.generated.models.RefreshRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import java.io.IOException
import javax.inject.Inject

/** Result of rotating the tokens with the server. */
sealed interface RefreshResult {
    data class Success(
        val tokens: AuthTokens,
    ) : RefreshResult

    /** The server refused the refresh token: the device must log in again. */
    data object Rejected : RefreshResult

    /** The server could not be reached or failed; the tokens may still be valid, so they are kept. */
    data object Failed : RefreshResult
}

fun interface TokenRefresher {
    /** Blocking; called on an OkHttp thread. */
    fun refresh(refreshToken: String): RefreshResult
}

/** Rotates the tokens via `POST /api/v1/auth/token/refresh`, on a client without the [TokenAuthenticator]. */
class AuthApiTokenRefresher
    @Inject
    constructor(
        @param:RefreshClient private val authApi: AuthApi,
    ) : TokenRefresher {
        override fun refresh(refreshToken: String): RefreshResult {
            val response =
                try {
                    runBlocking { authApi.refreshDeviceToken(RefreshRequest(refreshToken)) }
                } catch (_: IOException) {
                    return RefreshResult.Failed
                } catch (_: SerializationException) {
                    // A success body that cannot be decoded is a server failure; throwing here would crash OkHttp's thread.
                    return RefreshResult.Failed
                } catch (_: IllegalArgumentException) {
                    return RefreshResult.Failed
                }
            val body = response.body()
            return when {
                response.isSuccessful && body != null -> RefreshResult.Success(AuthTokens(body.accessToken, body.refreshToken))
                response.code() == 400 || response.code() == 401 -> RefreshResult.Rejected
                else -> RefreshResult.Failed
            }
        }
    }
