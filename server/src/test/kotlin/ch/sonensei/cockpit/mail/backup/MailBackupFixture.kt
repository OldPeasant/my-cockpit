package ch.sonensei.cockpit.mail.backup

import ch.sonensei.cockpit.backup.BackupFixture
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.Link
import ch.sonensei.cockpit.core.items.domain.LinkOrigin
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.reminders.domain.Reminder
import ch.sonensei.cockpit.core.reminders.domain.ReminderChannel
import ch.sonensei.cockpit.core.reminders.infra.ReminderRepository
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailIdentity
import ch.sonensei.cockpit.mail.model.MailIdentityRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import java.time.Instant

/**
 * An IMAP account with a (fictitious) password, a signature and two identities, and a Google OAuth account; folders with
 * detected, overridden and no roles (with sync state, which the backup leaves out); cached messages of which one is
 * linked from a task, one (detached, without Message-ID) links to a note and one has a reminder, and one is not
 * referenced at all (not in the backup).
 */
class MailBackupFixture(
	private val accounts: MailAccountRepository,
	private val identities: MailIdentityRepository,
	private val folders: MailFolderRepository,
	private val messages: MailMessageRepository,
	private val links: LinkRepository,
	private val reminders: ReminderRepository,
) : BackupFixture {
	override val contributor = MailBackupContributor.ID

	override fun seed() {
		val imap =
			accounts.saveAndFlush(
				MailAccount(
					MailAccountType.IMAP_SMTP,
					"Privat «Zürich»",
					"owner@example.test",
					"imap.example.test",
					993,
					MailSecurity.SSL_TLS,
					"smtp.example.test",
					587,
					MailSecurity.STARTTLS,
					"owner",
				).apply {
					password = EncryptedSecret("fictitious-imap-password")
					signatureMd = "--\n**Owner**\nexample.test"
					notifyNewMail = false
					syncWindowMonths = 6
				},
			)
		val google =
			accounts.saveAndFlush(
				MailAccount(
					MailAccountType.GOOGLE,
					"Work",
					"owner@work.example.test",
					"imap.gmail.com",
					993,
					MailSecurity.SSL_TLS,
					"smtp.gmail.com",
					465,
					MailSecurity.SSL_TLS,
					"owner@work.example.test",
				).apply { oauthConnectionId = UuidV7.generate() },
			)
		identities.saveAllAndFlush(
			listOf(
				MailIdentity(imap.id, "Owner", "owner@example.test").apply { isDefault = true },
				MailIdentity(imap.id, "Owner (Verein)", "verein@example.test").apply {
					replyTo = "board@example.test"
					signatureMd = ""
					position = 1
				},
			),
		)
		val inbox =
			folders.saveAndFlush(
				MailFolder(imap.id, "INBOX", "INBOX").apply {
					delimiter = "/"
					role = MailFolderRole.INBOX
					uidValidity = 42
					uidNext = 100
					highestModSeq = 7000
					lastSyncedAt = Instant.parse("2026-10-01T08:00:00Z")
				},
			)
		folders.saveAndFlush(
			MailFolder(imap.id, "INBOX/Archiv 2025", "Archiv 2025").apply {
				delimiter = "/"
				role = MailFolderRole.ARCHIVE
				roleOverridden = true
			},
		)
		folders.saveAndFlush(MailFolder(imap.id, "[Gmail]", "[Gmail]").apply { selectable = false })
		val allMail = folders.saveAndFlush(MailFolder(google.id, "[Gmail]/All Mail", "All Mail").apply { delimiter = "/" })

		val linked =
			messages.saveAndFlush(
				MailMessage(imap.id, inbox.id, 17).apply {
					messageIdHeader = "<invoice-17@example.test>"
					subject = "Rechnung Oktober"
					from = listOf(MailAddress("billing@example.test", "Billing"))
				},
			)
		val detached = messages.saveAndFlush(MailMessage(google.id, null, null).apply { subject = "Lost message" })
		val reminded =
			messages.saveAndFlush(
				MailMessage(google.id, allMail.id, 3).apply { messageIdHeader = "<follow-up@work.example.test>" },
			)
		messages.saveAndFlush(MailMessage(imap.id, inbox.id, 18).apply { messageIdHeader = "<newsletter@example.test>" })

		links.saveAllAndFlush(
			listOf(
				Link(ItemRef(ItemType.TASK, UuidV7.generate()), ItemRef(ItemType.EMAIL, linked.id), "Pay", LinkOrigin.MANUAL),
				Link(ItemRef(ItemType.EMAIL, detached.id), ItemRef(ItemType.NOTE, UuidV7.generate()), null, LinkOrigin.MANUAL),
			),
		)
		reminders.saveAndFlush(
			Reminder(ItemRef(ItemType.EMAIL, reminded.id), Instant.parse("2026-11-02T08:00:00Z"), ReminderChannel.NOTIFICATION),
		)
	}
}
