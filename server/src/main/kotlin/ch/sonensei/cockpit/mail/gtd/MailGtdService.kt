package ch.sonensei.cockpit.mail.gtd

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.gtd.domain.Change
import ch.sonensei.cockpit.gtd.domain.TaskChanges
import ch.sonensei.cockpit.gtd.domain.TaskList
import ch.sonensei.cockpit.gtd.domain.TaskService
import ch.sonensei.cockpit.mail.api.MailReadService
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailIdentityRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageLocator
import ch.sonensei.cockpit.mail.sanitize.RemoteImagePolicy
import ch.sonensei.cockpit.notes.domain.FieldChange
import ch.sonensei.cockpit.notes.domain.NoteChanges
import ch.sonensei.cockpit.notes.domain.NoteService
import org.jsoup.Jsoup
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.format.DateTimeFormatter
import java.util.UUID

/** An item created from a mail: its type and id, its title, the mail it is linked to and the link. */
data class MailGtdItem(
	val item: ItemRef,
	val title: String,
	val messageId: UUID,
	val linkId: UUID,
)

/**
 * GTD integration of mail (SPEC.md §7.3): turns a message into an Inbox task, a Waiting For task or a note, each with a
 * manual link to the message so the item shows a link back to the mail. The link points at the message's current copy;
 * it survives later moves because the EMAIL resolver re-resolves the row by Message-ID ([MailMessageLocator]).
 */
@Service
class MailGtdService(
	private val locator: MailMessageLocator,
	private val reads: MailReadService,
	private val accounts: MailAccountRepository,
	private val identities: MailIdentityRepository,
	private val folders: MailFolderRepository,
	private val tasks: TaskService,
	private val notes: NoteService,
	private val links: LinkService,
	private val settings: SettingsService,
	private val transactions: TransactionTemplate,
) {
	/** An Inbox task titled with the message's subject. */
	fun toTask(messageId: UUID): MailGtdItem =
		inTransaction {
			val message = current(messageId)
			val task = tasks.create(null, TaskList.INBOX, TaskChanges(title = Change(titleOf(message, TaskService.MAX_TITLE_LENGTH))))
			linked(message, ItemRef(ItemType.TASK, task.task.id), task.task.title)
		}

	/**
	 * A Waiting For task titled with the subject, waiting since the mail's date. For a mail the user sent (in the Sent
	 * folder, or from the account's address or one of its identities) it waits for the recipients (To, else Cc, else
	 * Bcc); for a received mail for its sender.
	 */
	fun toWaiting(messageId: UUID): MailGtdItem =
		inTransaction {
			val message = current(messageId)
			val waitingFor = if (isSent(message)) message.to.ifEmpty { message.cc }.ifEmpty { message.bcc } else message.from
			val waitingForText = waitingFor.joinToString(", ", transform = ::display).takeIf(String::isNotEmpty)
			val fields =
				TaskChanges(
					title = Change(titleOf(message, TaskService.MAX_TITLE_LENGTH)),
					waitingForText = Change(waitingForText?.let { truncate(it, TaskService.MAX_TITLE_LENGTH) }),
					waitingSince = (message.sentAt ?: message.receivedAt)?.let { Change(it.atZone(settings.timeZone()).toLocalDate()) },
				)
			val task = tasks.create(null, TaskList.WAITING, fields)
			linked(message, ItemRef(ItemType.TASK, task.task.id), task.task.title)
		}

	/**
	 * A note titled with the subject; its body has the sender, recipients and date, then the mail's plain text (the
	 * HTML part as text when there is none). Loading the body may contact the mail server.
	 *
	 * @throws ch.sonensei.cockpit.mail.imap.MailConnectionException when the body is not cached and the server fails
	 */
	fun toNote(messageId: UUID): MailGtdItem {
		val view = reads.read(messageId, RemoteImagePolicy.BLOCK)
		val text = view.body.text ?: view.body.html?.let { Jsoup.parse(it).wholeText() }
		return inTransaction {
			val message = current(view.message.id)
			val fields =
				NoteChanges(
					title = FieldChange(titleOf(message, NoteService.MAX_TITLE_LENGTH)),
					bodyMd = FieldChange(noteBody(message, text)),
				)
			val note = notes.create(fields)
			linked(message, ItemRef(ItemType.NOTE, note.id), note.title ?: "")
		}
	}

	private fun current(messageId: UUID): MailMessage = locator.current(messageId) ?: throw NotFoundException(MESSAGE_NOT_FOUND)

	private fun linked(
		message: MailMessage,
		item: ItemRef,
		title: String,
	): MailGtdItem {
		val link = links.create(item, ItemRef(ItemType.EMAIL, message.id), null)
		return MailGtdItem(item, title, message.id, link.link.id)
	}

	private fun isSent(message: MailMessage): Boolean {
		val folder = message.folderId?.let { folders.findById(it).orElse(null) }
		if (folder?.role == MailFolderRole.SENT) return true
		val own =
			buildSet {
				accounts.findById(message.accountId).orElse(null)?.let { add(it.emailAddress.lowercase()) }
				identities.findByAccountIdOrderByPositionAscIdAsc(message.accountId).forEach { add(it.email.lowercase()) }
			}
		return message.from.any { it.address.lowercase() in own }
	}

	private fun noteBody(
		message: MailMessage,
		text: String?,
	): String {
		val header =
			buildList {
				if (message.from.isNotEmpty()) add("**From:** ${message.from.joinToString(", ", transform = ::full)}")
				if (message.to.isNotEmpty()) add("**To:** ${message.to.joinToString(", ", transform = ::full)}")
				if (message.cc.isNotEmpty()) add("**Cc:** ${message.cc.joinToString(", ", transform = ::full)}")
				(message.sentAt ?: message.receivedAt)?.let {
					add("**Date:** ${DATE_FORMAT.format(it.atZone(settings.timeZone()))}")
				}
			}.joinToString("  \n")
		val body = listOf(header, text?.replace("\u0000", "")?.trim().orEmpty()).filter { it.isNotEmpty() }.joinToString("\n\n")
		return truncate(body, NoteService.MAX_BODY_LENGTH)
	}

	private fun <T : Any> inTransaction(block: () -> T): T = checkNotNull(transactions.execute { block() })

	private companion object {
		const val MESSAGE_NOT_FOUND = "Message not found"
		const val NO_SUBJECT = "(no subject)"
		val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

		fun titleOf(
			message: MailMessage,
			max: Int,
		): String =
			truncate(
				message.subject
					?.replace("\u0000", "")
					?.trim()
					?.takeIf(String::isNotEmpty) ?: NO_SUBJECT,
				max,
			)

		fun display(address: MailAddress): String = address.name?.takeIf(String::isNotBlank) ?: address.address

		fun full(address: MailAddress): String = address.name?.takeIf(String::isNotBlank)?.let { "$it <${address.address}>" } ?: address.address

		fun truncate(
			value: String,
			max: Int,
		): String = if (value.length <= max) value else value.take(max - 1).trimEnd() + "…"
	}
}
