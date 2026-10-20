package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.mail.imap.ImapConnectionPool
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAddress
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.FolderNotFoundException
import jakarta.mail.MessagingException
import jakarta.mail.event.MessageChangedEvent
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.InternetHeaders
import jakarta.mail.internet.MimeUtility
import org.eclipse.angus.mail.iap.Response
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPMessage
import org.eclipse.angus.mail.imap.MessageVanishedEvent
import org.eclipse.angus.mail.imap.ResyncData
import org.eclipse.angus.mail.imap.protocol.BODY
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE
import org.eclipse.angus.mail.imap.protocol.ENVELOPE
import org.eclipse.angus.mail.imap.protocol.FetchResponse
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol
import org.eclipse.angus.mail.imap.protocol.IMAPResponse
import org.eclipse.angus.mail.imap.protocol.INTERNALDATE
import org.eclipse.angus.mail.imap.protocol.MODSEQ
import org.eclipse.angus.mail.imap.protocol.RFC822SIZE
import org.eclipse.angus.mail.imap.protocol.UID
import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.eclipse.angus.mail.imap.protocol.FLAGS as ImapFlags

/** The folder does not exist (any more) on the server. */
class RemoteFolderNotFoundException(
	path: String,
	cause: Throwable? = null,
) : RuntimeException("Folder not found on the server: $path", cause)

/**
 * [RemoteMailbox] over IMAP (Angus Mail) with connections from the [ImapConnectionPool]. Folders are opened with
 * `EXAMINE` (read-only, so the sync never marks anything as seen), with `(CONDSTORE)` or `(QRESYNC (...))` when the
 * server supports it. Headers are read with one `UID FETCH` per batch (ENVELOPE, BODYSTRUCTURE, `References`, `In-Reply-To`), the
 * snippet with a partial fetch of the first text part.
 */
@Component
class ImapRemoteMailbox(
	private val pool: ImapConnectionPool,
) : RemoteMailbox {
	override fun <T> withFolder(
		account: MailAccount,
		path: String,
		known: KnownFolderState?,
		block: (RemoteFolder) -> T,
	): T =
		pool.withStore(account) { store ->
			val folder = store.getFolder(path) as IMAPFolder
			val qresync = store.hasCapability("QRESYNC")
			val condstore = qresync || store.hasCapability("CONDSTORE")
			val events =
				try {
					when {
						qresync && known != null -> folder.open(Folder.READ_ONLY, ResyncData(known.uidValidity, known.highestModSeq))
						condstore -> folder.open(Folder.READ_ONLY, ResyncData.CONDSTORE).let { null }
						else -> folder.open(Folder.READ_ONLY).let { null }
					}
				} catch (e: FolderNotFoundException) {
					throw RemoteFolderNotFoundException(path, e)
				} catch (e: MessagingException) {
					throw MailConnectionException.of(e)
				}
			try {
				val remote = ImapRemoteFolder(folder, condstore, qresync, events?.let { qresyncChanges(folder, it) })
				block(remote)
			} catch (e: MessagingException) {
				throw MailConnectionException.of(e)
			} finally {
				runCatching { if (folder.isOpen) folder.close(false) }
			}
		}

	private fun qresyncChanges(
		folder: IMAPFolder,
		events: List<jakarta.mail.event.MailEvent>,
	): QresyncChanges {
		val vanished = events.filterIsInstance<MessageVanishedEvent>().flatMap { it.getUIDs().asList() }.toSet()
		val changed =
			events.filterIsInstance<MessageChangedEvent>().mapNotNull { event ->
				val message = event.message as? IMAPMessage ?: return@mapNotNull null
				val uid = folder.getUID(message).takeIf { it > 0 } ?: return@mapNotNull null
				RemoteFlagState(uid, flagsOf(message.flags), message.modSeq.takeIf { it > 0 })
			}
		return QresyncChanges(vanished, changed)
	}
}

private class ImapRemoteFolder(
	private val folder: IMAPFolder,
	private val condstore: Boolean,
	qresync: Boolean,
	override val qresyncChanges: QresyncChanges?,
) : RemoteFolder {
	override val status: RemoteFolderStatus =
		RemoteFolderStatus(
			uidValidity = folder.uidValidity,
			uidNext = folder.uidNext.takeIf { it > 0 } ?: ((uidsFrom(1).lastOrNull() ?: 0) + 1),
			highestModSeq = if (condstore) runCatching { folder.highestModSeq }.getOrNull()?.takeIf { it > 0 } else null,
			condstore = condstore,
			qresync = qresync,
		)

	override fun uidsSince(since: Instant): List<Long> = search("SINCE ${IMAP_DATE.format(since.atOffset(ZoneOffset.UTC))}")

	override fun uidsFrom(fromUid: Long): List<Long> = if (empty) emptyList() else search("UID $fromUid:*").filter { it >= fromUid }

	override fun receivedDates(uids: List<Long>): Map<Long, Instant> =
		if (uids.isEmpty()) {
			emptyMap()
		} else {
			fetch(uids, "UID INTERNALDATE")
				.mapNotNull { (uid, r) ->
					r
						.getItem(INTERNALDATE::class.java)
						?.date
						?.toInstant()
						?.let { uid to it }
				}.toMap()
		}

	override fun uidsWithMessageIds(messageIds: Collection<String>): List<Long> =
		messageIds
			.filter { id -> id.isNotEmpty() && id.all { it in ' '..'~' } }
			.distinct()
			.chunked(MESSAGE_ID_SEARCH_CHUNK)
			.flatMap { chunk ->
				// `OR a OR b c`: prefix ORs nest to the right, so n criteria need n - 1 ORs.
				val criteria = chunk.map { "HEADER Message-ID ${quoted(it)}" }
				search(criteria.dropLast(1).joinToString("") { "OR $it " } + criteria.last())
			}.distinct()
			.sorted()

	override fun headers(uids: List<Long>): List<RemoteMessage> {
		if (uids.isEmpty()) return emptyList()
		val items = "UID FLAGS INTERNALDATE RFC822.SIZE ENVELOPE BODYSTRUCTURE BODY.PEEK[HEADER.FIELDS (References In-Reply-To)]"
		val fetched = fetch(uids, if (condstore) "$items MODSEQ" else items)
		val structures = fetched.associate { (uid, response) -> uid to response.getItem(BODYSTRUCTURE::class.java) }
		val snippets = snippets(structures)
		return fetched.map { (uid, response) -> message(uid, response, structures[uid], snippets[uid]) }
	}

	override fun flagsFrom(fromUid: Long): List<RemoteFlagState> =
		if (empty) {
			emptyList()
		} else {
			fetch(listOf(fromUid), if (condstore) "UID FLAGS MODSEQ" else "UID FLAGS", openEnded = true).map { (uid, r) -> flagState(uid, r) }
		}

	override fun flagsChangedSince(
		fromUid: Long,
		modSeq: Long,
	): List<RemoteFlagState> =
		if (empty) {
			emptyList()
		} else {
			fetch(listOf(fromUid), "UID FLAGS MODSEQ", openEnded = true, modifier = " (CHANGEDSINCE $modSeq)").map { (uid, r) -> flagState(uid, r) }
		}

	/** `n:*` fails in an empty folder on some servers. */
	private val empty: Boolean get() = folder.messageCount == 0

	private fun flagState(
		uid: Long,
		response: FetchResponse,
	) = RemoteFlagState(
		uid,
		response.getItem(ImapFlags::class.java)?.let(::flagsOf) ?: RemoteFlags(),
		response.getItem(MODSEQ::class.java)?.modseq,
	)

	private fun message(
		uid: Long,
		response: FetchResponse,
		structure: BODYSTRUCTURE?,
		snippet: String?,
	): RemoteMessage {
		val envelope = response.getItem(ENVELOPE::class.java)
		// In-Reply-To is in the ENVELOPE too, but not every server fills it in (GreenMail does not).
		val threadHeaders = response.getItem(BODY::class.java)?.byteArrayInputStream?.let(::InternetHeaders)
		return RemoteMessage(
			uid = uid,
			messageId = envelope?.messageId?.trim()?.takeIf { it.isNotEmpty() },
			inReplyTo =
				(threadHeaders?.getHeader("In-Reply-To", " ") ?: envelope?.inReplyTo)?.let(::unfold)?.takeIf { it.isNotEmpty() },
			references = threadHeaders?.getHeader("References", " ")?.let(::unfold)?.takeIf { it.isNotEmpty() },
			subject = envelope?.subject?.let(::decodeText),
			from = addresses(envelope?.from),
			to = addresses(envelope?.to),
			cc = addresses(envelope?.cc),
			bcc = addresses(envelope?.bcc),
			replyTo = addresses(envelope?.replyTo),
			sentAt = envelope?.date?.toInstant(),
			receivedAt = response.getItem(INTERNALDATE::class.java)?.date?.toInstant(),
			flags = response.getItem(ImapFlags::class.java)?.let(::flagsOf) ?: RemoteFlags(),
			sizeBytes = response.getItem(RFC822SIZE::class.java)?.size?.toLong() ?: 0,
			hasAttachments = structure?.let(MimeStructure::hasAttachments) ?: false,
			snippet = snippet,
			modSeq = response.getItem(MODSEQ::class.java)?.modseq,
		)
	}

	/** Partial fetches of each message's first text part, one `UID FETCH` per distinct section. */
	private fun snippets(structures: Map<Long, BODYSTRUCTURE?>): Map<Long, String> {
		val parts = structures.mapNotNull { (uid, structure) -> structure?.let(MimeStructure::snippetPart)?.let { uid to it } }
		return parts
			.groupBy({ it.second.section }, { it })
			.flatMap { (section, group) ->
				val byUid = group.toMap()
				fetch(group.map { it.first }, "UID BODY.PEEK[$section]<0.$SNIPPET_FETCH_BYTES>").mapNotNull { (uid, response) ->
					val part = byUid[uid] ?: return@mapNotNull null
					val bytes = response.getItem(BODY::class.java)?.byteArray?.newBytes ?: return@mapNotNull null
					Snippets.of(bytes, part)?.let { uid to it }
				}
			}.toMap()
	}

	private fun search(criteria: String): List<Long> =
		command("UID SEARCH $criteria") { responses ->
			responses
				.filterIsInstance<IMAPResponse>()
				.filter { it.keyEquals("SEARCH") }
				.flatMap { response -> generateSequence { response.readLong().takeIf { it >= 0 } }.toList() }
				.distinct()
				.sorted()
		}

	/** `UID FETCH` of [uids] (with [openEnded], everything from the first UID on), as UID → response. */
	private fun fetch(
		uids: List<Long>,
		items: String,
		openEnded: Boolean = false,
		modifier: String = "",
	): List<Pair<Long, FetchResponse>> {
		val sets = if (openEnded) listOf("${uids.first()}:*") else uids.chunked(FETCH_CHUNK).map(UidSets::format)
		val wanted = if (openEnded) null else uids.toHashSet()
		return sets.flatMap { set ->
			command("UID FETCH $set ($items)$modifier") { responses ->
				responses.filterIsInstance<FetchResponse>().mapNotNull { response ->
					val uid = response.getItem(UID::class.java)?.uid ?: return@mapNotNull null
					// `n:*` also returns the last message when its UID is below n (RFC 9051 §6.4.8).
					if ((wanted != null && uid !in wanted) || (wanted == null && uid < uids.first())) null else uid to response
				}
			}
		}
	}

	/** Runs a raw command; unsolicited responses (EXISTS, EXPUNGE, ...) still reach the folder. */
	private fun <T> command(
		command: String,
		parse: (Array<Response>) -> T,
	): T {
		val result = ArrayList<T>(1)
		folder.doCommand { protocol: IMAPProtocol ->
			val responses = protocol.command(command, null)
			result += parse(responses)
			protocol.notifyResponseHandlers(responses.filter { it !is FetchResponse }.toTypedArray())
			protocol.handleResult(responses.last())
			null
		}
		return result.single()
	}

	private companion object {
		const val FETCH_CHUNK = 500
		const val MESSAGE_ID_SEARCH_CHUNK = 20
		const val SNIPPET_FETCH_BYTES = 2048

		/** An IMAP quoted string (RFC 9051 §4.3); the caller only passes printable ASCII. */
		fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

		val IMAP_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH)
	}
}

private fun flagsOf(flags: Flags): RemoteFlags =
	RemoteFlags(
		seen = flags.contains(Flags.Flag.SEEN),
		flagged = flags.contains(Flags.Flag.FLAGGED),
		answered = flags.contains(Flags.Flag.ANSWERED),
		draft = flags.contains(Flags.Flag.DRAFT),
		keywords = flags.userFlags.sorted(),
	)

private fun unfold(value: String): String = MimeUtility.unfold(value).trim()

private fun decodeText(value: String): String =
	try {
		MimeUtility.decodeText(MimeUtility.unfold(value))
	} catch (_: IOException) {
		value
	}

private fun addresses(list: Array<InternetAddress>?): List<MailAddress> =
	list
		.orEmpty()
		.flatMap { if (it.isGroup) it.getGroup(false)?.toList().orEmpty() else listOf(it) }
		.mapNotNull { a ->
			a.address?.takeIf { address -> address.indexOf('@') in 1 until address.length - 1 }?.let {
				MailAddress(it, a.personal?.takeIf(String::isNotBlank))
			}
		}

/** Compact UID sets for IMAP commands: `[1, 2, 3, 7, 9, 10]` → `1:3,7,9:10`. */
internal object UidSets {
	fun format(uids: List<Long>): String {
		val sorted = uids.distinct().sorted()
		val ranges = mutableListOf<String>()
		var start = sorted.first()
		var end = start
		for (uid in sorted.drop(1)) {
			if (uid == end + 1) {
				end = uid
			} else {
				ranges += if (start == end) "$start" else "$start:$end"
				start = uid
				end = uid
			}
		}
		ranges += if (start == end) "$start" else "$start:$end"
		return ranges.joinToString(",")
	}
}

/** The text part a snippet is read from: its IMAP section, transfer encoding, charset and whether it is HTML. */
internal data class SnippetPart(
	val section: String,
	val encoding: String?,
	val charset: String?,
	val html: Boolean,
)

/** Reads a BODYSTRUCTURE: attachments and the part a snippet comes from. */
internal object MimeStructure {
	/** Whether the message has a part marked as attachment, or a named part that is not inline in an HTML body. */
	fun hasAttachments(structure: BODYSTRUCTURE): Boolean = leaves(structure, "", related = false).any { it.attachment }

	/** The first text/plain part that is no attachment, else the first such text/html part. */
	fun snippetPart(structure: BODYSTRUCTURE): SnippetPart? {
		val texts = leaves(structure, "", related = false).filter { !it.attachment && it.part.type.equals("text", ignoreCase = true) }
		val leaf =
			texts.firstOrNull { it.part.subtype.equals("plain", ignoreCase = true) }
				?: texts.firstOrNull { it.part.subtype.equals("html", ignoreCase = true) }
				?: return null
		return SnippetPart(
			leaf.section,
			leaf.part.encoding,
			leaf.part.cParams?.get("charset"),
			leaf.part.subtype.equals("html", ignoreCase = true),
		)
	}

	private class Leaf(
		val part: BODYSTRUCTURE,
		val section: String,
		val attachment: Boolean,
	)

	private fun leaves(
		part: BODYSTRUCTURE,
		section: String,
		related: Boolean,
	): List<Leaf> {
		if (part.isMulti) {
			val inRelated = related || part.subtype.equals("related", ignoreCase = true)
			return part.bodies.orEmpty().withIndex().flatMap { (i, child) ->
				leaves(child, if (section.isEmpty()) "${i + 1}" else "$section.${i + 1}", inRelated)
			}
		}
		val disposition = part.disposition?.lowercase()
		val named = part.dParams?.get("filename") != null || part.cParams?.get("name") != null
		val nested = part.type.equals("message", ignoreCase = true)
		val attachment = disposition == "attachment" || nested || (named && disposition != "inline" && !related)
		// A single-part message has its body at section TEXT.
		return listOf(Leaf(part, section.ifEmpty { "TEXT" }, attachment))
	}
}

/** Turns the first bytes of a text part into a short plain-text snippet. */
internal object Snippets {
	const val MAX_LENGTH = 200
	private val WHITESPACE = Regex("""\s+""")

	fun of(
		bytes: ByteArray,
		part: SnippetPart,
	): String? {
		val decoded = decode(bytes, part.encoding)
		val charset =
			part.charset
				?.let { runCatching { Charset.forName(MimeUtility.javaCharset(it)) }.getOrNull() }
				?: Charsets.UTF_8
		var text = String(decoded, charset)
		text =
			if (part.html) {
				Jsoup.parse(text).text()
			} else {
				text.lineSequence().filterNot { it.trimStart().startsWith(">") }.joinToString(" ")
			}
		text =
			text
				.replace(WHITESPACE, " ")
				.trim()
				.trimEnd('�')
				.trim()
		return text.take(MAX_LENGTH).takeIf { it.isNotEmpty() }
	}

	/** Decodes base64/quoted-printable; the bytes are cut off, so a broken tail is dropped instead of failing. */
	private fun decode(
		bytes: ByteArray,
		encoding: String?,
	): ByteArray {
		val stream =
			try {
				MimeUtility.decode(ByteArrayInputStream(bytes), encoding?.lowercase() ?: "7bit")
			} catch (_: MessagingException) {
				return bytes
			}
		val out = ByteArrayOutputStream()
		try {
			while (true) {
				val b = stream.read()
				if (b < 0) break
				out.write(b)
			}
		} catch (_: IOException) {
			// The truncated end of the part.
		}
		return out.toByteArray()
	}
}
