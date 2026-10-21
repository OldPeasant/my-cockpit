package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.items.domain.ResolvedLink
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.imap.ImapConnectionPool
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageLocator
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.FolderNotFoundException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.ReadOnlyFolderException
import org.eclipse.angus.mail.iap.CommandFailedException
import org.eclipse.angus.mail.imap.AppendUID
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional
import java.util.UUID

/**
 * [MailActionService] with a scripted IMAP connection, for what GreenMail cannot show: servers without MOVE or
 * UIDPLUS, missing or unusable COPYUID responses, read-only folders, commands the server answers with NO, and cache
 * updates that conflict with a concurrent writer.
 */
class MailActionServiceTests {
	private val locator = mockk<MailMessageLocator>()
	private val messages = mockk<MailMessageRepository>(relaxUnitFun = true)
	private val accounts = mockk<MailAccountRepository>()
	private val folders = mockk<MailFolderRepository>()
	private val pool = mockk<ImapConnectionPool>()
	private val links = mockk<LinkService>()
	private val changes = mockk<ChangeEventPublisher>(relaxed = true)
	private val service =
		MailActionService(
			locator,
			messages,
			accounts,
			folders,
			pool,
			links,
			TransactionTemplate(mockk<PlatformTransactionManager>(relaxed = true)),
			changes,
		)

	private val account =
		MailAccount(
			MailAccountType.IMAP_SMTP,
			"Work",
			"ann@work.example",
			"imap.example.test",
			993,
			MailSecurity.SSL_TLS,
			"smtp.example.test",
			587,
			MailSecurity.STARTTLS,
			"ann@work.example",
		)
	private val inbox = folder("INBOX", MailFolderRole.INBOX)
	private val trash = folder("Trash", MailFolderRole.TRASH)
	private val projects = folder("Projects", null)
	private val store = mockk<IMAPStore>()
	private val remoteInbox = mockk<IMAPFolder>(relaxUnitFun = true)
	private val remoteTrash = mockk<IMAPFolder>(relaxUnitFun = true)
	private val remoteProjects = mockk<IMAPFolder>(relaxUnitFun = true)
	private val cache = mutableMapOf<UUID, MailMessage>()

	init {
		every { accounts.findById(account.id) } returns Optional.of(account)
		listOf(inbox, trash, projects).forEach { every { folders.findById(it.id) } returns Optional.of(it) }
		every { folders.findByAccountIdAndRole(account.id, any()) } answers
			{ listOf(inbox, trash, projects).firstOrNull { it.role == secondArg() } }
		every { pool.withStore<Any?>(account, any()) } answers { secondArg<(IMAPStore) -> Any?>()(store) }
		every { store.getFolder("INBOX") } returns remoteInbox
		every { store.getFolder("Trash") } returns remoteTrash
		every { store.getFolder("Projects") } returns remoteProjects
		capabilities(move = true, uidplus = true)
		every { remoteInbox.uidValidity } returns UIDVALIDITY
		every { remoteTrash.uidValidity } returns UIDVALIDITY
		every { remoteInbox.isOpen } returns true
		every { remoteTrash.isOpen } returns true
		listOf(remoteInbox, remoteTrash).forEach {
			every { it.expunge() } returns emptyArray()
			every { it.expunge(any()) } returns emptyArray()
		}
		every { links.linksOf(any()) } returns emptyList()
		every { messages.findAllById(any()) } answers { firstArg<Iterable<UUID>>().mapNotNull { cache[it] } }
		every { messages.findByFolderIdAndUid(any(), any()) } returns null
		every { messages.delete(any()) } answers { cache.remove(firstArg<MailMessage>().id) }
		every { locator.current(any()) } answers { cache[firstArg()] }
	}

	private fun folder(
		path: String,
		role: MailFolderRole?,
	) = MailFolder(account.id, path, path).apply {
		this.role = role
		uidValidity = UIDVALIDITY
	}

	private fun capabilities(
		move: Boolean,
		uidplus: Boolean,
	) {
		every { store.hasCapability("MOVE") } returns move
		every { store.hasCapability("UIDPLUS") } returns uidplus
	}

	private fun cached(
		folder: MailFolder,
		uid: Long,
	) = MailMessage(account.id, folder.id, uid).also { cache[it.id] = it }

	/** The server has messages with [uids] in [folder]; returns them in the same order. */
	private fun onServer(
		folder: IMAPFolder,
		vararg uids: Long,
	): Array<Message> {
		val found = uids.map { mockk<Message>() }.toTypedArray()
		every { folder.getMessagesByUID(uids) } returns found
		return found
	}

	@Test
	fun `without MOVE a move is COPY, Deleted and UID EXPUNGE, and the row follows COPYUID`() {
		capabilities(move = false, uidplus = true)
		val message = cached(inbox, 7)
		val found = onServer(remoteInbox, 7)
		every { remoteInbox.copyUIDMessages(found, remoteProjects) } returns arrayOf(AppendUID(UIDVALIDITY, 41))

		val outcomes = service.apply(listOf(message.id), MailAction.MOVE, projects.id)

		outcomes shouldBe listOf(MailActionOutcome.ok(message.id))
		verify { remoteInbox.setFlags(found, Flags(Flags.Flag.DELETED), true) }
		verify { remoteInbox.expunge(found) }
		verify(exactly = 0) { remoteInbox.expunge() }
		message.folderId shouldBe projects.id
		message.uid shouldBe 41
		verify { changes.publish(MailFolderService.FOLDER_EVENT, inbox.id, ChangeOp.UPDATED) }
		verify { changes.publish(MailFolderService.FOLDER_EVENT, projects.id, ChangeOp.UPDATED) }
		verify { remoteInbox.close(false) }
	}

	@Test
	fun `without UIDPLUS a move expunges the whole folder and, without COPYUID, drops unlinked rows and detaches linked ones`() {
		capabilities(move = false, uidplus = false)
		val plain = cached(inbox, 7)
		val linked = cached(inbox, 8)
		every { links.linksOf(ItemRef(ItemType.EMAIL, linked.id)) } returns listOf(mockk<ResolvedLink>())
		val found = onServer(remoteInbox, 7, 8)
		every { remoteInbox.copyUIDMessages(found, remoteProjects) } returns null

		val outcomes = service.apply(listOf(plain.id, linked.id), MailAction.MOVE, projects.id)

		outcomes shouldBe listOf(MailActionOutcome.ok(plain.id), MailActionOutcome.ok(linked.id))
		verify { remoteInbox.expunge() }
		verify { messages.delete(plain) }
		linked.uid shouldBe null
		linked.folderId shouldBe inbox.id
	}

	@Test
	fun `a COPYUID of another UIDVALIDITY or of a UID already cached in the target is not trusted`() {
		val stale = cached(inbox, 7)
		val taken = cached(inbox, 8)
		val found = onServer(remoteInbox, 7, 8)
		every { remoteInbox.moveUIDMessages(found, remoteProjects) } returns
			arrayOf(AppendUID(UIDVALIDITY + 1, 41), AppendUID(UIDVALIDITY, 42))
		every { messages.findByFolderIdAndUid(projects.id, 42) } returns MailMessage(account.id, projects.id, 42)

		service.apply(listOf(stale.id, taken.id), MailAction.MOVE, projects.id)

		verify { messages.delete(stale) }
		verify { messages.delete(taken) }
	}

	@Test
	fun `a move keeps the COPYUID of the messages the server reported and drops the others`() {
		val reported = cached(inbox, 7)
		val unreported = cached(inbox, 8)
		val found = onServer(remoteInbox, 7, 8)
		every { remoteInbox.moveUIDMessages(found, remoteProjects) } returns arrayOf(AppendUID(UIDVALIDITY, 41), null)

		service.apply(listOf(reported.id, unreported.id), MailAction.MOVE, projects.id)

		reported.folderId shouldBe projects.id
		reported.uid shouldBe 41
		verify { messages.delete(unreported) }
	}

	@Test
	fun `delete in Trash without UIDPLUS expunges the folder and removes the row`() {
		capabilities(move = true, uidplus = false)
		val message = cached(trash, 3)
		val found = onServer(remoteTrash, 3)

		service.apply(listOf(message.id), MailAction.DELETE, null) shouldBe listOf(MailActionOutcome.ok(message.id))

		verify { remoteTrash.setFlags(found, Flags(Flags.Flag.DELETED), true) }
		verify { remoteTrash.expunge() }
		verify { messages.delete(message) }
		verify(exactly = 0) { changes.publish(MailFolderService.FOLDER_EVENT, projects.id, any()) }
	}

	@Test
	fun `a read-only source folder fails the ids with REJECTED`() {
		val message = cached(inbox, 7)
		every { remoteInbox.open(Folder.READ_WRITE) } throws ReadOnlyFolderException(remoteInbox)

		service.apply(listOf(message.id), MailAction.READ, null).single().errorCode shouldBe "REJECTED"
		message.seen shouldBe false
	}

	@Test
	fun `a command answered with NO fails the ids with REJECTED and leaves the cache alone`() {
		val message = cached(inbox, 7)
		val found = onServer(remoteInbox, 7)
		every { remoteInbox.setFlags(found, any<Flags>(), true) } throws
			MessagingException("NO flags not allowed", CommandFailedException("NO flags not allowed"))

		service.apply(listOf(message.id), MailAction.FLAG, null).single().errorCode shouldBe "REJECTED"
		message.flagged shouldBe false
		verify(exactly = 0) { changes.publish(any<Enum<*>>(), any(), any()) }
	}

	@Test
	fun `another server failure while acting is reported with its connection error`() {
		val message = cached(inbox, 7)
		val found = onServer(remoteInbox, 7)
		every { remoteInbox.setFlags(found, any<Flags>(), true) } throws MessagingException("connection reset")

		service.apply(listOf(message.id), MailAction.READ, null).single().errorCode shouldBe "CONNECTION_FAILED"
	}

	@Test
	fun `flags change on the server and in the cache`() {
		val message = cached(inbox, 7)
		val found = onServer(remoteInbox, 7)

		service.apply(listOf(message.id), MailAction.FLAG, null)
		service.apply(listOf(message.id), MailAction.READ, null)

		verify { remoteInbox.setFlags(found, Flags(Flags.Flag.FLAGGED), true) }
		verify { remoteInbox.setFlags(found, Flags(Flags.Flag.SEEN), true) }
		message.flagged shouldBe true
		message.seen shouldBe true
	}

	@Test
	fun `a message whose cached folder row is gone is NOT_FOUND without contacting the server`() {
		val orphan = MailMessage(account.id, UUID.randomUUID(), 5).also { cache[it.id] = it }
		every { folders.findById(checkNotNull(orphan.folderId)) } returns Optional.empty()

		service.apply(listOf(orphan.id), MailAction.READ, null).single().errorCode shouldBe "NOT_FOUND"
		verify(exactly = 0) { pool.withStore<Any?>(any(), any()) }
	}

	@Test
	fun `targetFolderId is checked before any message is looked up`() {
		val id = UUID.randomUUID()

		shouldThrow<InvalidRequestException> { service.apply(listOf(id), MailAction.READ, projects.id) }
			.fieldErrors
			.single()
			.field shouldBe "targetFolderId"
		shouldThrow<InvalidRequestException> { service.apply(listOf(id), MailAction.MOVE, null) }
		val unknown = UUID.randomUUID()
		every { folders.findById(unknown) } returns Optional.empty()
		shouldThrow<NotFoundException> { service.apply(listOf(id), MailAction.MOVE, unknown) }
		verify(exactly = 0) { locator.current(any()) }
	}

	/** The first [failures] flushes of the cache update throw what [conflict] returns; later ones succeed. */
	private fun flushConflicts(
		failures: Int,
		conflict: () -> RuntimeException,
	) {
		var calls = 0
		every { messages.flush() } answers { if (++calls <= failures) throw conflict() }
	}

	private fun versionConflict(): OptimisticLockingFailureException =
		ObjectOptimisticLockingFailureException(MailMessage::class.java, UUID.randomUUID())

	@Test
	fun `a version conflict in the cache update is retried on fresh rows and the ids stay ok`() {
		val message = cached(inbox, 7)
		val found = onServer(remoteInbox, 7)
		flushConflicts(1, ::versionConflict)

		val outcomes = service.apply(listOf(message.id), MailAction.FLAG, null)

		outcomes shouldBe listOf(MailActionOutcome.ok(message.id))
		verify(exactly = 1) { remoteInbox.setFlags(found, Flags(Flags.Flag.FLAGGED), true) }
		verify(exactly = 2) { messages.findAllById(listOf(message.id)) }
		message.flagged shouldBe true
		verify(exactly = 1) { changes.publish(MailFolderService.FOLDER_EVENT, inbox.id, ChangeOp.UPDATED) }
	}

	@Test
	fun `a conflict that persists leaves the cache to the next sync and still reports the ids ok`() {
		val message = cached(inbox, 7)
		val found = onServer(remoteInbox, 7)
		every { remoteInbox.moveUIDMessages(found, remoteProjects) } returns arrayOf(AppendUID(UIDVALIDITY, 41))
		flushConflicts(Int.MAX_VALUE, ::versionConflict)

		val outcomes = service.apply(listOf(message.id), MailAction.MOVE, projects.id)

		outcomes shouldBe listOf(MailActionOutcome.ok(message.id))
		verify(exactly = 2) { messages.flush() }
		// Both folders changed on the server, so clients are told to reload them although the cache update failed.
		verify { changes.publish(MailFolderService.FOLDER_EVENT, inbox.id, ChangeOp.UPDATED) }
		verify { changes.publish(MailFolderService.FOLDER_EVENT, projects.id, ChangeOp.UPDATED) }
	}

	@Test
	fun `a row a sync cached at the new UID meanwhile makes the retry drop the moved row instead of duplicating it`() {
		val message = cached(inbox, 7)
		val found = onServer(remoteInbox, 7)
		every { remoteInbox.moveUIDMessages(found, remoteProjects) } returns arrayOf(AppendUID(UIDVALIDITY, 41))
		val synced = MailMessage(account.id, projects.id, 41)
		// The first check finds the UID free; the unique index then reports the row the sync inserted meanwhile.
		every { messages.findByFolderIdAndUid(projects.id, 41) } returnsMany listOf(null, synced)
		flushConflicts(1) { DataIntegrityViolationException("duplicate key value violates unique constraint") }
		// The rollback discards the first attempt: the retry reads the row as stored, still in INBOX.
		val fresh = MailMessage(account.id, inbox.id, 7, message.id)
		every { messages.findAllById(listOf(message.id)) } returnsMany listOf(listOf(message), listOf(fresh))

		val outcomes = service.apply(listOf(message.id), MailAction.MOVE, projects.id)

		outcomes shouldBe listOf(MailActionOutcome.ok(message.id))
		verify { messages.delete(refEq(fresh)) }
		fresh.folderId shouldBe inbox.id
	}

	@Test
	fun `without MOVE a failing cleanup after a successful copy keeps the COPYUID and reports ok`() {
		capabilities(move = false, uidplus = true)
		val message = cached(inbox, 7)
		val found = onServer(remoteInbox, 7)
		every { remoteInbox.copyUIDMessages(found, remoteProjects) } returns arrayOf(AppendUID(UIDVALIDITY, 41))
		every { remoteInbox.setFlags(found, Flags(Flags.Flag.DELETED), true) } throws
			MessagingException("NO flags not allowed", CommandFailedException("NO flags not allowed"))

		val outcomes = service.apply(listOf(message.id), MailAction.MOVE, projects.id)

		outcomes shouldBe listOf(MailActionOutcome.ok(message.id))
		verify(exactly = 1) { remoteInbox.copyUIDMessages(any(), any()) }
		verify(exactly = 0) { remoteInbox.expunge(any()) }
		verify(exactly = 0) { remoteInbox.expunge() }
		message.folderId shouldBe projects.id
		message.uid shouldBe 41
	}

	@Test
	fun `a target folder missing on the server fails the ids with REJECTED and leaves the cache alone`() {
		val message = cached(inbox, 7)
		val found = onServer(remoteInbox, 7)
		every { remoteInbox.moveUIDMessages(found, remoteProjects) } throws
			FolderNotFoundException(remoteProjects, "Projects does not exist")

		service.apply(listOf(message.id), MailAction.MOVE, projects.id).single().errorCode shouldBe "REJECTED"
		message.folderId shouldBe inbox.id
		message.uid shouldBe 7
		verify(exactly = 0) { changes.publish(any<Enum<*>>(), any(), any()) }
	}

	@Test
	fun `a permanent delete of a linked message detaches its row instead of deleting it`() {
		val message = cached(trash, 3)
		every { links.linksOf(ItemRef(ItemType.EMAIL, message.id)) } returns listOf(mockk<ResolvedLink>())
		onServer(remoteTrash, 3)

		service.apply(listOf(message.id), MailAction.DELETE, null) shouldBe listOf(MailActionOutcome.ok(message.id))

		verify(exactly = 0) { messages.delete(any()) }
		message.uid shouldBe null
		message.folderId shouldBe trash.id
	}

	@Test
	fun `a source folder with another UIDVALIDITY than cached fails the ids with NOT_FOUND`() {
		val message = cached(inbox, 7)
		every { remoteInbox.uidValidity } returns UIDVALIDITY + 1

		service.apply(listOf(message.id), MailAction.READ, null).single().errorCode shouldBe "NOT_FOUND"
		verify(exactly = 0) { remoteInbox.getMessagesByUID(any<LongArray>()) }
		message.seen shouldBe false
	}

	private companion object {
		const val UIDVALIDITY = 1_700_000_000L
	}
}
