package ch.sonensei.cockpit.core.push.domain

import java.util.UUID

/** What a push is about; [wire] is the `kind` in its payload. */
enum class PushKind(
	val wire: String,
) {
	/** A reminder fired; the id is the reminder's. */
	REMINDER("reminder"),

	/** Unread mail arrived in an Inbox; the id is the newest new message's (`mail_message.id`). */
	MAIL("mail"),
}

/**
 * A push to every registered device. Modules publish it as a Spring application event; [PushDispatcher] sends it after
 * the publishing transaction commits. It carries only [kind] and [id], never item contents: the app fetches the rest.
 */
data class PushMessage(
	val kind: PushKind,
	val id: UUID,
) {
	/** The push payload, `{"kind":"…","id":"…"}`. */
	fun toJson(): String = """{"kind":"${kind.wire}","id":"$id"}"""
}
