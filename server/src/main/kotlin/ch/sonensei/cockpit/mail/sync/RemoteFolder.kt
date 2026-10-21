package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAddress
import java.time.Instant

/** The flags of a message as the server reports them. */
data class RemoteFlags(
	val seen: Boolean = false,
	val flagged: Boolean = false,
	val answered: Boolean = false,
	val draft: Boolean = false,
	/** Keywords other than the system flags, sorted. */
	val keywords: List<String> = emptyList(),
)

/** The flags of message [uid] and its MODSEQ (CONDSTORE servers only). */
data class RemoteFlagState(
	val uid: Long,
	val flags: RemoteFlags,
	val modSeq: Long? = null,
)

/** The headers of one message, as cached in `mail_message` (SPEC.md §7.2). */
data class RemoteMessage(
	val uid: Long,
	val messageId: String?,
	val inReplyTo: String?,
	val references: String?,
	val subject: String?,
	val from: List<MailAddress>,
	val to: List<MailAddress>,
	val cc: List<MailAddress>,
	val bcc: List<MailAddress>,
	val replyTo: List<MailAddress>,
	val sentAt: Instant?,
	val receivedAt: Instant?,
	val flags: RemoteFlags,
	val sizeBytes: Long,
	val hasAttachments: Boolean,
	val snippet: String?,
	val modSeq: Long? = null,
)

/** What `SELECT`/`EXAMINE` reported for a folder, and which sync extensions the server offers. */
data class RemoteFolderStatus(
	val uidValidity: Long,
	val uidNext: Long,
	/** HIGHESTMODSEQ; null without CONDSTORE (or when the folder has none, RFC 7162 NOMODSEQ). */
	val highestModSeq: Long?,
	val condstore: Boolean,
	val qresync: Boolean,
)

/**
 * Changes since a known state that a QRESYNC `SELECT` reported (RFC 7162 §3.2.5): expunged UIDs and messages
 * whose flags changed.
 */
data class QresyncChanges(
	val vanished: Set<Long>,
	val changed: List<RemoteFlagState>,
)

/** One folder opened read-only on the server. Every list of UIDs is ascending. */
interface RemoteFolder {
	val status: RemoteFolderStatus

	/** Set when the folder was opened with a QRESYNC state that the server accepted. */
	val qresyncChanges: QresyncChanges?

	/** UIDs of messages that arrived (INTERNALDATE) on or after the day of [since]. */
	fun uidsSince(since: Instant): List<Long>

	/** UIDs of all messages with a UID of at least [fromUid]. */
	fun uidsFrom(fromUid: Long): List<Long>

	/** The arrival time (INTERNALDATE) of the messages with these UIDs (UIDs no longer on the server are left out). */
	fun receivedDates(uids: List<Long>): Map<Long, Instant>

	/**
	 * UIDs of the messages whose `Message-ID` header contains one of [messageIds] (a server-side substring match, so
	 * the caller compares the fetched headers). Ids the server cannot be asked for (non-ASCII, line breaks) are skipped.
	 */
	fun uidsWithMessageIds(messageIds: Collection<String>): List<Long>

	/**
	 * UIDs of the messages that contain every one of [words] in their headers or body (IMAP `SEARCH TEXT`, matched by
	 * the server, usually as case-insensitive substrings).
	 */
	fun search(words: List<String>): List<Long>

	/** Headers of the messages with these UIDs (UIDs no longer on the server are left out). */
	fun headers(uids: List<Long>): List<RemoteMessage>

	/** Flags of every message with a UID of at least [fromUid]. */
	fun flagsFrom(fromUid: Long): List<RemoteFlagState>

	/** Flags of the messages with a UID of at least [fromUid] whose MODSEQ is above [modSeq] (CONDSTORE). */
	fun flagsChangedSince(
		fromUid: Long,
		modSeq: Long,
	): List<RemoteFlagState>
}

/** The state a folder was last synced with, to open it with QRESYNC. */
data class KnownFolderState(
	val uidValidity: Long,
	val highestModSeq: Long,
)

/** Opens folders of an account on its server. */
interface RemoteMailbox {
	/**
	 * Runs [block] with folder [path] opened read-only. With [known] and a server that supports QRESYNC, the folder
	 * is opened with that state and [RemoteFolder.qresyncChanges] holds what changed since.
	 */
	fun <T> withFolder(
		account: MailAccount,
		path: String,
		known: KnownFolderState?,
		block: (RemoteFolder) -> T,
	): T
}
