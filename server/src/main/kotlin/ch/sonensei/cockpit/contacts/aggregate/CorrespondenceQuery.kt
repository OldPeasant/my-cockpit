package ch.sonensei.cockpit.contacts.aggregate

import ch.sonensei.cockpit.mail.model.MailAddress
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** An address seen in the mail headers, with the newest display name and when it was last seen. */
data class Correspondent(
	val address: String,
	val name: String?,
	val lastSeenAt: Instant,
)

/**
 * Read-only lookups in the mail header cache by address (SPEC.md §8.3). The mail module has no service for that, so
 * this reads `mail_message` directly; it only ever selects ids and address headers and never writes. Located messages
 * only, without Trash and Spam (and without Drafts for [messageIdsWith]).
 */
@Component
class CorrespondenceQuery(
	private val jdbc: NamedParameterJdbcTemplate,
) {
	/**
	 * Ids of the newest messages with one of [addresses] in From, To or Cc, newest first. Copies of one message in
	 * several folders (same account and Message-ID) count once.
	 */
	fun messageIdsWith(
		addresses: Collection<String>,
		limit: Int,
	): List<UUID> {
		if (addresses.isEmpty()) return emptyList()
		val params =
			MapSqlParameterSource()
				.addValue("pattern", addressPattern(addresses))
				.addValue("limit", limit)
		val sql =
			"""
			select id from (
			    select distinct on (m.account_id, coalesce(m.message_id_header, m.id::text))
			        m.id, coalesce(m.received_at, m.created_at) as sort_at
			    from mail_message m join mail_folder f on f.id = m.folder_id
			    where m.uid is not null and (f.role is null or f.role not in ('TRASH', 'SPAM', 'DRAFTS'))
			        and (m.from_addresses ~* :pattern or m.to_addresses ~* :pattern or m.cc_addresses ~* :pattern)
			    order by m.account_id, coalesce(m.message_id_header, m.id::text),
			        coalesce(m.received_at, m.created_at) desc, m.id desc
			) t
			order by sort_at desc, id desc
			limit :limit
			"""
		return jdbc.query(sql, params) { rs, _ -> rs.getObject("id", UUID::class.java) }
	}

	/**
	 * Addresses from From, To and Cc of the newest [scan] messages mentioning [query], whose address or name contains
	 * [query] (case-insensitive), most recently seen first. The owner's own addresses (accounts and identities) are left
	 * out.
	 */
	fun correspondents(
		query: String,
		scan: Int,
	): List<Correspondent> {
		val params =
			MapSqlParameterSource()
				.addValue("like", "%" + escapeLike(query) + "%")
				.addValue("scan", scan)
		val sql =
			"""
			select m.from_addresses, m.to_addresses, m.cc_addresses, coalesce(m.received_at, m.created_at) as sort_at
			from mail_message m join mail_folder f on f.id = m.folder_id
			where m.uid is not null and (f.role is null or f.role not in ('TRASH', 'SPAM'))
			    and (m.from_addresses ilike :like or m.to_addresses ilike :like or m.cc_addresses ilike :like)
			order by sort_at desc, m.id desc
			limit :scan
			"""
		val own = ownAddresses()
		val needle = query.lowercase()
		val seen = LinkedHashMap<String, Correspondent>()
		jdbc.query(
			sql,
			params,
			RowCallbackHandler { rs ->
				val at = rs.getTimestamp("sort_at").toInstant()
				listOf("from_addresses", "to_addresses", "cc_addresses")
					.flatMap { MailAddress.parseList(rs.getString(it)) }
					.filter { it.address.lowercase() !in own }
					.filter { it.address.lowercase().contains(needle) || it.name?.lowercase()?.contains(needle) == true }
					.forEach { address ->
						// Rows come newest first: the first sighting has the newest time; a later one may only add a name.
						val key = address.address.lowercase()
						val known = seen[key]
						if (known == null) {
							seen[key] = Correspondent(address.address, address.name, at)
						} else if (known.name == null && address.name != null) {
							seen[key] = known.copy(name = address.name)
						}
					}
			},
		)
		return seen.values.toList()
	}

	private fun ownAddresses(): Set<String> =
		jdbc
			.queryForList(
				"select lower(email_address) from mail_account union select lower(email) from mail_identity",
				MapSqlParameterSource(),
				String::class.java,
			).filterNotNull()
			.toSet()

	private fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

	companion object {
		private val REGEX_SPECIAL = Regex("""[\\.^$|?*+()\[\]{}-]""")

		/**
		 * A case-insensitive POSIX regex that finds any of [addresses] as a whole mailbox in a stored header such as
		 * `"Doe, Ann" <ann@example.com>, bob@example.com`: bounded by start/end, `<`/`>`, a comma or white space.
		 */
		fun addressPattern(addresses: Collection<String>): String =
			addresses.joinToString("|", prefix = "(^|[\\s<,])(", postfix = ")($|[\\s>,])") { address ->
				address.replace(REGEX_SPECIAL) { "\\" + it.value }
			}
	}
}
