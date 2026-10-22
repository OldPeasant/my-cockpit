package ch.sonensei.cockpit.core.auth.passkey.domain

import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.CockpitPrincipal
import ch.sonensei.cockpit.core.auth.domain.DeviceTokenResult
import ch.sonensei.cockpit.core.auth.domain.DeviceTokenService
import ch.sonensei.cockpit.core.auth.domain.LoginResult
import ch.sonensei.cockpit.core.auth.domain.LoginService
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.domain.UserAccount
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.auth.passkey.infra.PasskeyRepository
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.InvalidRequestException
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticatorTransport
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionsAuthenticatorOutputs
import com.webauthn4j.data.extension.client.AuthenticationExtensionsClientOutputs
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.verifier.exception.VerificationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Passkeys (WebAuthn, SPEC.md §4.1), verified with webauthn4j. Passkeys are discoverable credentials with user
 * verification, so a passkey alone replaces password + TOTP.
 *
 * - Registration: the logged-in owner (web session or device token) confirms with a current TOTP code, then the
 *   authenticator creates the credential ([startRegistration], [finishRegistration]).
 * - Authentication: [startAuthentication], then [login] (web session) or [issueToken] (Android device). Both count
 *   against the same [LoginThrottle] as the password login, and fail without saying why.
 *
 * Attestation is not requested (`none`): the owner registers their own authenticators while logged in.
 *
 * An account restored without its TOTP secret ([UserAccount.totpReenrollRequired]) has no valid TOTP, so it cannot
 * register a passkey; a passkey login gives it a session that may only enroll TOTP again, and no device token
 * ([LoginResult.TotpEnrollmentRequired]), like the password login.
 */
@Service
class PasskeyService(
	private val users: UserAccountRepository,
	private val passkeys: PasskeyRepository,
	private val ceremonies: PasskeyCeremonies,
	private val login: LoginService,
	private val devices: DeviceTokenService,
	private val throttle: LoginThrottle,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val totp = Totp()
	private val objectConverter = ObjectConverter()
	private val webAuthn = WebAuthnManager.createNonStrictWebAuthnManager(objectConverter)
	private val credentialDataConverter = AttestedCredentialDataConverter(objectConverter)

	/**
	 * Starts adding a passkey for [principal] after checking the [totpCode] (throttled like a login). The options
	 * exclude the passkeys already registered, so an authenticator is not registered twice.
	 */
	@Transactional(readOnly = true)
	fun startRegistration(
		principal: CockpitPrincipal,
		totpCode: String,
		relyingParty: RelyingParty,
	): RegistrationStartResult {
		throttle.tryBeginAttempt()?.let { return RegistrationStartResult.Throttled(it) }
		val account = ownerAccount(principal)
		// While re-enrolling, the stored secret is a random one or not confirmed yet: it does not confirm anything.
		val secret = account?.takeUnless { it.totpReenrollRequired }?.let { Base32.decode(it.totpSecret.reveal()) }
		if (account == null || secret == null || !totp.verify(secret, totpCode, clock.instant())) {
			log.info("Passkey registration rejected: invalid TOTP code")
			return RegistrationStartResult.InvalidTotp
		}
		throttle.recordSuccess()
		val ceremony = Ceremony.Registration(account.id, ceremonies.newChallenge(), relyingParty, ceremonies.expiry())
		val options =
			RegistrationOptions(
				ceremonyId = ceremonies.start(ceremony),
				challenge = ceremony.challenge,
				relyingParty = relyingParty,
				userHandle = userHandle(account.id),
				username = account.username,
				excludeCredentials = passkeys.findAllByOrderByCreatedAtAsc().map { CredentialDescriptor(it.credentialId, transportsOf(it)) },
				algorithms = ALGORITHMS.map { it.value },
				timeoutMillis = PasskeyCeremonies.LIFETIME.toMillis(),
			)
		return RegistrationStartResult.Started(options)
	}

	/**
	 * Verifies the authenticator's [registrationResponseJson] (the `PublicKeyCredential` as JSON) for the ceremony
	 * [ceremonyId] of [principal] and stores the passkey as [name].
	 *
	 * @throws InvalidRequestException if the ceremony is unknown, expired or someone else's, or the response does
	 *   not verify
	 * @throws ConflictException if this credential is already registered
	 */
	@Transactional
	fun finishRegistration(
		principal: CockpitPrincipal,
		ceremonyId: String,
		name: String,
		registrationResponseJson: String,
	): PasskeyInfo {
		val ceremony = ceremonies.finish(ceremonyId) as? Ceremony.Registration
		val account = ownerAccount(principal)
		if (ceremony == null || account == null || ceremony.userId != account.id) {
			throw InvalidRequestException("ceremonyId", "unknown or expired ceremony")
		}
		val data =
			try {
				webAuthn.parseRegistrationResponseJSON(registrationResponseJson)
			} catch (e: RuntimeException) {
				// webauthn4j reports malformed input as DataConversionException, bad base64url as IllegalArgumentException.
				log.info("Passkey registration failed: malformed response ({})", e.javaClass.simpleName)
				throw InvalidRequestException("credential", "malformed passkey response")
			}
		try {
			webAuthn.verify(data, RegistrationParameters(serverProperty(ceremony), PUB_KEY_CRED_PARAMS, true, true))
		} catch (e: VerificationException) {
			log.info("Passkey registration failed: {}", e.javaClass.simpleName)
			throw InvalidRequestException("credential", "the passkey could not be verified")
		}
		val authenticatorData = checkNotNull(data.attestationObject).authenticatorData
		val credentialData = checkNotNull(authenticatorData.attestedCredentialData)
		if (passkeys.existsByCredentialId(credentialData.credentialId)) throw ConflictException("This passkey is already registered")
		val passkey =
			passkeys.saveAndFlush(
				Passkey(
					user = account,
					name = name,
					credentialId = credentialData.credentialId,
					attestedCredentialData = credentialDataConverter.convert(credentialData),
					signCount = authenticatorData.signCount,
					uvInitialized = authenticatorData.isFlagUV,
					backupEligible = authenticatorData.isFlagBE,
					backupState = authenticatorData.isFlagBS,
					transports = data.transports?.takeIf { it.isNotEmpty() }?.joinToString(",") { it.value },
				),
			)
		log.info("Passkey {} registered", passkey.id)
		return infoOf(passkey)
	}

	@Transactional(readOnly = true)
	fun list(): List<PasskeyInfo> = passkeys.findAllByOrderByCreatedAtAsc().map(::infoOf)

	/** Deletes the passkey [id]; false if there is no such passkey. */
	@Transactional
	fun delete(id: UUID): Boolean {
		val deleted = passkeys.deletePasskey(id) > 0
		if (deleted) log.info("Passkey {} deleted", id)
		return deleted
	}

	/** Starts a login with a passkey. No credentials are listed: passkeys are discoverable. */
	fun startAuthentication(relyingParty: RelyingParty): AuthenticationOptions {
		val ceremony = Ceremony.Authentication(ceremonies.newChallenge(), relyingParty, ceremonies.expiry())
		return AuthenticationOptions(ceremonies.start(ceremony), ceremony.challenge, relyingParty, PasskeyCeremonies.LIFETIME.toMillis())
	}

	/** Logs the browser in with a passkey: a new session, like [LoginService.login]. */
	@Transactional
	fun login(
		ceremonyId: String,
		authenticationResponseJson: String,
		rememberDevice: Boolean,
	): LoginResult =
		when (val check = verifyAssertion(ceremonyId, authenticationResponseJson)) {
			is AssertionCheck.Rejected -> {
				check.result
			}

			is AssertionCheck.Verified -> {
				val grant = login.startSession(check.account, rememberDevice, check.now)
				login.recordSuccess()
				log.info("Passkey login succeeded (remember device: {})", rememberDevice)
				LoginResult.Success(grant)
			}
		}

	/** Logs a device (the Android app) in with a passkey: a new token pair, like [DeviceTokenService.issue]. */
	@Transactional
	fun issueToken(
		ceremonyId: String,
		authenticationResponseJson: String,
		deviceName: String,
	): DeviceTokenResult =
		when (val check = verifyAssertion(ceremonyId, authenticationResponseJson)) {
			is AssertionCheck.Rejected -> {
				DeviceTokenResult.Rejected(check.result)
			}

			is AssertionCheck.Verified if check.account.totpReenrollRequired -> {
				log.info("Passkey device login refused: TOTP must be enrolled again first")
				DeviceTokenResult.Rejected(LoginResult.TotpEnrollmentRequired)
			}

			is AssertionCheck.Verified -> {
				val tokens = devices.issueFor(check.account, deviceName, check.now)
				login.recordSuccess()
				DeviceTokenResult.Issued(tokens)
			}
		}

	/**
	 * Checks an assertion under the login throttle and records the passkey's use. Every failure is the same
	 * [LoginResult.InvalidCredentials]; the log names only the kind of failure.
	 */
	private fun verifyAssertion(
		ceremonyId: String,
		authenticationResponseJson: String,
	): AssertionCheck {
		throttle.tryBeginAttempt()?.let { return AssertionCheck.Rejected(LoginResult.Throttled(it)) }
		val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
		val ceremony = ceremonies.finish(ceremonyId) as? Ceremony.Authentication ?: return rejected("unknown or expired ceremony")
		val data =
			try {
				webAuthn.parseAuthenticationResponseJSON(authenticationResponseJson)
			} catch (e: RuntimeException) {
				// As in finishRegistration: any failure to parse is malformed input.
				return rejected("malformed response (${e.javaClass.simpleName})")
			}
		// Locked like in LoginService.verify, so the grant cannot interleave with a TOTP re-enrollment confirm. The lock
		// comes before any other read: the account and the passkey (sign counter, version) are then the committed state
		// after a concurrent confirm or passkey login, not a stale copy that fails the optimistic version check.
		val lockedAccount = users.findFirstForUpdate()
		val passkey = passkeys.findByCredentialId(data.credentialId) ?: return rejected("unknown passkey")
		val account = lockedAccount?.takeIf { it.isActive && it.id == passkey.user.id } ?: return rejected("account not active")
		val userHandle = data.userHandle
		if (userHandle != null && !userHandle.contentEquals(userHandle(account.id))) return rejected("user handle mismatch")
		try {
			webAuthn.verify(data, AuthenticationParameters(serverProperty(ceremony), credentialRecordOf(passkey), null, true, true))
		} catch (e: VerificationException) {
			return rejected(e.javaClass.simpleName)
		}
		val authenticatorData = checkNotNull(data.authenticatorData)
		passkey.recordUse(authenticatorData.signCount, authenticatorData.isFlagUV, authenticatorData.isFlagBS, now)
		passkeys.saveAndFlush(passkey)
		log.info("Passkey {} used for login", passkey.id)
		return AssertionCheck.Verified(account, now)
	}

	private fun rejected(reason: String): AssertionCheck.Rejected {
		log.info("Passkey login failed: {}", reason)
		return AssertionCheck.Rejected(LoginResult.InvalidCredentials)
	}

	private fun ownerAccount(principal: CockpitPrincipal): UserAccount? =
		users.findFirstBy()?.takeIf { it.isActive && it.id == principal.userId }

	private fun serverProperty(ceremony: Ceremony): ServerProperty =
		ServerProperty(
			ceremony.relyingParty.origins
				.map(Origin::create)
				.toSet(),
			ceremony.relyingParty.id,
			DefaultChallenge(ceremony.challenge),
		)

	private fun credentialRecordOf(passkey: Passkey) =
		CredentialRecordImpl(
			NoneAttestationStatement(),
			passkey.uvInitialized,
			passkey.backupEligible,
			passkey.backupState,
			passkey.signCount,
			credentialDataConverter.convert(passkey.attestedCredentialData),
			AuthenticationExtensionsAuthenticatorOutputs(),
			null,
			AuthenticationExtensionsClientOutputs(),
			transportsOf(passkey),
		)

	private fun transportsOf(passkey: Passkey): Set<AuthenticatorTransport>? =
		passkey.transports
			?.split(',')
			?.filter { it.isNotBlank() }
			?.map(AuthenticatorTransport::create)
			?.toSet()

	private fun infoOf(passkey: Passkey) = PasskeyInfo(passkey.id, passkey.name, passkey.createdAt ?: clock.instant(), passkey.lastUsedAt)

	private sealed interface AssertionCheck {
		class Verified(
			val account: UserAccount,
			val now: Instant,
		) : AssertionCheck

		class Rejected(
			val result: LoginResult,
		) : AssertionCheck
	}

	companion object {
		/** ES256, EdDSA and RS256 in order of preference: what platform authenticators and security keys support. */
		val ALGORITHMS = listOf(COSEAlgorithmIdentifier.ES256, COSEAlgorithmIdentifier.EdDSA, COSEAlgorithmIdentifier.RS256)

		private val PUB_KEY_CRED_PARAMS = ALGORITHMS.map { PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, it) }

		/** The WebAuthn user handle of the owner: the 16 bytes of the account id (no personal data). */
		fun userHandle(userId: UUID): ByteArray =
			ByteBuffer
				.allocate(16)
				.putLong(userId.mostSignificantBits)
				.putLong(userId.leastSignificantBits)
				.array()
	}
}

sealed interface RegistrationStartResult {
	class Started(
		val options: RegistrationOptions,
	) : RegistrationStartResult

	data object InvalidTotp : RegistrationStartResult

	class Throttled(
		val retryAfter: Duration,
	) : RegistrationStartResult
}

/** What the client needs for `navigator.credentials.create()`; the controller renders it as WebAuthn JSON. */
class RegistrationOptions(
	val ceremonyId: String,
	val challenge: ByteArray,
	val relyingParty: RelyingParty,
	val userHandle: ByteArray,
	val username: String,
	val excludeCredentials: List<CredentialDescriptor>,
	val algorithms: List<Long>,
	val timeoutMillis: Long,
)

/** What the client needs for `navigator.credentials.get()`. */
class AuthenticationOptions(
	val ceremonyId: String,
	val challenge: ByteArray,
	val relyingParty: RelyingParty,
	val timeoutMillis: Long,
)

class CredentialDescriptor(
	val id: ByteArray,
	val transports: Set<AuthenticatorTransport>?,
)

data class PasskeyInfo(
	val id: UUID,
	val name: String,
	val createdAt: Instant,
	val lastUsedAt: Instant?,
)
