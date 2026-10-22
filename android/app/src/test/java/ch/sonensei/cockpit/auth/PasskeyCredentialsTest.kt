package ch.sonensei.cockpit.auth

import android.content.Context
import androidx.credentials.CreateCredentialRequest
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreatePasswordResponse
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.SecurityError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** How [CredentialManagerPasskeys] turns Credential Manager answers into [CredentialOutcome]s. */
@RunWith(AndroidJUnit4::class)
class PasskeyCredentialsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = mockk<CredentialManager>()
    private val passkeys = CredentialManagerPasskeys(context, manager)

    @Test
    fun `authentication asks for a passkey with the server's options and returns its response`() =
        runBlocking {
            val request = slot<GetCredentialRequest>()
            coEvery { manager.getCredential(context, capture(request)) } returns
                GetCredentialResponse(PublicKeyCredential(RESPONSE))

            assertEquals(CredentialOutcome.Success(RESPONSE), passkeys.authenticate(OPTIONS))
            val option = request.captured.credentialOptions.single() as GetPublicKeyCredentialOption
            assertEquals(OPTIONS, option.requestJson)
        }

    @Test
    fun `a credential other than a passkey is a failure`() =
        runBlocking {
            coEvery { manager.getCredential(context, any<GetCredentialRequest>()) } returns
                GetCredentialResponse(PasswordCredential("alex", "fictitious"))

            assertEquals(CredentialOutcome.Failed, passkeys.authenticate(OPTIONS))
        }

    @Test
    fun `authentication exceptions map to outcomes`() =
        runBlocking {
            val expected =
                listOf(
                    GetCredentialCancellationException() to CredentialOutcome.Cancelled,
                    GetCredentialInterruptedException() to CredentialOutcome.Cancelled,
                    GetPublicKeyCredentialDomException(NotAllowedError()) to CredentialOutcome.Cancelled,
                    GetPublicKeyCredentialDomException(SecurityError()) to CredentialOutcome.Failed,
                    NoCredentialException() to CredentialOutcome.NoCredential,
                    GetCredentialProviderConfigurationException() to CredentialOutcome.Unavailable,
                    GetCredentialUnsupportedException() to CredentialOutcome.Unavailable,
                    GetCredentialUnknownException() to CredentialOutcome.Failed,
                )
            expected.forEach { (exception, outcome) ->
                coEvery { manager.getCredential(context, any<GetCredentialRequest>()) } throws exception
                assertEquals(exception.javaClass.simpleName, outcome, passkeys.authenticate(OPTIONS))
            }
        }

    @Test
    fun `registration creates a passkey with the server's options and returns its response`() =
        runBlocking {
            val request = slot<CreateCredentialRequest>()
            coEvery { manager.createCredential(context, capture(request)) } returns CreatePublicKeyCredentialResponse(RESPONSE)

            assertEquals(CredentialOutcome.Success(RESPONSE), passkeys.register(CREATION))
            assertEquals(CREATION, (request.captured as CreatePublicKeyCredentialRequest).requestJson)
        }

    @Test
    fun `a response other than a passkey is a failed registration`() {
        val response: CreateCredentialResponse = CreatePasswordResponse()

        assertSame(CredentialOutcome.Failed, CredentialManagerPasskeys.registrationOutcome(response))
    }

    @Test
    fun `registration exceptions map to outcomes`() =
        runBlocking {
            val expected =
                listOf(
                    CreateCredentialCancellationException() to CredentialOutcome.Cancelled,
                    CreateCredentialInterruptedException() to CredentialOutcome.Cancelled,
                    CreatePublicKeyCredentialDomException(NotAllowedError()) to CredentialOutcome.Cancelled,
                    CreatePublicKeyCredentialDomException(InvalidStateError()) to CredentialOutcome.AlreadyRegistered,
                    CreatePublicKeyCredentialDomException(SecurityError()) to CredentialOutcome.Failed,
                    CreateCredentialNoCreateOptionException() to CredentialOutcome.Unavailable,
                    CreateCredentialProviderConfigurationException() to CredentialOutcome.Unavailable,
                    CreateCredentialUnsupportedException() to CredentialOutcome.Unavailable,
                    CreateCredentialUnknownException() to CredentialOutcome.Failed,
                )
            expected.forEach { (exception, outcome) ->
                coEvery { manager.createCredential(context, any<CreateCredentialRequest>()) } throws exception
                assertEquals(exception.javaClass.simpleName, outcome, passkeys.register(CREATION))
            }
        }

    private companion object {
        const val OPTIONS = """{"challenge":"Y2hhbGxlbmdl","rpId":"cockpit.example.org","userVerification":"required"}"""
        const val CREATION =
            """{"challenge":"Y2hhbGxlbmdl","rp":{"id":"cockpit.example.org","name":"Cockpit"},""" +
                """"user":{"id":"dXNlcg","name":"alex","displayName":"alex"},"pubKeyCredParams":[{"type":"public-key","alg":-7}]}"""
        const val RESPONSE = """{"id":"cred-1","type":"public-key","response":{}}"""
    }
}
