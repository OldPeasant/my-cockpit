package ch.sonensei.cockpit.core.auth.passkey.domain

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The relying party a ceremony runs for: the RP ID (a domain) and the origins whose client data is accepted (the
 * web origin, and `android:apk-key-hash:...` for the Android app).
 */
data class RelyingParty(
	val id: String,
	val name: String,
	val origins: Set<String>,
)

/** A started WebAuthn ceremony: the challenge sent to the client, which its response must sign. */
sealed interface Ceremony {
	val challenge: ByteArray
	val relyingParty: RelyingParty
	val expiresAt: Instant

	/** Adds a passkey for the logged-in owner [userId]. */
	class Registration(
		val userId: UUID,
		override val challenge: ByteArray,
		override val relyingParty: RelyingParty,
		override val expiresAt: Instant,
	) : Ceremony

	/** Logs in with a passkey. */
	class Authentication(
		override val challenge: ByteArray,
		override val relyingParty: RelyingParty,
		override val expiresAt: Instant,
	) : Ceremony
}

/**
 * Started ceremonies, by a random id the client sends back with its response. Each can be finished once, within
 * [LIFETIME]. They live in memory (single server instance); a restart only makes the user start the ceremony again.
 * At most [MAX_PENDING] of each kind are kept, so unauthenticated option requests cannot fill the memory. Registration
 * and authentication ceremonies are bounded separately: the public authentication options endpoint can only evict
 * other authentication ceremonies, never a logged-in owner's pending registration.
 */
@Component
class PasskeyCeremonies(
	private val clock: Clock,
) {
	private val random = SecureRandom()
	private val registrations = LinkedHashMap<String, Ceremony>()
	private val authentications = LinkedHashMap<String, Ceremony>()

	fun newChallenge(): ByteArray = ByteArray(CHALLENGE_BYTES).also(random::nextBytes)

	fun expiry(): Instant = clock.instant().plus(LIFETIME)

	/** Stores [ceremony] and returns its id. */
	@Synchronized
	fun start(ceremony: Ceremony): String {
		purgeExpired()
		val pending = storeOf(ceremony)
		// Insertion order is start order: the oldest of the same kind goes first.
		while (pending.size >= MAX_PENDING) pending.remove(pending.keys.first())
		val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(ID_BYTES).also(random::nextBytes))
		pending[id] = ceremony
		return id
	}

	/** Removes and returns the ceremony [id] if it exists and has not expired: a ceremony can be finished only once. */
	@Synchronized
	fun finish(id: String): Ceremony? {
		val ceremony = registrations.remove(id) ?: authentications.remove(id) ?: return null
		return ceremony.takeIf { clock.instant().isBefore(it.expiresAt) }
	}

	private fun storeOf(ceremony: Ceremony): LinkedHashMap<String, Ceremony> =
		when (ceremony) {
			is Ceremony.Registration -> registrations
			is Ceremony.Authentication -> authentications
		}

	private fun purgeExpired() {
		val now = clock.instant()
		registrations.values.removeIf { !now.isBefore(it.expiresAt) }
		authentications.values.removeIf { !now.isBefore(it.expiresAt) }
	}

	companion object {
		/** How long a client has to finish a ceremony; also the `timeout` hint sent to it. */
		val LIFETIME: Duration = Duration.ofMinutes(5)

		/** Per ceremony kind. */
		const val MAX_PENDING = 256
		private const val CHALLENGE_BYTES = 32
		private const val ID_BYTES = 24
	}
}
