package ch.sonensei.cockpit.core.backupcontrib

import ch.sonensei.cockpit.backup.BackupFixture
import ch.sonensei.cockpit.core.auth.domain.UserAccount
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
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
import ch.sonensei.cockpit.core.settings.domain.UserSettings
import ch.sonensei.cockpit.core.settings.infra.UserSettingsRepository
import java.time.Instant

/** A confirmed owner account with recovery codes, one of them used (fictitious hashes and secret). */
class UserBackupFixture(
	private val users: UserAccountRepository,
) : BackupFixture {
	override val contributor = UserBackupContributor.ID

	override fun seed() {
		val user =
			UserAccount("jdoe", "\$argon2id\$v=19\$m=16384,t=2,p=1\$c2FsdHNhbHQ\$ZmljdGl0aW91cyBoYXNo", EncryptedSecret("JBSWY3DPEHPK3PXP"))
		user.confirmTotp(Instant.parse("2026-03-01T08:00:00.123456Z"))
		repeat(3) { user.addRecoveryCode("\$argon2id\$v=19\$m=16384,t=2,p=1\$c2FsdA\$Y29kZS0$it") }
		user.recoveryCodes.first().markUsed(Instant.parse("2026-04-02T10:15:00Z"))
		users.saveAndFlush(user)
	}
}

/** Changed settings of several JSON types, unicode included. */
class SettingsBackupFixture(
	private val settings: UserSettingsRepository,
) : BackupFixture {
	override val contributor = SettingsBackupContributor.ID

	override fun seed() {
		settings.saveAndFlush(
			UserSettings(
				"""{"core.timeZone": "Europe/Zurich", "gtd.weeklyReviewDay": 5, "ui.compact": true, "ui.greeting": "Grüezi «Welt» 👋", "ui.nested": {"a": [1, null]}}""",
			),
		)
	}
}

/** Manual and sync links, with and without labels, between items of different types. */
class LinksBackupFixture(
	private val links: LinkRepository,
) : BackupFixture {
	override val contributor = LinksBackupContributor.ID

	override fun seed() {
		val task = ItemRef(ItemType.TASK, UuidV7.generate())
		val note = ItemRef(ItemType.NOTE, UuidV7.generate())
		val email = ItemRef(ItemType.EMAIL, UuidV7.generate())
		links.saveAllAndFlush(
			listOf(
				Link(task, note, "Notizen | Plan\nzweite Zeile", LinkOrigin.MANUAL),
				Link(note, task, null, LinkOrigin.SYNC),
				Link(task, email, "", LinkOrigin.MANUAL),
			),
		)
	}
}

/** A pending and a fired reminder. */
class RemindersBackupFixture(
	private val reminders: ReminderRepository,
) : BackupFixture {
	override val contributor = RemindersBackupContributor.ID

	override fun seed() {
		val task = ItemRef(ItemType.TASK, UuidV7.generate())
		val fired = Reminder(task, Instant.parse("2026-03-01T07:00:00Z"), ReminderChannel.NOTIFICATION)
		fired.markFired(Instant.parse("2026-03-01T07:00:00.250Z"))
		reminders.saveAllAndFlush(
			listOf(
				fired,
				Reminder(ItemRef(ItemType.EVENT, UuidV7.generate()), Instant.parse("2027-01-15T09:30:00Z"), ReminderChannel.NOTIFICATION),
			),
		)
	}
}
