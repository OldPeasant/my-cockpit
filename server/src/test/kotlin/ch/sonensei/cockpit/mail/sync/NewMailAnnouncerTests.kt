package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.events.domain.ClientNotification
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailSecurity
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration
import java.time.Instant
import java.util.UUID

class NewMailAnnouncerTests {
	private val now = Instant.parse("2026-10-21T08:00:00Z")
	private val account =
		MailAccount(
			MailAccountType.IMAP_SMTP,
			"Test",
			"jane@example.test",
			"imap.example.test",
			993,
			MailSecurity.SSL_TLS,
			"smtp.example.test",
			465,
			MailSecurity.SSL_TLS,
			"jane",
		)
	private val inbox = MailFolder(account.id, "INBOX", "INBOX").apply { role = MailFolderRole.INBOX }
	private val repository = mockk<MailSyncRepository>()
	private val changes = mockk<ChangeEventPublisher>(relaxed = true)
	private val events = mockk<ApplicationEventPublisher>(relaxed = true)
	private val announcer = NewMailAnnouncer(repository, changes, events)

	private fun message(
		uid: Long,
		receivedAt: Instant? = now.minus(Duration.ofMinutes(100 - uid)),
		seen: Boolean = false,
		folder: MailFolder = inbox,
	) = MailMessage(account.id, folder.id, uid).apply {
		this.receivedAt = receivedAt
		this.seen = seen
		subject = "Fictitious subject $uid"
		from = listOf(MailAddress("sender$uid@example.test", "Sender $uid"))
	}

	private fun stored(vararg messages: MailMessage): List<UUID> {
		every { repository.findAllById(any<Iterable<UUID>>()) } returns messages.toList()
		return messages.map { it.id }
	}

	private fun notification(): NewMailNotification {
		val event = slot<Any>()
		verify(exactly = 1) { events.publishEvent(capture(event)) }
		val published = event.captured.shouldBeInstanceOf<ClientNotification>()
		published.name shouldBe NewMailAnnouncer.EVENT_NAME
		return published.data.shouldBeInstanceOf<NewMailNotification>()
	}

	@Test
	fun `every arrival gets an EMAIL created change event`() {
		val ids = stored(message(1), message(2))

		announcer.announce(account, inbox, ids)

		ids.forEach { verify(exactly = 1) { changes.publish(ItemType.EMAIL, it, ChangeOp.CREATED) } }
	}

	@Test
	fun `unread arrivals in the Inbox are announced newest first with sender, subject and arrival`() {
		val older = message(1, receivedAt = now.minus(Duration.ofMinutes(5)))
		val newer = message(2, receivedAt = now)
		val ids = stored(older, newer)

		announcer.announce(account, inbox, ids)

		notification() shouldBe
			NewMailNotification(
				account.id,
				inbox.id,
				2,
				listOf(
					NewMailSummary(newer.id, "Sender 2 <sender2@example.test>", "Fictitious subject 2", now),
					NewMailSummary(older.id, "Sender 1 <sender1@example.test>", "Fictitious subject 1", now.minus(Duration.ofMinutes(5))),
				),
			)
	}

	@Test
	fun `seen arrivals and rows of other folders are not part of the notification`() {
		val unread = message(1)
		val seen = message(2, seen = true)
		val elsewhere = message(3, folder = MailFolder(account.id, "Archive", "Archive"))
		val ids = stored(unread, seen, elsewhere)

		announcer.announce(account, inbox, ids)

		val notification = notification()
		notification.count shouldBe 1
		notification.messages.map { it.id } shouldContainExactly listOf(unread.id)
		// Change events still go out for every arrival.
		verify(exactly = 3) { changes.publish(ItemType.EMAIL, any(), ChangeOp.CREATED) }
	}

	@Test
	fun `a message without a sender is summarized without one`() {
		val anonymous = message(1).apply { from = emptyList() }

		announcer.announce(account, inbox, stored(anonymous))

		notification().messages.single().from shouldBe null
	}

	@Test
	fun `at most 10 summaries are sent, the newest, while the count covers every unread arrival`() {
		val arrivals = (1L..12L).map { message(it, receivedAt = now.plusSeconds(it)) }
		val ids = stored(*arrivals.shuffled(kotlin.random.Random(7)).toTypedArray())

		announcer.announce(account, inbox, ids)

		val notification = notification()
		notification.count shouldBe 12
		notification.messages.map { it.id } shouldContainExactly arrivals.reversed().take(10).map { it.id }
	}

	@Test
	fun `equal arrival times are ordered by UID descending and messages without a date come last`() {
		val noDate = message(9, receivedAt = null)
		val low = message(5, receivedAt = now)
		val high = message(7, receivedAt = now)

		announcer.announce(account, inbox, stored(noDate, low, high))

		notification().messages.map { it.id } shouldContainExactly listOf(high.id, low.id, noDate.id)
	}

	@Test
	fun `no notification when the account does not notify about new mail`() {
		account.notifyNewMail = false
		val ids = stored(message(1))

		announcer.announce(account, inbox, ids)

		verify(exactly = 0) { events.publishEvent(any<Any>()) }
		verify(exactly = 1) { changes.publish(ItemType.EMAIL, ids.single(), ChangeOp.CREATED) }
	}

	@Test
	fun `no notification for arrivals outside the Inbox`() {
		val sent = MailFolder(account.id, "Sent", "Sent").apply { role = MailFolderRole.SENT }
		val plain = MailFolder(account.id, "Projects", "Projects")
		val ids = stored(message(1, folder = sent))

		announcer.announce(account, sent, ids)
		announcer.announce(account, plain, ids)

		verify(exactly = 0) { events.publishEvent(any<Any>()) }
		verify(exactly = 0) { repository.findAllById(any<Iterable<UUID>>()) }
		verify(exactly = 2) { changes.publish(ItemType.EMAIL, ids.single(), ChangeOp.CREATED) }
	}

	@Test
	fun `no notification when every arrival is already seen`() {
		announcer.announce(account, inbox, stored(message(1, seen = true), message(2, seen = true)))

		verify(exactly = 0) { events.publishEvent(any<Any>()) }
	}

	@Test
	fun `nothing is announced or read for no arrivals`() {
		announcer.announce(account, inbox, emptyList())

		verify(exactly = 0) { changes.publish(any<Enum<*>>(), any(), any()) }
		verify(exactly = 0) { repository.findAllById(any<Iterable<UUID>>()) }
		verify(exactly = 0) { events.publishEvent(any<Any>()) }
	}
}
