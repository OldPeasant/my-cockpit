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
 * request was sent with and retries with the stored one. A rejected refresh logs the device out. The result of a refresh
 * is applied only if the stored tokens are still the ones it started from, so a logout or login during the refresh
 * wins; the request is then retried with whatever is stored now.
 */
@Singleton
class TokenAuthenticator
    @Inject
    constructor(
        private val tokenStore: TokenStore,
        private val refresher: TokenRefresher,
        private val session: AuthSession,
    ) : Authenticator {
        override fun authenticate(
            route: Route?,
            response: Response,
        ): Request? {
            val sentToken = response.request.bearerToken() ?: return null
            // Refresh once: a 401 for a request that was already retried is final.
            if (response.priorResponse != null) return null
            synchronized(session.refreshLock) {
                val current = tokenStore.current() ?: return null
                if (current.accessToken != sentToken) return response.request.withBearer(current.accessToken)
                return when (val result = refresher.refresh(current.refreshToken)) {
                    is RefreshResult.Success -> {
                        if (tokenStore.replace(current, result.tokens)) {
                            response.request.withBearer(result.tokens.accessToken)
                        } else {
                            retryWithStored(response)
                        }
                    }
                    RefreshResult.Rejected -> {
                        if (session.logoutIfCurrent(current, LogoutReason.REFRESH_REJECTED)) null else retryWithStored(response)
                    }
                    RefreshResult.Failed -> null
                }
            }
        }

        /** The tokens changed during the refresh (logout or new login): use the stored ones, if any. */
        private fun retryWithStored(response: Response): Request? =
            tokenStore.current()?.let { response.request.withBearer(it.accessToken) }
    }
