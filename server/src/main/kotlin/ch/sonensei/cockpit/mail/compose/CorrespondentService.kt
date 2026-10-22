package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailIdentityRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Someone the user recently wrote to or got mail from. */
data class Correspondent(
	val address: String,
	val name: String?,
	val lastAt: Instant,
)

/**
 * Recent correspondents for recipient autocomplete (SPEC.md §7.3): the recipients (To, Cc, Bcc) of cached mail in Sent
 * folders and the senders of other cached mail, except Drafts, Trash and Spam, from the [SCANNED] most recent messages
 * of all accounts. The user's own addresses (accounts and identities) are left out. Each address appears once (case
 * does not matter) with its most recent date and the most recent name it was written with; the most recent first.
 */
@Service
class CorrespondentService(
	private val jdbc: JdbcTemplate,
	private val accounts: MailAccountRepository,
	private val identities: MailIdentityRepository,
) {
	/** Correspondents whose address or name contains [query] (case-insensitive; all when blank), at most [limit]. */
	@Transactional(readOnly = true)
	fun recent(
		query: String?,
		limit: Int,
	): List<Correspondent> {
		val own = (accounts.findAll().map { it.emailAddress } + identities.findAll().map { it.email }).map { it.lowercase() }.toSet()
		val needle = query?.trim()?.lowercase().orEmpty()
		val byAddress = LinkedHashMap<String, Correspondent>()
		jdbc.query(
			"""
			select m.from_addresses, m.to_addresses, m.cc_addresses, m.bcc_addresses, f.role,
			       coalesce(m.sent_at, m.received_at) as at
			from mail_message m join mail_folder f on f.id = m.folder_id
			where m.uid is not null and (f.role is null or f.role not in ('DRAFTS', 'TRASH', 'SPAM'))
			  and coalesce(m.sent_at, m.received_at) is not null
			order by coalesce(m.sent_at, m.received_at) desc, m.id desc
			limit $SCANNED
			""".trimIndent(),
		) { rs ->
			val at = rs.getTimestamp("at").toInstant()
			val addresses =
				if (rs.getString("role") == "SENT") {
					listOf("to_addresses", "cc_addresses", "bcc_addresses").flatMap { MailAddress.parseList(rs.getString(it)) }
				} else {
					MailAddress.parseList(rs.getString("from_addresses"))
				}
			for (address in addresses) {
				val key = address.address.lowercase()
				if (key in own) continue
				val seen = byAddress[key]
				// Rows come newest first: the first sighting has the date; a later one only fills a missing name.
				if (seen == null) {
					byAddress[key] = Correspondent(address.address, address.name, at)
				} else if (seen.name == null && address.name != null) {
					byAddress[key] = seen.copy(name = address.name)
				}
			}
		}
		return byAddress.values
			.asSequence()
			.filter { needle.isEmpty() || it.address.lowercase().contains(needle) || it.name?.lowercase()?.contains(needle) == true }
			.take(limit)
			.toList()
	}

	companion object {
		/** How many recent messages are looked at. */
		const val SCANNED = 5000
	}
}
