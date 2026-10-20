package ch.sonensei.cockpit.core.network

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers a 401 by refreshing the tokens once and retrying the request with the new access token.
 *
 * Concurrent 401s share one refresh: whoever comes second sees that the stored access token differs from the one its
 * request was sent with and retries with the stored one. A rejected refresh logs the device out.
 */
@Singleton
class TokenAuthenticator
    @Inject
    constructor(
        private val tokenStore: TokenStore,
        private val refresher: TokenRefresher,
        private val session: AuthSession,
    ) : Authenticator {
        private val lock = Any()

        override fun authenticate(
            route: Route?,
            response: Response,
        ): Request? {
            val sentToken = response.request.bearerToken() ?: return null
            // Refresh once: a 401 for a request that was already retried is final.
            if (response.priorResponse != null) return null
            synchronized(lock) {
                val current = tokenStore.current() ?: return null
                if (current.accessToken != sentToken) return response.request.withBearer(current.accessToken)
                return when (val result = refresher.refresh(current.refreshToken)) {
                    is RefreshResult.Success -> {
                        tokenStore.save(result.tokens)
                        response.request.withBearer(result.tokens.accessToken)
                    }
                    RefreshResult.Rejected -> {
                        session.logout(LogoutReason.REFRESH_REJECTED)
                        null
                    }
                    RefreshResult.Failed -> null
                }
            }
        }
    }
