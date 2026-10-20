package ch.sonensei.cockpit.mail.model

import ch.sonensei.cockpit.core.items.domain.ItemResolver
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Emails in generic views (SPEC.md §4.3): title is the subject, subtitle the sender, deep link
 * `/mail/messages/{id}` of the message's current copy. A link keeps working after the message moved or its UID
 * changed, because [MailMessageLocator] re-resolves it by Message-ID.
 */
@Component
class MailItemResolver(
	private val locator: MailMessageLocator,
) : ItemResolver {
	override val type = ItemType.EMAIL

	@Transactional(readOnly = true)
	override fun resolve(id: UUID): ResolvedItem? =
		locator.current(id)?.let {
			ResolvedItem(
				title = it.subject?.takeIf(String::isNotBlank) ?: NO_SUBJECT,
				subtitle = it.from.firstOrNull()?.let { sender -> sender.name ?: sender.address },
				icon = if (it.seen) "drafts" else "mail",
				deepLink = "/mail/messages/${it.id}",
			)
		}

	private companion object {
		const val NO_SUBJECT = "(no subject)"
	}
}
