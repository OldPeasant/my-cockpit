package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.events.domain.ClientNotification
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailMessage
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** One new message in a [NewMailNotification]: what a notification shows, never the body. */
@Schema(description = "A newly arrived, unread message")
data class NewMailSummary(
	@get:Schema(description = "The message id (`mail_message.id`)", requiredMode = RequiredMode.REQUIRED)
	val id: UUID,
	@get:Schema(description = "The first sender, formatted as `Name <address>`")
	val from: String?,
	val subject: String?,
	val receivedAt: Instant?,
)

/** SSE event `mail`: unread mail arrived in the Inbox of an account that notifies about new mail. */
@Schema(description = "Unread mail arrived in an account's Inbox")
data class NewMailNotification(
	@get:Schema(requiredMode = RequiredMode.REQUIRED)
	val accountId: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED)
	val folderId: UUID,
	@get:Schema(description = "How many unread messages arrived", requiredMode = RequiredMode.REQUIRED)
	val count: Int,
	@get:Schema(description = "The newest of them (at most 10), newest first", requiredMode = RequiredMode.REQUIRED)
	val messages: List<NewMailSummary>,
)

/**
 * Announces messages that arrived in a folder since its previous sync (SPEC.md §7.2): an `EMAIL` CREATED change
 * event for each, and when they include unread ones in the Inbox of an account with [MailAccount.notifyNewMail], a
 * [NewMailNotification] (SSE event [EVENT_NAME]) for clients to show.
 */
@Component
class NewMailAnnouncer(
	private val messages: MailSyncRepository,
	private val changes: ChangeEventPublisher,
	private val events: ApplicationEventPublisher,
) {
	fun announce(
		account: MailAccount,
		folder: MailFolder,
		arrived: List<UUID>,
	) {
		if (arrived.isEmpty()) return
		arrived.forEach { changes.publish(ItemType.EMAIL, it, ChangeOp.CREATED) }
		if (folder.role != MailFolderRole.INBOX || !account.notifyNewMail) return
		val unread =
			messages
				.findAllById(arrived)
				.filter { !it.seen && it.folderId == folder.id }
				.sortedWith(compareByDescending<MailMessage> { it.receivedAt }.thenByDescending { it.uid })
		if (unread.isEmpty()) return
		val summaries =
			unread.take(MAX_SUMMARIES).map { NewMailSummary(it.id, it.from.firstOrNull()?.toString(), it.subject, it.receivedAt) }
		events.publishEvent(ClientNotification(EVENT_NAME, NewMailNotification(account.id, folder.id, unread.size, summaries)))
	}

	companion object {
		const val EVENT_NAME = "mail"
		private const val MAX_SUMMARIES = 10
	}
}
