package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.files.domain.FileItem
import ch.sonensei.cockpit.files.domain.FileNames
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.files.domain.Folder
import ch.sonensei.cockpit.files.domain.SpecialFolder
import ch.sonensei.cockpit.files.domain.UploadLimits
import ch.sonensei.cockpit.files.infra.BlobTooLargeException
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageLocator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.activation.DataHandler
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.unit.DataSize
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID
import kotlin.io.path.listDirectoryEntries

/**
 * "Save to Files" ([MailAttachmentFilesService]) with the mail server, the drive and the links replaced: the part is
 * buffered in a temporary file (deleted afterwards in every outcome), size-limited, and only a mail server read
 * failure leaves the IMAP block with an exception (which marks the pooled connection unhealthy).
 */
class MailAttachmentFilesServiceTests {
	@TempDir
	lateinit var dataDir: Path

	private val message = MailMessage(UUID.randomUUID(), UUID.randomUUID(), 7)
	private val attachment = MailAttachment("2", "budget.csv", "text/csv", 30, inline = false, contentId = null)
	private val mailFolder = Folder("Mail", UUID.randomUUID())
	private val locator = mockk<MailMessageLocator> { every { current(message.id) } returns message }
	private val loader = mockk<MailContentLoader>()
	private val reads = MailReadService(locator, mockk(), mockk(), mockk(), mockk(), loader, mockk())
	private val files = mockk<FilesService>()
	private val links = mockk<LinkService>(relaxed = true)
	private val transactions = mockk<TransactionTemplate>()

	/** How the IMAP block ended: `null` while it did not run, else its result or exception. */
	private var blockOutcome: Result<Any?>? = null

	/** What [FilesService.storeFile] received: the content read from the buffer and the arguments. */
	private var stored: Stored? = null

	private data class Stored(
		val folderId: UUID?,
		val name: String,
		val mime: String,
		val content: List<Byte>,
		val maxSize: Long,
	)

	private val tmp: Path get() = dataDir.resolve("tmp").resolve("mail")

	private fun service(maxSize: Long = 1024) =
		MailAttachmentFilesService(
			reads,
			files,
			links,
			UploadLimits(DataSize.ofBytes(maxSize), DataSize.ofMegabytes(8)),
			transactions,
			dataDir,
		)

	init {
		every { transactions.execute(any<TransactionCallback<FileItem>>()) } answers {
			firstArg<TransactionCallback<FileItem>>().doInTransaction(mockk(relaxed = true))
		}
		every { files.specialFolder(SpecialFolder.MAIL) } returns mailFolder
		every { files.freeFileName(mailFolder.id, any()) } answers { secondArg() }
		every { files.storeFile(any(), any(), any(), any(), any(), any()) } answers {
			val content = arg<InputStream>(3).readAllBytes()
			stored = Stored(firstArg(), secondArg(), thirdArg(), content.toList(), arg(4))
			FileItem(secondArg(), firstArg(), thirdArg(), content.size.toLong(), "fictitious-sha256", "fictitious-key")
		}
	}

	private fun serverCopy(
		content: ByteArray,
		filename: String = "budget.csv",
	): MimeMessage =
		MimeMessage(Session.getInstance(Properties())).apply {
			setContent(
				MimeMultipart(
					MimeBodyPart().apply { setText("Hello", "UTF-8") },
					MimeBodyPart().apply {
						dataHandler = DataHandler(ByteArrayDataSource(content, "application/octet-stream"))
						fileName = filename
					},
				),
			)
			saveChanges()
		}

	/**
	 * The loader lists [listed] and runs the block on the server copy (mapped by [root]) like the real one, recording
	 * how it ended; [beforeBlock] runs while the IMAP connection would be held, before the block.
	 */
	private fun remote(
		content: ByteArray = CONTENT,
		listed: List<MailAttachment> = listOf(attachment),
		beforeBlock: () -> Unit = {},
		root: (Part) -> Part = { it },
	) {
		every { loader.load(message) } returns MailContent("Hello", null, listed)
		every { loader.withRemoteMessage<Any?>(message, any()) } answers {
			beforeBlock()
			val block = secondArg<(Part) -> Any?>()
			val outcome = runCatching { block(root(serverCopy(content))) }
			blockOutcome = outcome
			outcome.getOrThrow()
		}
	}

	private fun tempFilesLeft(): List<Path> = if (Files.isDirectory(tmp)) tmp.listDirectoryEntries() else emptyList()

	@Test
	fun `the part is stored in the Mail folder under its name with its type and the upload limit, then linked`() {
		remote()

		val file = service().saveToFiles(message.id, "2")

		stored shouldBe Stored(mailFolder.id, "budget.csv", "text/csv", CONTENT.toList(), 1024)
		file.name shouldBe "budget.csv"
		file.folderId shouldBe mailFolder.id
		verify(exactly = 1) { links.create(ItemRef(ItemType.FILE, file.id), ItemRef(ItemType.EMAIL, message.id), null) }
		blockOutcome!!.isSuccess shouldBe true
		tempFilesLeft().shouldBeEmpty()
	}

	@Test
	fun `a taken name is replaced by the free one the drive suggests`() {
		remote()
		every { files.freeFileName(mailFolder.id, "budget.csv") } returns "budget (2).csv"

		val file = service().saveToFiles(message.id, "2")

		file.name shouldBe "budget (2).csv"
		stored!!.name shouldBe "budget (2).csv"
	}

	@Test
	fun `an unnamed generic part is stored as attachment with the default type`() {
		remote(listed = listOf(MailAttachment("2", null, "application/octet-stream", 30, inline = false, contentId = null)))

		service().saveToFiles(message.id, "2")

		stored!!.name shouldBe "attachment"
		stored!!.mime shouldBe FilesService.DEFAULT_MIME
	}

	@Test
	fun `a generic type is guessed from the sanitized name`() {
		remote(listed = listOf(MailAttachment("2", "q1/report.pdf", "application/octet-stream", 30, inline = false, contentId = null)))

		service().saveToFiles(message.id, "2")

		stored!!.name shouldBe "q1_report.pdf"
		stored!!.mime shouldBe "application/pdf"
	}

	@Test
	fun `the temporary file lives below the data directory`() {
		var seen: List<Path> = emptyList()
		remote(beforeBlock = { seen = tempFilesLeft() })

		service().saveToFiles(message.id, "2")

		seen.size shouldBe 1
		seen.single().fileName.toString() shouldEndWith ".part"
		tempFilesLeft().shouldBeEmpty()
	}

	@Test
	fun `an attachment the message does not list is 404 without contacting the server`() {
		remote()

		shouldThrow<NotFoundException> { service().saveToFiles(message.id, "9") }

		verify(exactly = 0) { loader.withRemoteMessage<Any?>(any(), any()) }
		stored shouldBe null
		tempFilesLeft().shouldBeEmpty()
	}

	@Test
	fun `an unknown message is 404`() {
		val unknown = UUID.randomUUID()
		every { locator.current(unknown) } returns null

		shouldThrow<NotFoundException> { service().saveToFiles(unknown, "2") }

		stored shouldBe null
	}

	@Test
	fun `a part missing on the server is 404 without leaving the IMAP block as a failure`() {
		// The server copy no longer has the cached part 2.
		remote(root = { MimeMessage(Session.getInstance(Properties())).apply { setText("Hello", "UTF-8") }.apply { saveChanges() } })

		shouldThrow<NotFoundException> { service().saveToFiles(message.id, "2") }

		blockOutcome!!.isSuccess shouldBe true
		stored shouldBe null
		verify(exactly = 0) { links.create(any(), any(), any()) }
		tempFilesLeft().shouldBeEmpty()
	}

	@Test
	fun `a part of exactly the maximum size is stored`() {
		remote()

		service(maxSize = CONTENT.size.toLong()).saveToFiles(message.id, "2")

		stored!!.content shouldBe CONTENT.toList()
	}

	@Test
	fun `a part one byte over the maximum size is too large, without leaving the IMAP block as a failure`() {
		remote()

		val e = shouldThrow<BlobTooLargeException> { service(maxSize = CONTENT.size.toLong() - 1).saveToFiles(message.id, "2") }

		e.maxSize shouldBe CONTENT.size.toLong() - 1
		blockOutcome!!.isSuccess shouldBe true
		stored shouldBe null
		tempFilesLeft().shouldBeEmpty()
	}

	@Test
	fun `a part larger than the copy buffer is copied completely`() {
		val large = ByteArray(40 * 1024) { (it % 251).toByte() }
		remote(content = large)

		service(maxSize = large.size.toLong()).saveToFiles(message.id, "2")

		stored!!.content shouldBe large.toList()
	}

	@Test
	fun `a failing local write is no mail server failure`() {
		val full = Path.of("/dev/full")
		assumeTrue(Files.isWritable(full), "needs /dev/full to simulate a full disk")
		// The buffer file is replaced by /dev/full, so every write fails with "No space left on device".
		remote(beforeBlock = {
			val temp = tempFilesLeft().single()
			Files.delete(temp)
			Files.createSymbolicLink(temp, full)
		})

		val e = shouldThrow<UncheckedIOException> { service().saveToFiles(message.id, "2") }

		e.cause.shouldBeInstanceOf<IOException>()
		// The IMAP block ended normally, so the pool keeps the connection.
		blockOutcome!!.isSuccess shouldBe true
		stored shouldBe null
		tempFilesLeft().shouldBeEmpty()
	}

	@Test
	fun `a buffer file that cannot be opened is no mail server failure`() {
		// A directory in place of the buffer file makes opening it for writing fail.
		remote(beforeBlock = {
			val temp = tempFilesLeft().single()
			Files.delete(temp)
			Files.createDirectory(temp)
		})

		val e = shouldThrow<UncheckedIOException> { service().saveToFiles(message.id, "2") }

		e.cause.shouldBeInstanceOf<IOException>()
		// The IMAP block ended normally, so the pool keeps the connection.
		blockOutcome!!.isSuccess shouldBe true
		stored shouldBe null
		tempFilesLeft().shouldBeEmpty()
	}

	@Test
	fun `a failure reading from the mail server leaves the IMAP block as a failure and stores nothing`() {
		// The real loader turns this IOException into a MailConnectionException and drops the connection.
		remote(root = { root -> FailingReads(root) })

		shouldThrow<IOException> { service().saveToFiles(message.id, "2") }

		blockOutcome!!.exceptionOrNull().shouldBeInstanceOf<IOException>()
		stored shouldBe null
		tempFilesLeft().shouldBeEmpty()
	}

	@Test
	fun `a failure storing the file deletes the temporary file too`() {
		remote()
		every { files.storeFile(any(), any(), any(), any(), any(), any()) } throws IllegalStateException("fictitious store failure")

		shouldThrow<IllegalStateException> { service().saveToFiles(message.id, "2") }

		verify(exactly = 0) { links.create(any(), any(), any()) }
		tempFilesLeft().shouldBeEmpty()
	}

	@Test
	fun `fileName keeps a usable name and trims it`() {
		MailAttachmentFilesService.fileName("budget.csv") shouldBe "budget.csv"
		MailAttachmentFilesService.fileName("  Prüfbericht 2026.pdf  ") shouldBe "Prüfbericht 2026.pdf"
	}

	@Test
	fun `fileName replaces separators and control characters`() {
		MailAttachmentFilesService.fileName("../etc/passwd") shouldBe ".._etc_passwd"
		MailAttachmentFilesService.fileName("C:\\Users\\jane\\report.pdf") shouldBe "C:_Users_jane_report.pdf"
		MailAttachmentFilesService.fileName("a\u0000b\nc\td.txt") shouldBe "a_b_c_d.txt"
		MailAttachmentFilesService.fileName("/") shouldBe "_"
	}

	@Test
	fun `fileName falls back to attachment when nothing usable is left`() {
		listOf(null, "", "   ", ".", "..", " .. ").forEach { MailAttachmentFilesService.fileName(it) shouldBe "attachment" }
	}

	@Test
	fun `fileName keeps a name of the maximum length and shortens a longer one, keeping the extension`() {
		val max = "a".repeat(FileNames.MAX_LENGTH - 4) + ".pdf"
		MailAttachmentFilesService.fileName(max) shouldBe max

		val long = MailAttachmentFilesService.fileName("b".repeat(300) + ".pdf")
		long.length shouldBe FileNames.MAX_LENGTH
		long shouldEndWith ".pdf"

		// A trailing space left by the cut is dropped, so the result is still a valid name.
		val spaced = MailAttachmentFilesService.fileName("c".repeat(FileNames.MAX_LENGTH - 5) + " " + "d".repeat(100) + ".pdf")
		spaced shouldBe "c".repeat(FileNames.MAX_LENGTH - 5) + ".pdf"
	}

	@Test
	fun `fileName cuts a long name with an over-long extension without keeping it`() {
		val name = MailAttachmentFilesService.fileName("e".repeat(300) + "." + "x".repeat(40))

		name shouldBe "e".repeat(FileNames.MAX_LENGTH)
	}

	@Test
	fun `every fileName result is a valid drive name`() {
		listOf(null, "", "..", "a/b", "\u0001", "x".repeat(1000), "y".repeat(400) + ".tar.gz", " z ")
			.forEach { FileNames.validate(MailAttachmentFilesService.fileName(it)) shouldBe MailAttachmentFilesService.fileName(it) }
	}

	@Test
	fun `mime keeps a specific type and guesses a missing or generic one from the name`() {
		MailAttachmentFilesService.mime("text/csv", "budget.csv") shouldBe "text/csv"
		MailAttachmentFilesService.mime("image/png", "photo.jpg") shouldBe "image/png"
		MailAttachmentFilesService.mime("application/octet-stream", "report.pdf") shouldBe "application/pdf"
		MailAttachmentFilesService.mime("", "report.pdf") shouldBe "application/pdf"
		MailAttachmentFilesService.mime("  ", "photo.png") shouldBe "image/png"
		MailAttachmentFilesService.mime("application/octet-stream", "attachment") shouldBe FilesService.DEFAULT_MIME
		MailAttachmentFilesService.mime("", "data.fictitious-ext") shouldBe FilesService.DEFAULT_MIME
	}

	/** A message whose part contents fail to read, as when the IMAP connection drops mid-download. */
	private class FailingReads(
		private val root: Part,
	) : Part by root {
		override fun getContent(): Any {
			val multipart = root.content as MimeMultipart
			val failing = MimeMultipart()
			for (index in 0 until multipart.count) {
				val part = multipart.getBodyPart(index) as MimeBodyPart
				failing.addBodyPart(
					object : MimeBodyPart() {
						override fun isMimeType(mimeType: String) = part.isMimeType(mimeType)

						override fun getInputStream(): InputStream =
							object : FilterInputStream(part.inputStream) {
								override fun read(
									b: ByteArray,
									off: Int,
									len: Int,
								): Int = throw IOException("Connection dropped")
							}
					},
				)
			}
			return failing
		}
	}

	private companion object {
		val CONTENT = "item,amount\nfictitious,42\n".toByteArray()
	}
}
