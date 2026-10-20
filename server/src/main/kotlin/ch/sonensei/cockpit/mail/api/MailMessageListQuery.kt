package ch.sonensei.cockpit.mail.api

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Which messages a list shows. */
sealed interface MailListScope {
	/** The Inbox (role) folders of every account. */
	data object Unified : MailListScope

	/** The Inbox (role) folder of one account. */
	data class AccountInbox(
		val accountId: UUID,
	) : MailListScope

	data class Folder(
		val folderId: UUID,
	) : MailListScope
}

/**
 * One list row: a message, or in threaded mode the newest message of a conversation in the scope together with the
 * number of its messages ([threadSize]) and unread ones ([threadUnread]) in the scope. [sortAt] is the arrival
 * (INTERNALDATE; the row's creation when the server reported none).
 */
data class MailListRow(
	val messageId: UUID,
	val sortAt: Instant,
	val threadSize: Int,
	val threadUnread: Int,
)

/** Keyset position after which a page starts. */
data class MailListPosition(
	val sortAt: Instant,
	val messageId: UUID,
)

/**
 * Located messages of a [MailListScope], newest first (keyset pagination on arrival + id). Threaded mode groups by
 * account and thread key; a message without thread key is its own conversation.
 */
@Component
class MailMessageListQuery(
	private val jdbc: NamedParameterJdbcTemplate,
) {
	fun rows(
		scope: MailListScope,
		threaded: Boolean,
		after: MailListPosition?,
		limit: Int,
	): List<MailListRow> {
		val params = MapSqlParameterSource("limit", limit)
		val scopeCondition =
			when (scope) {
				MailListScope.Unified -> {
					"f.role = 'INBOX'"
				}

				is MailListScope.AccountInbox -> {
					params.addValue("accountId", scope.accountId)
					"f.account_id = :accountId and f.role = 'INBOX'"
				}

				is MailListScope.Folder -> {
					params.addValue("folderId", scope.folderId)
					"m.folder_id = :folderId"
				}
			}
		val afterCondition =
			after?.let {
				params.addValue("afterAt", Timestamp.from(it.sortAt)).addValue("afterId", it.messageId)
				"and (sort_at, id) < (:afterAt, :afterId)"
			} ?: ""
		val sql =
			if (threaded) {
				"""
				select id, sort_at, thread_size, thread_unread from (
				    select m.id, coalesce(m.received_at, m.created_at) as sort_at,
				        row_number() over (conversation order by coalesce(m.received_at, m.created_at) desc, m.id desc) as position,
				        count(*) over conversation as thread_size,
				        count(*) filter (where not m.seen) over conversation as thread_unread
				    from mail_message m join mail_folder f on f.id = m.folder_id
				    where m.uid is not null and $scopeCondition
				    window conversation as (partition by m.account_id, coalesce(m.thread_key, m.id::text))
				) t
				where position = 1 $afterCondition
				order by sort_at desc, id desc
				limit :limit
				"""
			} else {
				"""
				select id, sort_at, 1 as thread_size, case when seen then 0 else 1 end as thread_unread from (
				    select m.id, m.seen, coalesce(m.received_at, m.created_at) as sort_at
				    from mail_message m join mail_folder f on f.id = m.folder_id
				    where m.uid is not null and $scopeCondition
				) t
				where true $afterCondition
				order by sort_at desc, id desc
				limit :limit
				"""
			}
		return jdbc.query(sql, params) { rs, _ ->
			MailListRow(
				messageId = rs.getObject("id", UUID::class.java),
				sortAt = rs.getTimestamp("sort_at").toInstant(),
				threadSize = rs.getInt("thread_size"),
				threadUnread = rs.getInt("thread_unread"),
			)
		}
	}
}
