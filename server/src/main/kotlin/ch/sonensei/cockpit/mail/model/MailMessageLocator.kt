package ch.sonensei.cockpit.mail.model

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Finds the current copy of a linked message (SPEC.md §4.3). A [MailMessage] row whose UID is no longer valid
 * ([MailMessage.isLocated] false, e.g. after a move or a UIDVALIDITY change) is re-resolved by its account and
 * Message-ID header: a located copy in the same folder is preferred, then any other folder of the account.
 */
@Component
class MailMessageLocator(
	private val messages: MailMessageRepository,
) {
	/** The located message for [id] (itself while it is located), or `null` when it is gone from the account. */
	@Transactional(readOnly = true)
	fun current(id: UUID): MailMessage? {
		val message = messages.findById(id).orElse(null) ?: return null
		if (message.isLocated) return message
		val messageId = message.messageIdHeader ?: return null
		val copies = messages.findByAccountIdAndMessageIdHeaderAndUidIsNotNullOrderByIdAsc(message.accountId, messageId)
		return copies.firstOrNull { it.folderId == message.folderId } ?: copies.firstOrNull()
	}
}
