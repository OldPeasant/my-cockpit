package ch.sonensei.cockpit.auth

import android.os.Build
import ch.sonensei.cockpit.core.network.AuthSession
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.core.network.LogoutReason
import ch.sonensei.cockpit.core.network.ServerUrlRepository
import ch.sonensei.cockpit.core.network.ServerUrlValidation
import ch.sonensei.cockpit.core.network.generated.apis.AuthApi
import ch.sonensei.cockpit.core.network.generated.models.TokenRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.time.DateTimeException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The second login factor: the authenticator app's current code, or one of the recovery codes. */
sealed interface SecondFactor {
    val code: String

    data class Totp(
        override val code: String,
    ) : SecondFactor

    data class RecoveryCode(
        override val code: String,
    ) : SecondFactor
}

sealed interface LoginResult {
    data object Success : LoginResult

    data class InvalidServerUrl(
        val reason: ServerUrlValidation.Reason,
    ) : LoginResult

    /** The server refused the request as incomplete (400). */
    data object InvalidRequest : LoginResult

    /** Username, password or code is wrong; the server does not say which (401). */
    data object WrongCredentials : LoginResult

    /** Too many failed attempts (429): wait [retryAfterSeconds] before the next one. */
    data class TooManyAttempts(
        val retryAfterSeconds: Long,
    ) : LoginResult

    /** The server could not be reached. */
    data object Network : LoginResult

    /** The server answered with an error or an unreadable body. */
    data object ServerError : LoginResult
}

/** Clears data cached for the logged-in user; features contribute theirs to the set Hilt injects. */
fun interface SessionCache {
    suspend fun clear()
}

/**
 * Logs the device in (`POST /api/v1/auth/token`) and out (revokes the device, forgets tokens, clears caches).
 *
 * It is the only owner of cache clearing: [logout] clears the caches of a user logout itself, and every other logout
 * (the network layer rejecting the refresh token, a server change) is picked up from [AuthSession.loggedOut] in
 * [appScope], which lives as long as the app.
 */
@Singleton
class AuthRepository internal constructor(
    private val serverUrls: ServerUrlRepository,
    private val authApi: AuthApi,
    private val tokenStore: PersistentTokenStore,
    private val session: AuthSession,
    private val caches: Set<@JvmSuppressWildcards SessionCache>,
    appScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        serverUrls: ServerUrlRepository,
        authApi: AuthApi,
        tokenStore: PersistentTokenStore,
        session: AuthSession,
        caches: Set<@JvmSuppressWildcards SessionCache>,
    ) : this(serverUrls, authApi, tokenStore, session, caches, CoroutineScope(SupervisorJob() + Dispatchers.Default), Dispatchers.IO)

    init {
        // Undispatched, so the subscription exists before the constructor returns and no logout is missed.
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.loggedOut.filter { it != LogoutReason.USER }.collect { clearCaches() }
        }
    }

    /** The configured server, to prefill the login form. */
    val serverUrl: Flow<String?> = serverUrls.serverUrl.map { it?.toString() }

    suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
        secondFactor: SecondFactor,
    ): LoginResult {
        val validation = serverUrls.setServerUrl(serverUrl)
        if (validation is ServerUrlValidation.Invalid) return LoginResult.InvalidServerUrl(validation.reason)
        val request =
            TokenRequest(
                deviceName = deviceName(),
                password = password,
                username = username.trim(),
                totp = (secondFactor as? SecondFactor.Totp)?.code?.trim(),
                recoveryCode = (secondFactor as? SecondFactor.RecoveryCode)?.code?.trim(),
            )
        val response =
            try {
                authApi.issueDeviceToken(request)
            } catch (_: IOException) {
                return LoginResult.Network
            } catch (_: SerializationException) {
                return LoginResult.ServerError
            } catch (_: IllegalArgumentException) {
                return LoginResult.ServerError
            } catch (_: DateTimeException) {
                return LoginResult.ServerError
            }
        val body = response.body()
        return when {
            response.isSuccessful && body != null -> {
                // Encrypting with the Keystore is binder IPC: keep it off the main thread.
                withContext(ioDispatcher) {
                    tokenStore.login(AuthTokens(body.accessToken, body.refreshToken), body.deviceId.toString())
                }
                LoginResult.Success
            }
            response.code() == 400 -> LoginResult.InvalidRequest
            response.code() == 401 -> LoginResult.WrongCredentials
            response.code() == 429 -> LoginResult.TooManyAttempts(retryAfter(response.headers()["Retry-After"]))
            else -> LoginResult.ServerError
        }
    }

    /**
     * Revokes this device on the server (best effort, so a stolen copy of the refresh token stops working), then
     * forgets the tokens and clears the caches. Offline, the local logout still happens. Once started it cannot be
     * cancelled (the caller's screen going away must not leave the device logged in); the revocation is bounded
     * by [REVOKE_TIMEOUT_MS].
     */
    suspend fun logout() =
        withContext(NonCancellable) {
            try {
                tokenStore.session.value?.deviceId?.let { id ->
                    try {
                        withTimeoutOrNull(REVOKE_TIMEOUT_MS) { authApi.revokeDevice(UUID.fromString(id)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Unreachable server or an error answer: the local logout below still happens.
                    }
                }
            } finally {
                session.logout(LogoutReason.USER)
                clearCaches()
            }
        }

    private suspend fun clearCaches() = caches.forEach { it.clear() }

    private fun deviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() && it != Build.UNKNOWN }
            .joinToString(" ")
            .ifBlank { "Android" }
            .take(DEVICE_NAME_MAX)

    private fun retryAfter(header: String?): Long = header?.trim()?.toLongOrNull()?.coerceAtLeast(1) ?: DEFAULT_RETRY_AFTER_SECONDS

    internal companion object {
        const val DEFAULT_RETRY_AFTER_SECONDS = 30L
        const val REVOKE_TIMEOUT_MS = 5_000L
        const val DEVICE_NAME_MAX = 100
    }
}
