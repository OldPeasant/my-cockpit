package ch.sonensei.cockpit.core.auth.passkey.api

import ch.sonensei.cockpit.core.auth.passkey.domain.AuthenticationOptions
import ch.sonensei.cockpit.core.auth.passkey.domain.CredentialDescriptor
import ch.sonensei.cockpit.core.auth.passkey.domain.PasskeyInfo
import ch.sonensei.cockpit.core.auth.passkey.domain.RegistrationOptions
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.Base64
import java.util.UUID

class PasskeyRegistrationOptionsRequest(
	@field:NotBlank
	@field:Size(max = 16)
	@field:Schema(description = "Current 6-digit code from the authenticator app", example = "123456", requiredMode = RequiredMode.REQUIRED)
	val totp: String,
) {
	override fun toString(): String = "PasskeyRegistrationOptionsRequest(******)"
}

class PasskeyRegistrationRequest(
	@field:NotBlank
	@field:Size(max = 64)
	@field:Schema(description = "From the registration options", requiredMode = RequiredMode.REQUIRED)
	val ceremonyId: String,
	@field:NotBlank
	@field:Size(max = 100)
	@field:Schema(description = "Shown in the passkey list", example = "Laptop", maxLength = 100, requiredMode = RequiredMode.REQUIRED)
	val name: String,
	@field:NotEmpty
	@field:Schema(
		description = "The created `PublicKeyCredential` as JSON (`RegistrationResponseJSON`)",
		requiredMode = RequiredMode.REQUIRED,
	)
	val credential: Map<String, Any?>,
)

class PasskeyLoginRequest(
	@field:NotBlank
	@field:Size(max = 64)
	@field:Schema(description = "From the authentication options", requiredMode = RequiredMode.REQUIRED)
	val ceremonyId: String,
	@field:NotEmpty
	@field:Schema(
		description = "The `PublicKeyCredential` assertion as JSON (`AuthenticationResponseJSON`)",
		requiredMode = RequiredMode.REQUIRED,
	)
	val credential: Map<String, Any?>,
	@field:Schema(description = "Keep the session for a sliding 30 days instead of the browser session")
	val rememberDevice: Boolean = false,
)

class PasskeyTokenRequest(
	@field:NotBlank
	@field:Size(max = 64)
	@field:Schema(description = "From the authentication options", requiredMode = RequiredMode.REQUIRED)
	val ceremonyId: String,
	@field:NotEmpty
	@field:Schema(
		description = "The `PublicKeyCredential` assertion as JSON (`AuthenticationResponseJSON`)",
		requiredMode = RequiredMode.REQUIRED,
	)
	val credential: Map<String, Any?>,
	@field:NotBlank
	@field:Size(max = 100)
	@field:Schema(description = "Shown in the device list", example = "Pixel 9", maxLength = 100, requiredMode = RequiredMode.REQUIRED)
	val deviceName: String,
)

data class PasskeyResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val name: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val createdAt: Instant,
	@field:Schema(description = "Last login with this passkey; null if never used")
	val lastUsedAt: Instant?,
) {
	companion object {
		fun of(passkey: PasskeyInfo) = PasskeyResponse(passkey.id, passkey.name, passkey.createdAt, passkey.lastUsedAt)
	}
}

class PasskeyRegistrationOptionsResponse(
	@field:Schema(description = "Send back with the registration", requiredMode = RequiredMode.REQUIRED)
	val ceremonyId: String,
	@field:Schema(description = "`PublicKeyCredentialCreationOptionsJSON`", requiredMode = RequiredMode.REQUIRED)
	val publicKey: CreationOptionsJson,
) {
	companion object {
		fun of(options: RegistrationOptions) =
			PasskeyRegistrationOptionsResponse(
				options.ceremonyId,
				CreationOptionsJson(
					rp = RelyingPartyJson(options.relyingParty.id, options.relyingParty.name),
					user = UserJson(base64Url(options.userHandle), options.username, options.username),
					challenge = base64Url(options.challenge),
					pubKeyCredParams = options.algorithms.map { CredentialParameterJson(alg = it) },
					timeout = options.timeoutMillis,
					excludeCredentials = options.excludeCredentials.map(CredentialDescriptorJson::of),
					authenticatorSelection = AuthenticatorSelectionJson(),
				),
			)
	}
}

class PasskeyAuthenticationOptionsResponse(
	@field:Schema(description = "Send back with the login", requiredMode = RequiredMode.REQUIRED)
	val ceremonyId: String,
	@field:Schema(description = "`PublicKeyCredentialRequestOptionsJSON`", requiredMode = RequiredMode.REQUIRED)
	val publicKey: RequestOptionsJson,
) {
	companion object {
		fun of(options: AuthenticationOptions) =
			PasskeyAuthenticationOptionsResponse(
				options.ceremonyId,
				RequestOptionsJson(base64Url(options.challenge), options.timeoutMillis, options.relyingParty.id),
			)
	}
}

/** WebAuthn Level 3 `PublicKeyCredentialCreationOptionsJSON`; binary values are base64url without padding. */
class CreationOptionsJson(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val rp: RelyingPartyJson,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val user: UserJson,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val challenge: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val pubKeyCredParams: List<CredentialParameterJson>,
	@field:Schema(description = "Milliseconds", requiredMode = RequiredMode.REQUIRED)
	val timeout: Long,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val excludeCredentials: List<CredentialDescriptorJson>,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val authenticatorSelection: AuthenticatorSelectionJson,
	@field:Schema(allowableValues = ["none"], requiredMode = RequiredMode.REQUIRED)
	val attestation: String = "none",
)

/** WebAuthn Level 3 `PublicKeyCredentialRequestOptionsJSON`. No `allowCredentials`: passkeys are discoverable. */
class RequestOptionsJson(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val challenge: String,
	@field:Schema(description = "Milliseconds", requiredMode = RequiredMode.REQUIRED)
	val timeout: Long,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val rpId: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val allowCredentials: List<CredentialDescriptorJson> = emptyList(),
	@field:Schema(allowableValues = ["required"], requiredMode = RequiredMode.REQUIRED)
	val userVerification: String = "required",
)

class RelyingPartyJson(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val name: String,
)

class UserJson(
	@field:Schema(description = "User handle, base64url", requiredMode = RequiredMode.REQUIRED)
	val id: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val name: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val displayName: String,
)

class CredentialParameterJson(
	@field:Schema(allowableValues = ["public-key"], requiredMode = RequiredMode.REQUIRED)
	val type: String = PUBLIC_KEY,
	@field:Schema(description = "COSE algorithm identifier", requiredMode = RequiredMode.REQUIRED)
	val alg: Long,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
class CredentialDescriptorJson(
	@field:Schema(allowableValues = ["public-key"], requiredMode = RequiredMode.REQUIRED)
	val type: String = PUBLIC_KEY,
	@field:Schema(description = "Credential id, base64url", requiredMode = RequiredMode.REQUIRED)
	val id: String,
	val transports: List<String>? = null,
) {
	companion object {
		fun of(descriptor: CredentialDescriptor) =
			CredentialDescriptorJson(id = base64Url(descriptor.id), transports = descriptor.transports?.map { it.value }?.sorted())
	}
}

class AuthenticatorSelectionJson(
	@field:Schema(allowableValues = ["required"], requiredMode = RequiredMode.REQUIRED)
	val residentKey: String = "required",
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val requireResidentKey: Boolean = true,
	@field:Schema(allowableValues = ["required"], requiredMode = RequiredMode.REQUIRED)
	val userVerification: String = "required",
)

private const val PUBLIC_KEY = "public-key"

private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
