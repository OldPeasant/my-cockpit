package ch.sonensei.cockpit.auth

import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.core.network.ServerUrlRepository
import ch.sonensei.cockpit.core.network.ServerUrlValidation
import ch.sonensei.cockpit.core.network.generated.apis.PasskeysApi
import ch.sonensei.cockpit.core.network.generated.models.PasskeyRegistrationOptionsRequest
import ch.sonensei.cockpit.core.network.generated.models.PasskeyRegistrationRequest
import ch.sonensei.cockpit.core.network.generated.models.PasskeyResponse
import ch.sonensei.cockpit.core.network.generated.models.PasskeyTokenRequest
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.IOException
import java.time.DateTimeException
import javax.inject.Inject
import javax.inject.Singleton

/** A started WebAuthn ceremony: [publicKey] is passed to the authenticator exactly as the server sent it. */
@Serializable
data class PasskeyCeremony(
    val ceremonyId: String,
    val publicKey: JsonObject,
)

/**
 * The two option calls of [PasskeysApi], with the options kept as raw JSON: re-encoding the generated models would
 * drop fields they do not know (extensions, hints) and add `null`s Credential Manager may reject.
 */
interface PasskeyCeremonyApi {
    @POST("api/v1/auth/passkeys/authentication/options")
    suspend fun startAuthentication(): Response<PasskeyCeremony>

    @POST("api/v1/auth/passkeys/registration/options")
    suspend fun startRegistration(
        @Body request: PasskeyRegistrationOptionsRequest,
    ): Response<PasskeyCeremony>
}

@Module
@InstallIn(SingletonComponent::class)
object PasskeyModule {
    @Provides
    fun passkeysApi(retrofit: Retrofit): PasskeysApi = retrofit.create(PasskeysApi::class.java)

    @Provides
    fun passkeyCeremonyApi(retrofit: Retrofit): PasskeyCeremonyApi = retrofit.create(PasskeyCeremonyApi::class.java)
}

sealed interface PasskeyRegistrationResult {
    data class Success(
        val passkey: PasskeyResponse,
    ) : PasskeyRegistrationResult

    /** The user closed the passkey sheet. */
    data object Cancelled : PasskeyRegistrationResult

    /** The authenticator code is missing (400) or wrong (403). */
    data object WrongCode : PasskeyRegistrationResult

    /** Too many wrong codes (429): wait [retryAfterSeconds]. */
    data class TooManyAttempts(
        val retryAfterSeconds: Long,
    ) : PasskeyRegistrationResult

    /** This passkey is already registered (the authenticator or the server said so). */
    data object AlreadyRegistered : PasskeyRegistrationResult

    /** The device cannot create passkeys (no credential provider). */
    data object Unavailable : PasskeyRegistrationResult

    /** The authenticator failed, or the server did not accept its response. */
    data object Failed : PasskeyRegistrationResult

    data object Network : PasskeyRegistrationResult

    data object ServerError : PasskeyRegistrationResult
}

/**
 * Passkeys on the server side: logs the device in with one (`/passkeys/token`, like the password login of
 * [AuthRepository]), lists them and adds one for the logged-in user. The authenticator is [PasskeyCredentials].
 */
@Singleton
class PasskeyRepository internal constructor(
    private val serverUrls: ServerUrlRepository,
    private val passkeysApi: PasskeysApi,
    private val ceremonyApi: PasskeyCeremonyApi,
    private val tokenStore: PersistentTokenStore,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        serverUrls: ServerUrlRepository,
        passkeysApi: PasskeysApi,
        ceremonyApi: PasskeyCeremonyApi,
        tokenStore: PersistentTokenStore,
    ) : this(serverUrls, passkeysApi, ceremonyApi, tokenStore, Dispatchers.IO)

    /** Logs this device in to [serverUrl] with a passkey the user picks in the system sheet. */
    suspend fun login(
        serverUrl: String,
        credentials: PasskeyCredentials,
    ): LoginResult {
        val validation = serverUrls.setServerUrl(serverUrl)
        if (validation is ServerUrlValidation.Invalid) return LoginResult.InvalidServerUrl(validation.reason)
        val ceremony =
            when (val started = call { ceremonyApi.startAuthentication() }) {
                is Call.Ok -> started.body
                is Call.Status -> return if (started.code == 429) tooMany(started) else LoginResult.ServerError
                Call.Network -> return LoginResult.Network
                Call.Unreadable -> return LoginResult.ServerError
            }
        val assertion =
            when (val outcome = credentials.authenticate(ceremony.publicKey.toString())) {
                is CredentialOutcome.Success -> parse(outcome.responseJson) ?: return LoginResult.PasskeyFailed
                CredentialOutcome.Cancelled -> return LoginResult.PasskeyCancelled
                CredentialOutcome.NoCredential -> return LoginResult.NoPasskey
                CredentialOutcome.Unavailable -> return LoginResult.PasskeyUnavailable
                CredentialOutcome.AlreadyRegistered, CredentialOutcome.Failed -> return LoginResult.PasskeyFailed
            }
        val request = PasskeyTokenRequest(ceremonyId = ceremony.ceremonyId, credential = assertion, deviceName = deviceName())
        return when (val token = call { passkeysApi.issueDeviceTokenWithPasskey(request) }) {
            is Call.Ok -> {
                // Encrypting with the Keystore is binder IPC: keep it off the main thread.
                withContext(ioDispatcher) {
                    tokenStore.login(AuthTokens(token.body.accessToken, token.body.refreshToken), token.body.deviceId.toString())
                }
                LoginResult.Success
            }
            is Call.Status ->
                when (token.code) {
                    400 -> LoginResult.InvalidRequest
                    401 -> LoginResult.PasskeyRejected
                    403 -> LoginResult.TotpResetRequired
                    429 -> tooMany(token)
                    else -> LoginResult.ServerError
                }
            Call.Network -> LoginResult.Network
            Call.Unreadable -> LoginResult.ServerError
        }
    }

    /** The logged-in user's passkeys, oldest first; null if they could not be loaded. */
    suspend fun list(): List<PasskeyResponse>? = (call { passkeysApi.listPasskeys() } as? Call.Ok)?.body

    /** Adds a passkey named [name]; the server asks for the current authenticator code [totp] first. */
    suspend fun register(
        name: String,
        totp: String,
        credentials: PasskeyCredentials,
    ): PasskeyRegistrationResult {
        val ceremony =
            when (val started = call { ceremonyApi.startRegistration(PasskeyRegistrationOptionsRequest(totp.trim())) }) {
                is Call.Ok -> started.body
                is Call.Status ->
                    return when (started.code) {
                        400, 403 -> PasskeyRegistrationResult.WrongCode
                        429 -> PasskeyRegistrationResult.TooManyAttempts(retryAfter(started.retryAfter))
                        else -> PasskeyRegistrationResult.ServerError
                    }
                Call.Network -> return PasskeyRegistrationResult.Network
                Call.Unreadable -> return PasskeyRegistrationResult.ServerError
            }
        val attestation =
            when (val outcome = credentials.register(ceremony.publicKey.toString())) {
                is CredentialOutcome.Success -> parse(outcome.responseJson) ?: return PasskeyRegistrationResult.Failed
                CredentialOutcome.Cancelled -> return PasskeyRegistrationResult.Cancelled
                CredentialOutcome.AlreadyRegistered -> return PasskeyRegistrationResult.AlreadyRegistered
                CredentialOutcome.Unavailable -> return PasskeyRegistrationResult.Unavailable
                CredentialOutcome.NoCredential, CredentialOutcome.Failed -> return PasskeyRegistrationResult.Failed
            }
        val request = PasskeyRegistrationRequest(ceremonyId = ceremony.ceremonyId, credential = attestation, name = name.trim())
        return when (val finished = call { passkeysApi.finishPasskeyRegistration(request) }) {
            is Call.Ok -> PasskeyRegistrationResult.Success(finished.body)
            is Call.Status ->
                when (finished.code) {
                    400 -> PasskeyRegistrationResult.Failed
                    409 -> PasskeyRegistrationResult.AlreadyRegistered
                    else -> PasskeyRegistrationResult.ServerError
                }
            Call.Network -> PasskeyRegistrationResult.Network
            Call.Unreadable -> PasskeyRegistrationResult.ServerError
        }
    }

    private fun tooMany(status: Call.Status) = LoginResult.TooManyAttempts(retryAfter(status.retryAfter))

    /** The authenticator's JSON as the request's `credential` object; null if it is not a JSON object. */
    private fun parse(json: String): JsonObject? =
        try {
            Json.parseToJsonElement(json).jsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private sealed interface Call<out T> {
        data class Ok<T>(
            val body: T,
        ) : Call<T>

        data class Status(
            val code: Int,
            val retryAfter: String?,
        ) : Call<Nothing>

        data object Network : Call<Nothing>

        data object Unreadable : Call<Nothing>
    }

    private inline fun <T> call(request: () -> Response<T>): Call<T> {
        val response =
            try {
                request()
            } catch (_: IOException) {
                return Call.Network
            } catch (_: SerializationException) {
                return Call.Unreadable
            } catch (_: IllegalArgumentException) {
                return Call.Unreadable
            } catch (_: DateTimeException) {
                return Call.Unreadable
            }
        val body = response.body()
        return when {
            response.isSuccessful && body != null -> Call.Ok(body)
            response.isSuccessful -> Call.Unreadable
            else -> Call.Status(response.code(), response.headers()["Retry-After"])
        }
    }
}
