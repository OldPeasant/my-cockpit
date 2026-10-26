package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailIdentity
import ch.sonensei.cockpit.mail.model.MailIdentityRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** A new sending identity; blank [replyTo] and empty [signatureMd] count as absent. */
data class NewMailIdentity(
	val name: String,
	val email: String,
	val replyTo: String? = null,
	val signatureMd: String? = null,
	val isDefault: Boolean = false,
)

/** Changes to an identity; `null` leaves a field as it is, an empty [replyTo] or [signatureMd] removes it. */
data class MailIdentityChanges(
	val name: String? = null,
	val email: String? = null,
	val replyTo: String? = null,
	val signatureMd: String? = null,
	val isDefault: Boolean? = null,
)

/**
 * The sending identities of an account (SPEC.md §7.1), besides the account's own address. An address is used once
 * per account (case-insensitive); at most one identity is the default that compose starts with. Changes of the same
 * account run one after the other (row lock on the account), and each publishes a `MAIL_IDENTITY` change event.
 */
@Service
class MailIdentityService(
	private val identities: MailIdentityRepository,
	private val changes: ChangeEventPublisher,
	private val entityManager: EntityManager,
) {
	/** The identities of an account, in their order. */
	@Transactional(readOnly = true)
	fun list(accountId: UUID): List<MailIdentity> {
		account(accountId, lock = false)
		return identities.findByAccountIdOrderByPositionAscIdAsc(accountId)
	}

	@Transactional(readOnly = true)
	fun get(
		accountId: UUID,
		id: UUID,
	): MailIdentity {
		account(accountId, lock = false)
		return identity(accountId, id)
	}

	@Transactional
	fun create(
		accountId: UUID,
		request: NewMailIdentity,
	): MailIdentity {
		account(accountId, lock = true)
		val existing = identities.findByAccountIdOrderByPositionAscIdAsc(accountId)
		checkName(request.name)
		checkUnique(existing, request.email, except = null)
		if (request.isDefault) clearDefault(existing)
		val identity =
			MailIdentity(accountId, request.name.trim(), request.email).apply {
				replyTo = request.replyTo?.takeIf { it.isNotBlank() }
				signatureMd = request.signatureMd?.takeIf { it.isNotEmpty() }
				isDefault = request.isDefault
				position = (existing.maxOfOrNull { it.position } ?: -1) + 1
			}
		return identities.saveAndFlush(identity).also { changes.publish(IDENTITY_EVENT, it.id, ChangeOp.CREATED) }
	}

	@Transactional
	fun update(
		accountId: UUID,
		id: UUID,
		request: MailIdentityChanges,
		ifMatch: String?,
	): MailIdentity {
		account(accountId, lock = true)
		val identity = identity(accountId, id)
		ETags.checkIfMatch(ifMatch, identity.version)
		val existing = identities.findByAccountIdOrderByPositionAscIdAsc(accountId)
		request.name?.let(::checkName)
		request.email?.let(::checkEmail)
		request.email?.let { checkUnique(existing, it, except = id) }
		if (request.isDefault == true && !identity.isDefault) clearDefault(existing)
		request.name?.let { identity.name = it.trim() }
		request.email?.let { identity.email = it }
		request.replyTo?.let { identity.replyTo = it.takeIf { it.isNotBlank() } }
		request.signatureMd?.let { identity.signatureMd = it.ifEmpty { null } }
		request.isDefault?.let { identity.isDefault = it }
		return identities.saveAndFlush(identity).also { changes.publish(IDENTITY_EVENT, it.id, ChangeOp.UPDATED) }
	}

	@Transactional
	fun delete(
		accountId: UUID,
		id: UUID,
		ifMatch: String?,
	) {
		account(accountId, lock = true)
		val identity = identity(accountId, id)
		ETags.checkIfMatch(ifMatch, identity.version)
		identities.delete(identity)
		identities.flush()
		changes.publish(IDENTITY_EVENT, id, ChangeOp.DELETED)
	}

	private fun account(
		id: UUID,
		lock: Boolean,
	): MailAccount {
		val mode = if (lock) LockModeType.PESSIMISTIC_WRITE else LockModeType.NONE
		return entityManager.find(MailAccount::class.java, id, mode) ?: throw NotFoundException("Mail account not found")
	}

	private fun identity(
		accountId: UUID,
		id: UUID,
	): MailIdentity =
		identities.findById(id).orElse(null)?.takeIf { it.accountId == accountId }
			?: throw NotFoundException("Mail identity not found")

	private fun checkName(name: String) {
		if (name.isBlank()) throw InvalidRequestException("name", "must not be blank")
	}

	private fun checkEmail(email: String) {
		if (email.isBlank()) throw InvalidRequestException("email", "must not be blank")
	}

	private fun checkUnique(
		existing: List<MailIdentity>,
		email: String,
		except: UUID?,
	) {
		if (existing.any { it.id != except && it.email.equals(email, ignoreCase = true) }) {
			throw ConflictException("The account already has an identity with this address")
		}
	}

	/** Takes the default from the current default identity first: the database allows one per account. */
	private fun clearDefault(existing: List<MailIdentity>) {
		existing.filter { it.isDefault }.forEach {
			it.isDefault = false
			identities.saveAndFlush(it)
			changes.publish(IDENTITY_EVENT, it.id, ChangeOp.UPDATED)
		}
	}

	companion object {
		/** The `type` of identity change events. */
		const val IDENTITY_EVENT = "MAIL_IDENTITY"
	}
}
