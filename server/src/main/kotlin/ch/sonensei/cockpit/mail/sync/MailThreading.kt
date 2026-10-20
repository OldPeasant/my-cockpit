package ch.sonensei.cockpit.mail.sync

/** What the cache already knows about conversations of one account, for [MailThreading.assign]. */
interface ThreadIndex {
	/** The thread keys of cached messages whose Message-ID is one of [messageIds], by Message-ID. */
	fun keysByMessageId(messageIds: Collection<String>): Map<String, String>

	/** Those of [keys] that some cached message of the account has as its thread key. */
	fun existingKeys(keys: Collection<String>): Set<String>
}

/**
 * The thread key of a message and the existing keys that now belong to the same conversation. The caller stores
 * [key] on the message and rewrites every cached message whose key is in [merged] to [key].
 */
data class ThreadAssignment(
	val key: String,
	val merged: Set<String>,
)

/**
 * Conversations (SPEC.md §7.2): messages are grouped by `References` / `In-Reply-To`, with the normalized subject as
 * fallback for messages that have neither.
 *
 * A thread key is the Message-ID of the conversation's root (the first entry of `References`, else `In-Reply-To`),
 * or `subject:<normalized subject>` for a message without either header. Whatever the arrival order, a message joins
 * the conversation of every cached message it refers to and of every cached message that refers to it; when it
 * connects conversations that were apart until now (e.g. a reply that arrived before its parent), they merge into
 * one ([ThreadAssignment.merged]).
 */
object MailThreading {
	/** Thread key prefix of conversations grouped by subject. */
	const val SUBJECT_KEY_PREFIX = "subject:"

	/** Reply and forward prefixes in common languages (Re, Fwd, Aw/Wg German, Sv/Vs Nordic, Tr/Rif, Antw, Odp, ...). */
	private val REPLY_PREFIX =
		Regex(
			"""^\s*(?:(?:re|fw|fwd|aw|wg|sv|vs|vb|tr|rif|r|antw|odp|ynt|res|enc|rv)(?:\s*\[\d+])?\s*[:：]|\[[^\]]*])\s*""",
			RegexOption.IGNORE_CASE,
		)
	private val WHITESPACE = Regex("""\s+""")
	private val MESSAGE_ID = Regex("""<[^<>\s]+>""")

	/**
	 * The subject without reply/forward prefixes and mailing list tags (`[list]`), whitespace collapsed, lower case:
	 * `Re: AW: [team] Budget  2026` → `budget 2026`. Empty for a missing or prefix-only subject.
	 */
	fun normalizeSubject(subject: String?): String {
		var rest = subject.orEmpty()
		while (true) {
			val match = REPLY_PREFIX.find(rest) ?: break
			rest = rest.substring(match.range.last + 1)
		}
		return rest.replace(WHITESPACE, " ").trim().lowercase()
	}

	/** The Message-IDs (with angle brackets) in a `References` or `In-Reply-To` value, in order, without duplicates. */
	fun messageIds(header: String?): List<String> =
		header
			?.let { value ->
				MESSAGE_ID
					.findAll(value)
					.map { it.value }
					.distinct()
					.toList()
			}.orEmpty()

	/**
	 * The thread key of a message with these headers. [messageId] is the message's own `Message-ID` (null when it has
	 * none); [fallbackId] identifies a message that has neither references nor a usable subject (e.g. its row id).
	 */
	fun assign(
		messageId: String?,
		inReplyTo: String?,
		references: String?,
		subject: String?,
		fallbackId: String,
		index: ThreadIndex,
	): ThreadAssignment {
		val parents = (messageIds(references) + messageIds(inReplyTo)).distinct().filter { it != messageId }
		val known = if (parents.isEmpty()) emptyMap() else index.keysByMessageId(parents)
		// Conversations that may already exist under a provisional key: replies that arrived earlier use the Message-ID
		// of their root (this message or one of its parents) as key.
		val provisional = (listOfNotNull(messageId) + parents).distinct()
		val provisionalExisting = index.existingKeys(provisional)
		val default =
			parents.firstOrNull()
				?: normalizeSubject(subject).takeIf { it.isNotEmpty() }?.let { SUBJECT_KEY_PREFIX + it }
				?: messageId
				?: "id:$fallbackId"
		// The conversation of the first cached parent (parents are root-first). With no parent cached yet, a reply is
		// keyed by its root, so that the root and later replies find it there; conversations keyed provisionally by this
		// message or an intermediate parent merge into it. Only a message without parents keeps a provisional key.
		val key =
			parents.firstNotNullOfOrNull { known[it] }
				?: parents.firstOrNull()
				?: provisional.firstOrNull { it in provisionalExisting }
				?: default
		val merged = (known.values + provisionalExisting).toSet() - key
		return ThreadAssignment(key, merged)
	}
}
