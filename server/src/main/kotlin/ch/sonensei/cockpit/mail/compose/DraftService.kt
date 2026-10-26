package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.mail.api.MailAttachment
import ch.sonensei.cockpit.mail.api.MailContentLoader
import ch.sonensei.cockpit.mail.api.MimeContent
import ch.sonensei.cockpit.mail.imap.ImapConnectionPool
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailBodyRepository
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailIdentity
import ch.sonensei.cockpit.mail.model.MailIdentityRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageLocator
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import ch.sonensei.cockpit.mail.sanitize.HtmlMailSanitizer
import ch.sonensei.cockpit.mail.sanitize.RemoteImagePolicy
import ch.sonensei.cockpit.mail.sanitize.SanitizeOptions
import ch.sonensei.cockpit.mail.sync.MailHeaderSyncService
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.FolderNotFoundException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Part
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.io.IOException
import java.io.OutputStream
import java.time.Clock
import java.util.Date
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Where an attachment of a saved draft comes from. */
sealed interface DraftAttachmentRef {
	/** An attachment the draft already has (its IMAP section in the current version). */
	data class Existing(
		val partId: String,
	) : DraftAttachmentRef

	/** A file of the drive (SPEC.md §8). */
	data class File(
		val fileId: UUID,
	) : DraftAttachmentRef
}

/** What the user composed. [attachments] `null` keeps the draft's current attachments. */
data class DraftInput(
	val identityId: UUID?,
	val to: List<MailAddress>,
	val cc: List<MailAddress>,
	val bcc: List<MailAddress>,
	val subject: String?,
	val html: String?,
	val text: String?,
	val attachments: List<DraftAttachmentRef>?,
)

/** A file uploaded directly into a draft. */
class DraftUpload(
	val filename: String,
	val contentType: String,
	val bytes: ByteArray,
)

/**
 * A draft as stored in the account's Drafts folder: its cached headers ([message]), the identity it is sent with
 * (`null` when its `From` matches none), its body ([html] sanitized) and attachments. [version] is the IMAP UID of the
 * current version; it is the draft's ETag.
 */
data class Draft(
	val message: MailMessage,
	val identityId: UUID?,
	val html: String?,
	val text: String?,
	val attachments: List<MailAttachment>,
) {
	val version: Long get() = checkNotNull(message.uid)
}

/** The outcome of sending: the cached copy in the Sent folder when it is known yet, and what happened besides SMTP. */
data class SentDraft(
	val sentMessageId: UUID?,
	val appendedToSent: Boolean,
	val draftRemoved: Boolean,
)

/**
 * Drafts and sending (SPEC.md §7.3, §7.4). A draft lives in the account's Drafts folder on the mail server: every save
 * appends the new version (flags `\Draft \Seen`) and deletes the previous one. All versions share one `Message-ID`, so
 * the header sync re-attaches the new UID to the draft's cached row (see [ch.sonensei.cockpit.mail.sync.MailHeaderStore]),
 * and the draft keeps its id; its ETag is the UID of the current version, so a save based on an older version fails
 * with 412. Saves of the same draft run one after another.
 *
 * Sending reads the stored draft, sends it over SMTP, appends it to the Sent folder (not for Google and Microsoft
 * accounts, whose servers keep a copy themselves) and deletes the draft. The sent message keeps the draft's
 * `Message-ID`, so the draft's id then resolves to the copy in Sent. Once SMTP accepted the message, failures to append
 * or to delete only show in the result.
 */
@Service
class DraftService(
	private val accounts: MailAccountRepository,
	private val identities: MailIdentityRepository,
	private val folders: MailFolderRepository,
	private val messages: MailMessageRepository,
	private val bodies: MailBodyRepository,
	private val locator: MailMessageLocator,
	private val pool: ImapConnectionPool,
	private val sync: MailHeaderSyncService,
	private val loader: MailContentLoader,
	private val sanitizer: HtmlMailSanitizer,
	private val sender: MailSender,
	private val files: FilesService,
	private val transactions: TransactionTemplate,
	private val properties: ComposeProperties,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val locks = Array(LOCK_STRIPES) { ReentrantLock() }

	/** @throws NotFoundException when [id] is no message in a Drafts folder */
	fun get(id: UUID): Draft = view(draftRow(id))

	/**
	 * Saves a new draft in the Drafts folder of [accountId].
	 *
	 * @throws NotFoundException for an unknown account or file
	 * @throws InvalidRequestException for an identity of another account, an attachment by `partId`, too large a draft
	 * @throws ConflictException when the account has no Drafts folder
	 */
	fun create(
		accountId: UUID,
		input: DraftInput,
	): Draft {
		val account = accounts.findById(accountId).orElse(null) ?: throw NotFoundException("Mail account not found")
		val drafts = draftsFolder(account)
		if (input.attachments.orEmpty().any { it is DraftAttachmentRef.Existing }) {
			throw InvalidRequestException("attachments", "a new draft can only attach files (fileId)")
		}
		val composed = compose(account, input, newMessageId(account), inReplyTo = null, references = null)
		return store(account, drafts, previous = null) { composed.withAttachments(it, input.attachments.orEmpty(), emptyList()) }
	}

	/**
	 * Replaces the content of draft [id] (threading headers stay). Its attachments are [DraftInput.attachments] when
	 * given (current ones by `partId`, files by `fileId`), else the current ones.
	 *
	 * @throws NotFoundException for an unknown draft or file
	 * @throws InvalidRequestException for an unknown `partId`, an identity of another account, too large a draft
	 */
	fun update(
		id: UUID,
		ifMatch: String?,
		input: DraftInput,
	): Draft =
		locked(id) { row, account, drafts ->
			ETags.checkIfMatch(ifMatch, checkNotNull(row.uid))
			val current = loader.load(row).attachments
			val refs = input.attachments ?: current.map { DraftAttachmentRef.Existing(it.partId) }
			val unknown =
				refs.filterIsInstance<DraftAttachmentRef.Existing>().map { it.partId }.filter { partId ->
					current.none {
						it.partId ==
							partId
					}
				}
			if (unknown.isNotEmpty()) throw InvalidRequestException("attachments", "unknown partId ${unknown.first()}")
			val composed = compose(account, input, row.messageIdHeader ?: newMessageId(account), row.inReplyTo, row.referencesHeader)
			store(account, drafts, previous = row) { old -> composed.withAttachments(old, refs, current) }
		}

	/**
	 * Adds an uploaded file to draft [id], keeping everything else of the stored version.
	 *
	 * @throws InvalidRequestException when the draft would become too large
	 */
	fun attach(
		id: UUID,
		ifMatch: String?,
		upload: DraftUpload,
	): Draft =
		locked(id) { row, account, drafts ->
			ETags.checkIfMatch(ifMatch, checkNotNull(row.uid))
			if (upload.bytes.size > limit()) throw tooLarge()
			val messageId = row.messageIdHeader ?: newMessageId(account)
			store(account, drafts, previous = row) { old ->
				val copy = FixedIdMimeMessage(checkNotNull(old) as MimeMessage, messageId)
				// Counted on the fully read copy, like a save counts; thrown here, it is reported as a 400.
				if (sizeOf(copy) + upload.bytes.size > limit()) throw tooLarge()
				val part =
					MimeBodyPart().apply {
						setContent(upload.bytes, upload.contentType)
						disposition = Part.ATTACHMENT
						fileName = MimeUtility.encodeText(upload.filename, "UTF-8", null)
					}
				// Copied first: the content of a copy is read from its own bytes, so it can be changed.
				val existing = copy.content
				if (copy.isMimeType("multipart/mixed") && existing is MimeMultipart) {
					existing.addBodyPart(part)
					copy.setContent(existing)
				} else {
					val body = MimeBodyPart()
					body.setContent(existing, copy.contentType)
					copy.setContent(MimeMultipart("mixed", body, part))
				}
				copy.sentDate = Date.from(clock.instant())
				copy.setFlags(Flags(Flags.Flag.DRAFT), true)
				copy.setFlags(Flags(Flags.Flag.SEEN), true)
				copy.saveChanges()
				copy
			}
		}

	/** Deletes draft [id] from the server. */
	fun delete(
		id: UUID,
		ifMatch: String?,
	) {
		locked(id) { row, account, drafts ->
			ETags.checkIfMatch(ifMatch, checkNotNull(row.uid))
			when (pool.withStore(account) { store -> remove(store, drafts, checkNotNull(row.uid)) }) {
				true -> Unit
				false -> throw NotFoundException(DRAFT_NOT_FOUND)
			}
			resync(drafts)
		}
	}

	/**
	 * Sends draft [id] with its stored content.
	 *
	 * @throws InvalidRequestException when the draft has no recipient or is too large (changed by another client)
	 * @throws MailSendRejectedException when the SMTP server refused the message or a recipient
	 * @throws MailConnectionException when a mail server failed before the message was sent
	 */
	fun send(
		id: UUID,
		ifMatch: String?,
	): SentDraft =
		locked(id) { row, account, drafts ->
			val uid = checkNotNull(row.uid)
			ETags.checkIfMatch(ifMatch, uid)
			val messageId = row.messageIdHeader ?: newMessageId(account)
			val outgoing =
				pool.withStore(account) { store -> read(store, drafts, uid)?.let { FixedIdMimeMessage(it, messageId) } }
					?: throw NotFoundException(DRAFT_NOT_FOUND)
			if (outgoing.allRecipients.isNullOrEmpty()) throw InvalidRequestException("to", "a draft needs a recipient to be sent")
			// Backstop: every save checks the size, but a draft can also be changed by another mail client.
			if (sizeOf(outgoing) > limit()) throw tooLarge()
			outgoing.sentDate = Date.from(clock.instant())
			outgoing.setFlags(Flags(Flags.Flag.DRAFT), false)
			outgoing.setFlags(Flags(Flags.Flag.SEEN), true)
			outgoing.saveChanges()
			sender.send(account, outgoing)
			val sent = folders.findByAccountIdAndRole(account.id, MailFolderRole.SENT)?.takeIf { it.selectable }
			val appended =
				sent != null && account.type == MailAccountType.IMAP_SMTP && afterSend("append to Sent") { append(account, sent, outgoing) }
			val removed = afterSend("delete the sent draft") { pool.withStore(account) { remove(it, drafts, uid) } }
			if (removed) detach(row.id, drafts.id, uid)
			afterSend("sync Drafts") { resync(drafts) }
			val copy =
				sent?.let {
					afterSend("sync Sent") { resync(it) }
					located(account.id, it.id, messageId)
				}
			SentDraft(copy?.id, appended, removed)
		}

	/**
	 * Creates a reply, reply-all or forward of message [id] as a new draft in the Drafts folder of its account (see
	 * [ReplyComposer]); a forward carries the original's attachments. It is sent as [identityId], by default the
	 * identity the original was addressed to, else the account's default identity, else the account address.
	 */
	fun respond(
		id: UUID,
		mode: ResponseMode,
		identityId: UUID?,
	): Draft {
		val original = locator.current(id) ?: throw NotFoundException("Mail message not found")
		val account = accounts.findById(original.accountId).orElse(null) ?: throw NotFoundException("Mail message not found")
		val drafts = draftsFolder(account)
		val content = loader.load(original)
		val sanitized = content.textHtml?.let { sanitizer.sanitize(it, KEEP_REMOTE_IMAGES).html }
		val quotedHtml =
			when {
				sanitized == null -> ComposeText.textToHtml(content.textPlain.orEmpty())
				// A reply carries no attachments, so its inline images would be broken.
				mode != ResponseMode.FORWARD -> withoutInlineImages(sanitized)
				else -> sanitized
			}
		val quotedText = content.textPlain ?: sanitized?.let(ComposeText::plainText).orEmpty()
		val own = ownAddresses(account)
		val response = ReplyComposer.compose(mode, original, quotedHtml, quotedText, own)
		val identity =
			identityId
				?: identities
					.findByAccountIdOrderByPositionAscIdAsc(account.id)
					.firstOrNull { identity ->
						(original.to + original.cc + original.bcc).any { it.address.equals(identity.email, ignoreCase = true) }
					}?.id
		val input = DraftInput(identity, response.to, response.cc, emptyList(), response.subject, response.html, response.text, emptyList())
		val composed = compose(account, input, newMessageId(account), response.inReplyTo, response.references)
		val attachments = if (mode == ResponseMode.FORWARD) forwardedAttachments(original, content.attachments) else emptyList()
		return store(account, drafts, previous = null) { composed.copy(attachments = attachments).also(::checkSize) }
	}

	// --- composing -------------------------------------------------------------------------------------------------

	/** The draft without attachments; [withAttachments] adds them. */
	private fun compose(
		account: MailAccount,
		input: DraftInput,
		messageId: String,
		inReplyTo: String?,
		references: String?,
	): ComposedMessage {
		val identity = identity(account, input.identityId)
		val from =
			identity?.let { MailAddress(it.email, it.name.ifBlank { null }) }
				?: MailAddress(account.emailAddress, account.displayName.ifBlank { null })
		val html = input.html?.takeIf { it.isNotBlank() }
		return ComposedMessage(
			messageId = messageId,
			from = from,
			replyTo = identity?.replyTo?.let { MailAddress.parseList(it).firstOrNull() },
			to = input.to,
			cc = input.cc,
			bcc = input.bcc,
			subject = input.subject,
			html = html,
			text = input.text ?: html?.let(ComposeText::plainText).orEmpty(),
			inReplyTo = inReplyTo,
			references = references,
			attachments = emptyList(),
		)
	}

	/**
	 * This message with the attachments [refs]: files are read from the drive, existing ones from [old] (the stored version,
	 * described by [current]).
	 */
	private fun ComposedMessage.withAttachments(
		old: Message?,
		refs: List<DraftAttachmentRef>,
		current: List<MailAttachment>,
	): ComposedMessage {
		// A running total, so no more than the limit (plus one byte) is ever read into memory.
		var total = sizeBytes
		if (total > limit()) throw tooLarge()
		val attachments =
			refs.map { ref ->
				val remaining = minOf(limit() - total + 1, Int.MAX_VALUE.toLong()).toInt()
				val attachment =
					when (ref) {
						is DraftAttachmentRef.File -> {
							val file = files.file(ref.fileId)
							ComposedAttachment(file.name, file.mime, files.open(file.id).use { it.readNBytes(remaining) })
						}
						is DraftAttachmentRef.Existing -> {
							val meta = current.first { it.partId == ref.partId }
							val part = old?.let { MimeContent.findPart(it, ref.partId) } ?: throw DraftChangedException()
							val bytes = part.inputStream.use { it.readNBytes(remaining) }
							ComposedAttachment(meta.filename, meta.contentType, bytes, meta.inline, meta.contentId)
						}
					}
				total += attachment.bytes.size
				if (total > limit()) throw tooLarge()
				attachment
			}
		return copy(attachments = attachments)
	}

	private fun forwardedAttachments(
		original: MailMessage,
		parts: List<MailAttachment>,
	): List<ComposedAttachment> {
		if (parts.isEmpty()) return emptyList()
		return loader.withRemoteMessage(original) { root ->
			var total = 0L
			parts.mapNotNull { meta ->
				val part = MimeContent.findPart(root, meta.partId) ?: return@mapNotNull null
				val bytes = part.inputStream.use { it.readNBytes(limit() + 1) }
				total += bytes.size
				// Returned, not thrown, inside the IMAP block: a throw there would count as a server failure.
				if (total > limit()) return@withRemoteMessage null
				ComposedAttachment(meta.filename, meta.contentType, bytes, meta.inline, meta.contentId)
			}
		} ?: throw tooLarge()
	}

	private fun checkSize(message: ComposedMessage) {
		if (message.sizeBytes > limit()) throw tooLarge()
	}

	/**
	 * The size of a fully read stored draft, counted like [ComposedMessage.sizeBytes]: the text lengths plus the exact
	 * decoded attachment bytes (not the estimate of [MailAttachment.sizeBytes], which counts base64 line breaks too).
	 * Stops counting once it is over the limit.
	 */
	private fun sizeOf(message: MimeMessage): Long {
		val content = MimeContent.read(message, limit() + 1)
		var total = (content.textPlain?.length ?: 0).toLong() + (content.textHtml?.length ?: 0)
		for (attachment in content.attachments) {
			if (total > limit()) break
			val part = MimeContent.findPart(message, attachment.partId) ?: continue
			total += part.inputStream.use { it.transferTo(OutputStream.nullOutputStream()) }
		}
		return total
	}

	private fun limit(): Int = properties.maxMessageSize.toBytes().toInt()

	private fun tooLarge() =
		InvalidRequestException("attachments", "the message must not be larger than ${properties.maxMessageSize.toMegabytes()} MB")

	/** The identity a draft is From: [identityId], else the default identity; null means the account address. */
	private fun identity(
		account: MailAccount,
		identityId: UUID?,
	): MailIdentity? {
		val all = identities.findByAccountIdOrderByPositionAscIdAsc(account.id)
		if (identityId == null) return all.firstOrNull { it.isDefault }
		return all.firstOrNull { it.id == identityId } ?: throw InvalidRequestException("identityId", "is not an identity of the account")
	}

	private fun ownAddresses(account: MailAccount): Set<String> =
		buildSet {
			add(account.emailAddress.lowercase())
			identities.findByAccountIdOrderByPositionAscIdAsc(account.id).forEach { add(it.email.lowercase()) }
		}

	private fun withoutInlineImages(html: String): String {
		if (!html.contains("cid:", ignoreCase = true)) return html
		val document = Jsoup.parseBodyFragment(html)
		document.outputSettings().prettyPrint(false).charset(Charsets.UTF_8)
		document.select("img[src^=cid:]").remove()
		return document.body().html()
	}

	/** `<uuid@domain>` with the domain of the account's address. */
	private fun newMessageId(account: MailAccount): String =
		"<${UuidV7.generate()}@${account.emailAddress.substringAfterLast('@', "cockpit.invalid")}>"

	// --- storing ---------------------------------------------------------------------------------------------------

	/**
	 * Appends the draft that [build] makes (from the stored version [previous], read on the same connection) to
	 * [drafts], deletes [previous], and syncs the folder so the cached row follows.
	 */
	private fun store(
		account: MailAccount,
		drafts: MailFolder,
		previous: MailMessage?,
		build: (Message?) -> Any,
	): Draft {
		val previousUid = previous?.uid
		var messageId: String? = null
		val outcome =
			pool.withStore(account) { store ->
				val folder = store.getFolder(drafts.path) as IMAPFolder
				try {
					folder.open(Folder.READ_WRITE)
				} catch (e: FolderNotFoundException) {
					return@withStore Stored.Gone
				} catch (e: MessagingException) {
					throw MailConnectionException.of(e)
				}
				try {
					val old =
						previousUid?.let {
							if (drafts.uidValidity != null && folder.uidValidity != drafts.uidValidity) return@withStore Stored.Gone
							folder.getMessageByUID(it) ?: return@withStore Stored.Gone
						}
					val mime =
						try {
							when (val built = build(old)) {
								is MimeMessage -> built
								is ComposedMessage -> DraftMime.build(built, clock.instant(), draft = true)
								else -> error("Unexpected draft $built")
							}
						} catch (e: DraftChangedException) {
							return@withStore Stored.Gone
						} catch (e: RuntimeException) {
							// A validation failure (unknown file, too large): reported after the connection is back.
							return@withStore Stored.Invalid(e)
						}
					messageId = mime.messageID
					folder.appendMessages(arrayOf(mime))
					if (old != null) {
						folder.setFlags(arrayOf(old), Flags(Flags.Flag.DELETED), true)
						expunge(store, folder, old)
					}
					Stored.Done
				} catch (e: MessagingException) {
					throw MailConnectionException.of(e)
				} catch (e: IOException) {
					throw MailConnectionException.of(e)
				} finally {
					runCatching { if (folder.isOpen) folder.close(false) }
				}
			}
		when (outcome) {
			Stored.Gone -> throw NotFoundException(DRAFT_NOT_FOUND)
			is Stored.Invalid -> throw outcome.cause
			Stored.Done -> Unit
		}
		if (previous != null && previousUid != null) detach(previous.id, drafts.id, previousUid)
		resync(drafts)
		val row = located(account.id, drafts.id, checkNotNull(messageId)) ?: throw ConflictException(SAVED_NOT_VISIBLE)
		// The cached body (if any) is the one of the previous version.
		transactions.executeWithoutResult { bodies.findByMessageId(row.id)?.let(bodies::delete) }
		return view(row)
	}

	private fun append(
		account: MailAccount,
		folder: MailFolder,
		message: MimeMessage,
	) {
		pool.withStore(account) { store ->
			val remote = store.getFolder(folder.path)
			try {
				remote.appendMessages(arrayOf(message))
			} catch (e: MessagingException) {
				throw MailConnectionException.of(e)
			}
		}
	}

	/** The fully read stored version [uid] of [drafts], or `null` when it is gone. */
	private fun read(
		store: IMAPStore,
		drafts: MailFolder,
		uid: Long,
	): MimeMessage? =
		withOpenFolder(store, drafts, Folder.READ_ONLY) { folder ->
			val message = folder.getMessageByUID(uid) as MimeMessage? ?: return@withOpenFolder null
			MimeMessage(message)
		}

	/** Deletes [uid] from [drafts]; `false` when it was gone already. */
	private fun remove(
		store: IMAPStore,
		drafts: MailFolder,
		uid: Long,
	): Boolean =
		withOpenFolder(store, drafts, Folder.READ_WRITE) { folder ->
			val message = folder.getMessageByUID(uid) ?: return@withOpenFolder false
			folder.setFlags(arrayOf(message), Flags(Flags.Flag.DELETED), true)
			expunge(store, folder, message)
			true
		} ?: false

	private fun <T> withOpenFolder(
		store: IMAPStore,
		folder: MailFolder,
		mode: Int,
		block: (IMAPFolder) -> T,
	): T? {
		val remote = store.getFolder(folder.path) as IMAPFolder
		try {
			remote.open(mode)
		} catch (e: FolderNotFoundException) {
			return null
		} catch (e: MessagingException) {
			throw MailConnectionException.of(e)
		}
		try {
			if (folder.uidValidity != null && remote.uidValidity != folder.uidValidity) return null
			return block(remote)
		} catch (e: MessagingException) {
			throw MailConnectionException.of(e)
		} catch (e: IOException) {
			throw MailConnectionException.of(e)
		} finally {
			runCatching { if (remote.isOpen) remote.close(false) }
		}
	}

	/** Expunges only [message] with UIDPLUS, else every `\Deleted` message of the folder. */
	private fun expunge(
		store: IMAPStore,
		folder: IMAPFolder,
		message: Message,
	) {
		if (store.hasCapability("UIDPLUS")) folder.expunge(arrayOf(message)) else folder.expunge()
	}

	/** Marks the cached row as no longer at [uid], so the next sync re-attaches it to the new version by Message-ID. */
	private fun detach(
		rowId: UUID,
		draftsId: UUID,
		uid: Long,
	) {
		transactions.executeWithoutResult {
			messages
				.findById(rowId)
				.orElse(null)
				?.takeIf { it.folderId == draftsId && it.uid == uid }
				?.detach()
		}
	}

	private fun resync(folder: MailFolder) {
		sync.syncFolder(folder.id)
	}

	private fun located(
		accountId: UUID,
		folderId: UUID,
		messageId: String,
	): MailMessage? =
		messages
			.findByAccountIdAndMessageIdHeaderAndFolderIdIsNotNullAndUidIsNotNullOrderByIdAsc(accountId, messageId)
			.firstOrNull { it.folderId == folderId }

	/** Logs a failure after SMTP accepted the message; returns whether [step] worked. */
	private fun afterSend(
		what: String,
		step: () -> Any?,
	): Boolean =
		try {
			step() != false
		} catch (e: MailConnectionException) {
			log.info("Sent a draft, but could not {}: {}", what, e.error)
			log.debug("After-send failure", e.cause)
			false
		} catch (e: RuntimeException) {
			log.info("Sent a draft, but could not {}", what)
			log.debug("After-send failure", e)
			false
		}

	// --- lookup ----------------------------------------------------------------------------------------------------

	private fun draftsFolder(account: MailAccount): MailFolder =
		folders.findByAccountIdAndRole(account.id, MailFolderRole.DRAFTS)?.takeIf { it.selectable }
			?: throw ConflictException("The account has no Drafts folder")

	private fun draftRow(id: UUID): MailMessage {
		val row = locator.current(id) ?: throw NotFoundException(DRAFT_NOT_FOUND)
		val folder = row.folderId?.let { folders.findById(it).orElse(null) }
		if (folder?.role != MailFolderRole.DRAFTS) throw NotFoundException(DRAFT_NOT_FOUND)
		return row
	}

	private fun <T> locked(
		id: UUID,
		block: (MailMessage, MailAccount, MailFolder) -> T,
	): T {
		val first = draftRow(id)
		val lock = locks[Math.floorMod((first.messageIdHeader ?: first.id.toString()).hashCode(), LOCK_STRIPES)]
		return lock.withLock {
			// Read again under the lock: a save that just finished moved the draft to a new UID.
			val row = draftRow(id)
			val account = accounts.findById(row.accountId).orElse(null) ?: throw NotFoundException(DRAFT_NOT_FOUND)
			block(row, account, draftsFolder(account))
		}
	}

	private fun view(row: MailMessage): Draft {
		val content = loader.load(row)
		val from = row.from.firstOrNull()?.address
		val identity =
			from?.let { address ->
				identities.findByAccountIdOrderByPositionAscIdAsc(row.accountId).firstOrNull { it.email.equals(address, ignoreCase = true) }
			}
		val html = content.textHtml?.let { sanitizer.sanitize(it, KEEP_REMOTE_IMAGES).html }
		return Draft(row, identity?.id, html, content.textPlain, content.attachments)
	}

	/** The stored version a save was based on is gone (deleted or replaced by another client). */
	private class DraftChangedException : RuntimeException()

	private sealed interface Stored {
		data object Done : Stored

		data object Gone : Stored

		data class Invalid(
			val cause: RuntimeException,
		) : Stored
	}

	private companion object {
		const val DRAFT_NOT_FOUND = "Draft not found"
		const val SAVED_NOT_VISIBLE = "The draft was saved, but the mail server does not list it yet; reload the drafts"
		const val LOCK_STRIPES = 64

		/**
		 * For HTML that is edited or sent rather than shown: remote images keep their `src` (an identity "proxy"), so a
		 * GET -> PUT round trip and a quoted original lose none; scripts, handlers etc. are still removed.
		 */
		val KEEP_REMOTE_IMAGES = SanitizeOptions(RemoteImagePolicy.PROXY, imageProxy = { it })
	}
}
