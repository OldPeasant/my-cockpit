package ch.sonensei.cockpit.core.backupcontrib

import ch.sonensei.cockpit.backup.domain.BackupFormat
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.backup.infra.ZipBackupReader
import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.CredentialCheck
import ch.sonensei.cockpit.core.auth.domain.LoginCredentials
import ch.sonensei.cockpit.core.auth.domain.LoginService
import ch.sonensei.cockpit.core.auth.domain.SetupEnrollment
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.crypto.SecretCipher
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.Link
import ch.sonensei.cockpit.core.items.domain.LinkOrigin
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.reminders.domain.Reminder
import ch.sonensei.cockpit.core.reminders.domain.ReminderChannel
import ch.sonensei.cockpit.core.reminders.infra.ReminderRepository
import ch.sonensei.cockpit.core.settings.domain.CoreSettings
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.zip.ZipFile
import kotlin.io.path.outputStream

/**
 * What the backup round trip ([ch.sonensei.cockpit.backup.BackupRoundTripTest]) does not show for the core
 * contributors: the TOTP secret stays out of every backup file and is replaced by a new, working one on import, the
 * readable mirrors and their Markdown escaping, an install without user or settings, and that restored rows are usable
 * through the auth and settings services. Same context as the login tests (test clock), so no extra database pool.
 *
 * Exports go through the real [BackupService] (every module's contributor); imports run only the four core
 * contributors, in their dependency order, after the core tables were emptied, because the shared test database still
 * holds other modules' data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class CoreBackupContributorsTests(
	@Autowired private val backups: BackupService,
	@Autowired private val userContributor: UserBackupContributor,
	@Autowired private val settingsContributor: SettingsBackupContributor,
	@Autowired private val linksContributor: LinksBackupContributor,
	@Autowired private val remindersContributor: RemindersBackupContributor,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactions: TransactionTemplate,
	@Autowired private val clock: MutableClock,
	@Autowired private val setup: SetupService,
	@Autowired private val loginService: LoginService,
	@Autowired private val users: UserAccountRepository,
	@Autowired private val cipher: SecretCipher,
	@Autowired private val settings: SettingsService,
	@Autowired private val links: LinkRepository,
	@Autowired private val reminders: ReminderRepository,
) : PostgresTestBase() {
	@TempDir
	lateinit var work: Path

	private val totp = Totp()

	@BeforeEach
	fun emptyCoreTables() {
		clock.set(MutableClockTestConfiguration.START)
		loginService.recordSuccess()
		emptyCore()
	}

	@Test
	fun `the TOTP secret is in no file of the backup, neither in plain text nor encrypted`() {
		val enrollment = activeOwner()
		val secret = Base32.decode(enrollment.totpSecret)
		val stored = jdbc.queryForObject("select totp_secret from app_user", String::class.java)!!

		val files = export()

		val forbidden =
			listOf(
				enrollment.totpSecret,
				enrollment.totpSecret.lowercase(),
				stored,
				Base64.getEncoder().encodeToString(secret),
				HexFormat.of().formatHex(secret),
			)
		files.keys.filter { it.startsWith(BackupFormat.DATA_DIR) || it.startsWith(BackupFormat.READABLE_DIR) }.size shouldNotBe 0
		files.forEach { (path, content) ->
			forbidden.forEach { value -> withClue("$path must not contain the TOTP secret") { content shouldNotContain value } }
		}
		val user = backups.canonicalJson.readTree(files.getValue("data/user.json"))
		user.propertyNames().toList() shouldContainExactly
			listOf("createdAt", "id", "passwordHash", "recoveryCodes", "totpConfirmedAt", "updatedAt", "username", "version")
		user["username"].asString() shouldBe USERNAME
	}

	@Test
	fun `after import the owner has a new decryptable TOTP secret, the old one no longer works, and a recovery code does`(
		output: CapturedOutput,
	) {
		val enrollment = activeOwner()
		val original = users.findFirstBy()!!
		val zip = exportZip()
		emptyCore()

		importCore(zip)

		withClue("the import warns that TOTP must be re-enrolled, without secrets or the username") {
			output.all shouldContain "Restored owner account without its TOTP secret: the owner must sign in with a recovery code"
			output.all shouldNotContain enrollment.totpSecret
			output.all shouldNotContain USERNAME
		}
		val restored = users.findFirstBy().shouldNotBeNull()
		restored.id shouldBe original.id
		restored.username shouldBe USERNAME
		restored.passwordHash shouldBe original.passwordHash
		restored.isActive shouldBe true
		val stored = jdbc.queryForObject("select totp_secret from app_user", String::class.java)!!
		Base32.decode(cipher.decrypt(stored)).size shouldBe Totp.SECRET_BYTES
		val newSecret = Base32.decode(restored.totpSecret.reveal())
		newSecret.size shouldBe Totp.SECRET_BYTES
		newSecret.contentEquals(Base32.decode(enrollment.totpSecret)) shouldBe false
		setup.isSetupRequired() shouldBe false

		val oldCode = totp.generate(Base32.decode(enrollment.totpSecret), clock.instant())
		withClue("a TOTP code of the old secret is rejected (it was not restored)") {
			loginService.verify(LoginCredentials(USERNAME, PASSWORD, oldCode, null, false)).shouldBeInstanceOf<CredentialCheck.Rejected>()
		}
		loginService.recordSuccess()
		withClue("the password hash and recovery code hashes survive, so the owner signs in with a recovery code") {
			loginService
				.verify(LoginCredentials(USERNAME, PASSWORD, null, enrollment.recoveryCodes.first(), false))
				.shouldBeInstanceOf<CredentialCheck.Verified>()
		}
		withClue("the new secret works for a TOTP login") {
			loginService
				.verify(LoginCredentials(USERNAME, PASSWORD, totp.generate(newSecret, clock.instant()), null, false))
				.shouldBeInstanceOf<CredentialCheck.Verified>()
		}
	}

	@Test
	fun `importing an owner without unused recovery codes warns that the owner is locked out`(output: CapturedOutput) {
		activeOwner()
		jdbc.update("update recovery_code set used_at = now()")
		val zip = exportZip()
		emptyCore()

		importCore(zip)

		output.all shouldContain "no unused recovery code left"
		withClue("only the lockout warning is logged, and it names neither the owner nor a secret") {
			output.all shouldNotContain "the owner must sign in with a recovery code"
			output.all shouldNotContain USERNAME
		}
	}

	@Test
	fun `importing an active owner warns with the number of unused recovery codes`(output: CapturedOutput) {
		val enrollment = activeOwner()
		jdbc.update("update recovery_code set used_at = now() where id = (select id from recovery_code order by id limit 1)")
		val zip = exportZip()
		emptyCore()

		importCore(zip)

		output.all shouldContain "sign in with a recovery code (${enrollment.recoveryCodes.size - 1} unused) and re-enroll TOTP"
	}

	@Test
	fun `importing an owner who never confirmed TOTP logs no re-enrollment warning`(output: CapturedOutput) {
		setup.start(USERNAME, PASSWORD)
		val zip = exportZip()
		emptyCore()

		importCore(zip)

		users.findFirstBy().shouldNotBeNull().isActive shouldBe false
		output.all shouldNotContain "Restored owner account without its TOTP secret"
	}

	@Test
	fun `an install without owner and settings exports no user or settings file, and importing it creates neither`() {
		val files = export()

		files shouldNotContainKey "data/user.json"
		files shouldNotContainKey "data/settings.json"
		files.getValue("readable/settings.md") shouldBe "# Settings\n\nAll settings are at their defaults.\n"

		importCore(exportZip())

		jdbc.queryForObject("select count(*) from app_user", Long::class.java) shouldBe 0
		jdbc.queryForObject("select count(*) from user_settings", Long::class.java) shouldBe 0
		setup.isSetupRequired() shouldBe true
		settings.current().version shouldBe 0
		settings.get(CoreSettings.TIME_ZONE) shouldBe CoreSettings.TIME_ZONE.default
	}

	@Test
	fun `restored settings are read through the settings service with their version`() {
		val changed = settings.update(mapOf("timeZone" to "Asia/Tokyo", "theme" to "dark", "weekStart" to "SUNDAY"))
		val zip = exportZip()
		emptyCore()
		settings.get(CoreSettings.TIME_ZONE) shouldBe CoreSettings.TIME_ZONE.default

		importCore(zip)

		settings.get(CoreSettings.TIME_ZONE) shouldBe "Asia/Tokyo"
		settings.get(CoreSettings.THEME) shouldBe "dark"
		settings.get(CoreSettings.WEEK_START) shouldBe "SUNDAY"
		settings.current() shouldBe changed
		withClue("the restored row keeps its version, so the next change bumps it as usual") {
			settings.update(mapOf("theme" to "light")).version shouldBe changed.version + 1
		}
	}

	@Test
	fun `the settings mirror lists the changed settings sorted by key with Markdown table cells escaped`() {
		jdbc.update(
			"insert into user_settings (id, created_at, updated_at, version, overrides) values (?, now(), now(), 1, ?::jsonb)",
			UuidV7.generate(),
			"""{"timeZone": "Asia/Tokyo", "ui.note": "a|b", "we|ird\nkey": 5, "path": "C:\\temp"}""",
		)

		val mirror = export().getValue("readable/settings.md")

		mirror shouldBe
			"""
			# Settings

			Settings changed from their defaults:

			| Setting | Value |
			| --- | --- |
			| path | "C:\\\\temp" |
			| timeZone | "Asia/Tokyo" |
			| ui.note | "a\|b" |
			| we\|ird key | 5 |

			""".trimIndent()
	}

	@Test
	fun `the links mirror lists every link in id order with type-prefixed ids and an escaped label`() {
		val task = ItemRef(ItemType.TASK, UuidV7.generate())
		val note = ItemRef(ItemType.NOTE, UuidV7.generate())
		val email = ItemRef(ItemType.EMAIL, UuidV7.generate())
		val first = Link(task, note, "Notizen | Plan\nzweite Zeile\r\ndritte", LinkOrigin.MANUAL)
		val second = Link(note, email, null, LinkOrigin.SYNC)
		val third = Link(email, task, "C:\\temp\\x", LinkOrigin.MANUAL)
		transactions.executeWithoutResult { links.saveAllAndFlush(listOf(third, first, second)) }

		val mirror = export().getValue("readable/links.md")

		mirror shouldBe
			"# Links\n\n" +
			"Links between items, by item type and id (see `data/` for the items).\n\n" +
			"| From | To | Label | Origin |\n" +
			"| --- | --- | --- | --- |\n" +
			"| task:${task.id} | note:${note.id} | Notizen \\| Plan zweite Zeile dritte | manual |\n" +
			"| note:${note.id} | email:${email.id} |  | sync |\n" +
			"| email:${email.id} | task:${task.id} | C:\\\\temp\\\\x | manual |\n"
	}

	@Test
	fun `the reminders mirror lists only pending reminders, earliest first, in UTC`() {
		val event = ItemRef(ItemType.EVENT, UuidV7.generate())
		val task = ItemRef(ItemType.TASK, UuidV7.generate())
		val later = Reminder(event, Instant.parse("2027-01-15T09:30:00Z"), ReminderChannel.NOTIFICATION)
		val earlier = Reminder(task, Instant.parse("2026-12-24T18:00:00Z"), ReminderChannel.NOTIFICATION)
		val fired = Reminder(task, Instant.parse("2026-03-01T07:00:00Z"), ReminderChannel.NOTIFICATION)
		fired.markFired(Instant.parse("2026-03-01T07:00:00.250Z"))
		transactions.executeWithoutResult { reminders.saveAllAndFlush(listOf(later, earlier, fired)) }

		val files = export()

		files.getValue("readable/reminders.md") shouldBe
			"# Reminders\n\n" +
			"Reminders not fired yet, earliest first (times in UTC).\n\n" +
			"| When | Item | Channel |\n" +
			"| --- | --- | --- |\n" +
			"| 2026-12-24T18:00:00Z | task:${task.id} | notification |\n" +
			"| 2027-01-15T09:30:00Z | event:${event.id} | notification |\n"
		withClue("the data file keeps the fired reminder, so a restore does not fire it again") {
			files
				.getValue("data/reminders.jsonl")
				.lines()
				.filter { it.isNotBlank() }
				.size shouldBe 3
			files.getValue("data/reminders.jsonl") shouldContain "\"firedAt\":\"2026-03-01T07:00:00.250Z\""
		}
	}

	@Test
	fun `empty links and reminders give mirrors with only their table header`() {
		val files = export()

		files.getValue("readable/links.md") shouldContain "| From | To | Label | Origin |\n| --- | --- | --- | --- |\n"
		files
			.getValue("readable/links.md")
			.lines()
			.filter { it.startsWith("| ") }
			.size shouldBe 2
		files
			.getValue("readable/reminders.md")
			.lines()
			.filter { it.startsWith("| ") }
			.size shouldBe 2
		files shouldContainKey "readable/settings.md"
	}

	/** An active owner created by the real setup flow (fictitious credentials). */
	private fun activeOwner(): SetupEnrollment {
		val enrollment = setup.start(USERNAME, PASSWORD)
		setup.confirmTotp(totp.generate(Base32.decode(enrollment.totpSecret), clock.instant()))
		return enrollment
	}

	/** Deletes the core contributors' rows (sessions, devices and recovery codes go with the user). */
	private fun emptyCore() {
		jdbc.update("delete from app_user")
		jdbc.update("delete from user_settings")
		jdbc.update("delete from link")
		jdbc.update("delete from reminder")
	}

	private fun exportZip(): Path {
		val zip = work.resolve("backup-${UuidV7.generate()}.zip")
		zip.outputStream().use { backups.export(it) }
		return zip
	}

	/** Every file of a fresh export, as UTF-8 text. */
	private fun export(): Map<String, String> =
		ZipFile(exportZip().toFile()).use { zip ->
			zip
				.entries()
				.toList()
				.filterNot { it.isDirectory }
				.associate { it.name to zip.getInputStream(it).use { input -> String(input.readAllBytes(), Charsets.UTF_8) } }
		}

	/** Imports [zip] with the core contributors only, in their dependency order, in one transaction. */
	private fun importCore(zip: Path) {
		ZipBackupReader.open(zip, backups.canonicalJson).use { reader ->
			transactions.executeWithoutResult {
				listOf(userContributor, settingsContributor, linksContributor, remindersContributor).forEach { it.import(reader) }
			}
		}
	}

	companion object {
		private const val USERNAME = "jdoe"
		private const val PASSWORD = "fictitious-Passw0rd-for-tests"
	}
}
