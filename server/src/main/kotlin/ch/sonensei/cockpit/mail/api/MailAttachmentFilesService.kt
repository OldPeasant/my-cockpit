package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.files.domain.FileItem
import ch.sonensei.cockpit.files.domain.FileNames
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.domain.SpecialFolder
import ch.sonensei.cockpit.files.domain.UploadLimits
import ch.sonensei.cockpit.files.infra.BlobTooLargeException
import ch.sonensei.cockpit.mail.model.MailMessage
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.MediaTypeFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * "Save to Files" of the read view (SPEC.md §7.3, §8.2): copies an attachment from the mail server into the
 * auto-managed `/Attachments/Mail` folder and links the file to the message.
 *
 * The part is first buffered in a temporary file below `${cockpit.data-dir}/tmp/mail`, while the IMAP connection is
 * held, so no database connection waits for the mail server ([FilesService.storeFile] reads its content inside its
 * transaction). Storing the file and linking it then run in one transaction.
 */
@Service
class MailAttachmentFilesService(
	private val reads: MailReadService,
	private val loader: MailContentLoader,
	private val files: FilesService,
	private val links: LinkService,
	private val limits: UploadLimits,
	private val transactions: TransactionTemplate,
	@Value("\${cockpit.data-dir}") dataDir: Path,
) {
	private val tmp: Path =
		dataDir
			.toAbsolutePath()
			.normalize()
			.resolve("tmp")
			.resolve("mail")

	/**
	 * Stores attachment [partId] of message [id] in `/Attachments/Mail` under its filename (a taken name gets a number,
	 * `report (2).pdf`) and links the file to the message.
	 *
	 * @throws NotFoundException when the message or the attachment does not exist (or is no longer on the server)
	 * @throws BlobTooLargeException when the attachment exceeds the maximum file size
	 * @throws ch.sonensei.cockpit.mail.imap.MailConnectionException when the mail server fails
	 */
	fun saveToFiles(
		id: UUID,
		partId: String,
	): FileItem {
		val message = reads.get(id)
		val attachment = loader.load(message).attachments.firstOrNull { it.partId == partId } ?: throw NotFoundException(ATTACHMENT_NOT_FOUND)
		Files.createDirectories(tmp)
		val temp = Files.createTempFile(tmp, "attachment-", ".part")
		try {
			download(message, partId, temp)
			val name = fileName(attachment.filename)
			return checkNotNull(
				transactions.execute {
					val folder = files.specialFolder(SpecialFolder.MAIL)
					val file =
						Files.newInputStream(temp).use {
							files.storeFile(folder.id, files.freeFileName(folder.id, name), mime(attachment.contentType, name), it, limits.maxSize)
						}
					links.create(ItemRef(ItemType.FILE, file.id), ItemRef(ItemType.EMAIL, message.id), null)
					file
				},
			)
		} finally {
			Files.deleteIfExists(temp)
		}
	}

	/** Copies the decoded part into [target]; a local write failure is not counted as a mail server failure. */
	private fun download(
		message: MailMessage,
		partId: String,
		target: Path,
	) {
		// Failures on our side are returned, not thrown, inside the block: a throw would count as an IMAP failure.
		val outcome =
			loader.withRemoteMessage(message) { root ->
				val part = MimeContent.findPart(root, partId) ?: return@withRemoteMessage Download.PartMissing
				Files.newOutputStream(target).use { out ->
					part.inputStream.use { copy(it, LimitedOutputStream(out, limits.maxSize)) }
				}
			}
		when (outcome) {
			Download.Done -> Unit
			Download.PartMissing -> throw NotFoundException(ATTACHMENT_NOT_FOUND)
			Download.TooLarge -> throw BlobTooLargeException(limits.maxSize)
			is Download.WriteFailed -> throw UncheckedIOException("Could not buffer the attachment", outcome.cause)
		}
	}

	/** Copies [input] (the mail server) to [output]. Read failures are thrown; a write failure ends the copy. */
	private fun copy(
		input: InputStream,
		output: OutputStream,
	): Download {
		val buffer = ByteArray(COPY_BUFFER_SIZE)
		while (true) {
			val read = input.read(buffer)
			if (read < 0) break
			try {
				output.write(buffer, 0, read)
			} catch (_: TooLargeSignal) {
				return Download.TooLarge
			} catch (e: IOException) {
				return Download.WriteFailed(e)
			}
		}
		return try {
			output.flush()
			Download.Done
		} catch (e: IOException) {
			Download.WriteFailed(e)
		}
	}

	private sealed interface Download {
		data object Done : Download

		data object PartMissing : Download

		data object TooLarge : Download

		data class WriteFailed(
			val cause: IOException,
		) : Download
	}

	private class TooLargeSignal : IOException("Attachment too large")

	/** Fails with [TooLargeSignal] once more than [maxSize] bytes were written. */
	private class LimitedOutputStream(
		private val out: OutputStream,
		private val maxSize: Long,
	) : OutputStream() {
		private var size = 0L

		override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

		override fun write(
			b: ByteArray,
			off: Int,
			len: Int,
		) {
			size += len
			if (size > maxSize) throw TooLargeSignal()
			out.write(b, off, len)
		}

		override fun flush() = out.flush()
	}

	companion object {
		private const val ATTACHMENT_NOT_FOUND = "Attachment not found"
		private const val DEFAULT_NAME = "attachment"
		private const val COPY_BUFFER_SIZE = 16 * 1024

		/**
		 * A valid drive file name for an attachment's [filename]: separators and control characters become `_`,
		 * at most [FileNames.MAX_LENGTH] characters (keeping the extension); `attachment` when nothing usable is left.
		 */
		fun fileName(filename: String?): String {
			val cleaned =
				filename
					.orEmpty()
					.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
					.joinToString("")
					.trim()
			if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return DEFAULT_NAME
			if (cleaned.length <= FileNames.MAX_LENGTH) return cleaned
			val dot = cleaned.lastIndexOf('.')
			val ext = if (dot > 0 && cleaned.length - dot <= MAX_EXTENSION) cleaned.substring(dot) else ""
			return cleaned.take(FileNames.MAX_LENGTH - ext.length).trimEnd() + ext
		}

		/** The attachment's content type, or one guessed from [name] when it is missing or generic. */
		fun mime(
			contentType: String,
			name: String,
		): String =
			contentType.takeIf { it.isNotBlank() && it != MediaType.APPLICATION_OCTET_STREAM_VALUE }
				?: MediaTypeFactory.getMediaType(name).map { it.toString() }.orElse(FilesService.DEFAULT_MIME)

		private const val MAX_EXTENSION = 16
	}
}
