package ch.sonensei.cockpit.auth

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException

/** What the system passkey sheet ended with. */
sealed interface CredentialOutcome {
    /** The authenticator's response as WebAuthn JSON (`AuthenticationResponseJSON` or `RegistrationResponseJSON`). */
    data class Success(
        val responseJson: String,
    ) : CredentialOutcome

    /** The user closed the sheet (or it timed out): not an error worth showing. */
    data object Cancelled : CredentialOutcome

    /** No passkey for this server is stored on the device. */
    data object NoCredential : CredentialOutcome

    /** Registration: the authenticator already holds a passkey for this account. */
    data object AlreadyRegistered : CredentialOutcome

    /** No credential provider supports passkeys here (no Play services, no provider set up, old Android). */
    data object Unavailable : CredentialOutcome

    /** Any other failure of the authenticator or the provider. */
    data object Failed : CredentialOutcome
}

/** The device side of a WebAuthn ceremony; the server side is [PasskeyRepository]. */
interface PasskeyCredentials {
    /** Signs the server's `PublicKeyCredentialRequestOptionsJSON` with a passkey the user picks. */
    suspend fun authenticate(requestJson: String): CredentialOutcome

    /** Creates a passkey for the server's `PublicKeyCredentialCreationOptionsJSON`. */
    suspend fun register(requestJson: String): CredentialOutcome
}

/**
 * [PasskeyCredentials] through Android's Credential Manager. [context] must be an Activity context: the system sheet is
 * shown on top of it.
 */
class CredentialManagerPasskeys(
    private val context: Context,
    private val manager: CredentialManager = CredentialManager.create(context),
) : PasskeyCredentials {
    override suspend fun authenticate(requestJson: String): CredentialOutcome {
        val request = GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson)))
        return try {
            authenticationOutcome(manager.getCredential(context, request))
        } catch (e: GetCredentialException) {
            authenticationOutcome(e)
        }
    }

    override suspend fun register(requestJson: String): CredentialOutcome =
        try {
            registrationOutcome(manager.createCredential(context, CreatePublicKeyCredentialRequest(requestJson)))
        } catch (e: CreateCredentialException) {
            registrationOutcome(e)
        }

    internal companion object {
        fun authenticationOutcome(response: GetCredentialResponse): CredentialOutcome =
            (response.credential as? PublicKeyCredential)?.let { CredentialOutcome.Success(it.authenticationResponseJson) }
                ?: CredentialOutcome.Failed

        fun authenticationOutcome(e: GetCredentialException): CredentialOutcome =
            when (e) {
                // WebAuthn reports both "closed by the user" and "timed out" as NotAllowedError.
                is GetCredentialCancellationException, is GetCredentialInterruptedException -> CredentialOutcome.Cancelled
                is GetPublicKeyCredentialDomException ->
                    if (e.domError is NotAllowedError) CredentialOutcome.Cancelled else CredentialOutcome.Failed
                is NoCredentialException -> CredentialOutcome.NoCredential
                is GetCredentialProviderConfigurationException, is GetCredentialUnsupportedException -> CredentialOutcome.Unavailable
                else -> CredentialOutcome.Failed
            }

        fun registrationOutcome(response: CreateCredentialResponse): CredentialOutcome =
            (response as? CreatePublicKeyCredentialResponse)?.let { CredentialOutcome.Success(it.registrationResponseJson) }
                ?: CredentialOutcome.Failed

        fun registrationOutcome(e: CreateCredentialException): CredentialOutcome =
            when (e) {
                is CreateCredentialCancellationException, is CreateCredentialInterruptedException -> CredentialOutcome.Cancelled
                is CreatePublicKeyCredentialDomException ->
                    when (e.domError) {
                        // The server lists the user's passkeys in excludeCredentials.
                        is InvalidStateError -> CredentialOutcome.AlreadyRegistered
                        is NotAllowedError -> CredentialOutcome.Cancelled
                        else -> CredentialOutcome.Failed
                    }
                is CreateCredentialNoCreateOptionException,
                is CreateCredentialProviderConfigurationException,
                is CreateCredentialUnsupportedException,
                -> CredentialOutcome.Unavailable
                else -> CredentialOutcome.Failed
            }
    }
}

/** Credential Manager bound to the current Activity. */
@Composable
fun rememberPasskeyCredentials(): PasskeyCredentials {
    val context = LocalContext.current
    return remember(context) { CredentialManagerPasskeys(context) }
}
