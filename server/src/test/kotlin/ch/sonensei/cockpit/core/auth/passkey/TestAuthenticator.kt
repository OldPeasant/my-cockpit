package ch.sonensei.cockpit.core.auth.passkey

import com.webauthn4j.converter.AttestationObjectConverter
import com.webauthn4j.converter.AuthenticatorDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.attestation.AttestationObject
import com.webauthn4j.data.attestation.authenticator.AAGUID
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionAuthenticatorOutput
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * A software passkey authenticator (ES256, `none` attestation) that answers WebAuthn options with the JSON a browser's
 * `PublicKeyCredential.toJSON()` or Android's Credential Manager returns. Each instance holds one credential.
 */
class TestAuthenticator(
	var rpId: String = "localhost",
	var origin: String = "http://localhost",
) {
	private val objectConverter = ObjectConverter()
	private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
	val credentialId: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
	var signCount: Long = 0
	var userHandle: ByteArray? = null

	/**
	 * Answers `publicKey` creation options; [type], [challenge] and [flags] can be overridden to forge a bad response.
	 * [transports] null leaves the field out, as some clients do.
	 */
	fun register(
		publicKey: Map<*, *>,
		type: String = "webauthn.create",
		challenge: String = publicKey["challenge"] as String,
		flags: Byte = FLAGS,
		transports: List<String>? = listOf("internal", "hybrid"),
	): Map<String, Any?> {
		userHandle = decode((publicKey["user"] as Map<*, *>)["id"] as String)
		val clientData = clientDataJson(type, challenge)
		val credentialData =
			AttestedCredentialData(AAGUID.ZERO, credentialId, EC2COSEKey.create(keyPair.public as ECPublicKey, COSEAlgorithmIdentifier.ES256))
		val authenticatorData =
			AuthenticatorData<RegistrationExtensionAuthenticatorOutput>(
				sha256(rpId.toByteArray()),
				(flags.toInt() or 0x40).toByte(),
				signCount,
				credentialData,
			)
		val attestationObject = AttestationObject(authenticatorData, NoneAttestationStatement())
		val response =
			mapOf(
				"clientDataJSON" to encode(clientData),
				"attestationObject" to encode(AttestationObjectConverter(objectConverter).convertToBytes(attestationObject)),
			)
		return credential(if (transports == null) response else response + ("transports" to transports))
	}

	/** Answers `publicKey` request options with a signed assertion; the sign count goes up by one. */
	fun authenticate(
		publicKey: Map<*, *>,
		type: String = "webauthn.get",
		challenge: String = publicKey["challenge"] as String,
		flags: Byte = FLAGS,
		tamper: Boolean = false,
	): Map<String, Any?> {
		signCount++
		val clientData = clientDataJson(type, challenge)
		val authenticatorData =
			AuthenticatorDataConverter(objectConverter).convert(
				AuthenticatorData<AuthenticationExtensionAuthenticatorOutput>(sha256(rpId.toByteArray()), flags, signCount),
			)
		val signature =
			Signature.getInstance("SHA256withECDSA").run {
				initSign(keyPair.private)
				update(authenticatorData + sha256(clientData))
				sign()
			}
		if (tamper) signature[signature.size - 1] = (signature[signature.size - 1] + 1).toByte()
		return credential(
			mapOf(
				"clientDataJSON" to encode(clientData),
				"authenticatorData" to encode(authenticatorData),
				"signature" to encode(signature),
				"userHandle" to userHandle?.let(::encode),
			),
		)
	}

	private fun credential(response: Map<String, Any?>) =
		mapOf(
			"id" to encode(credentialId),
			"rawId" to encode(credentialId),
			"type" to "public-key",
			"authenticatorAttachment" to "platform",
			"response" to response,
			"clientExtensionResults" to emptyMap<String, Any>(),
		)

	private fun clientDataJson(
		type: String,
		challenge: String,
	): ByteArray = """{"type":"$type","challenge":"$challenge","origin":"$origin","crossOrigin":false}""".toByteArray()

	companion object {
		/** User present + user verified + backup eligible + backed up: a synced passkey. */
		const val FLAGS: Byte = (0x01 or 0x04 or 0x08 or 0x10).toByte()

		fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

		fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

		fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
	}
}
