package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.api.RestoreCli
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.SetupEnrollment
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.backupcontrib.LinksBackupFixture
import ch.sonensei.cockpit.core.backupcontrib.UserBackupFixture
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

/**
 * `--restore=/path/backup.zip` (SPEC.md §10.1): restores on a fresh install and exits; the setup flow is then skipped.
 * [RestoreCli.run] starts its own application (no web server) against this test's database, like `java -jar` would.
 */
@SpringBootTest
class RestoreCliTests(
	@Autowired private val backups: BackupService,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactions: TransactionTemplate,
	@Autowired private val users: UserAccountRepository,
	@Autowired private val setup: SetupService,
	@Autowired private val links: LinkRepository,
) {
	@BeforeEach
	fun setUp() = RestoreTestDatabases.wipe(jdbc, dataDir)

	private fun cli(
		zip: Path,
		vararg extra: String,
	): Pair<Int, String> = cliWithDataDir(zip, dataDir, *extra)

	private fun cliWithDataDir(
		zip: Path,
		dir: Path,
		vararg extra: String,
	): Pair<Int, String> = runCli(zip, dir, extra.toList(), passphrase = null, explicitPassphrase = false)

	/** Like [cli], with [passphrase] as if it were in `COCKPIT_RESTORE_PASSPHRASE`. */
	private fun cliWithPassphrase(
		zip: Path,
		passphrase: String?,
	): Pair<Int, String> = runCli(zip, dataDir, emptyList(), passphrase, explicitPassphrase = true)

	private fun runCli(
		zip: Path,
		dir: Path,
		extra: List<String>,
		passphrase: String?,
		explicitPassphrase: Boolean,
	): Pair<Int, String> {
		val (url, username, password) = RestoreTestDatabases.create(DATABASE)
		val out = ByteArrayOutputStream()
		val args =
			arrayOf(
				"--restore=$zip",
				"--spring.datasource.url=$url",
				"--spring.datasource.username=$username",
				"--spring.datasource.password=$password",
				"--cockpit.data-dir=$dir",
				*extra.toTypedArray(),
			)
		val print = PrintStream(out, true, Charsets.UTF_8)
		val code = if (explicitPassphrase) RestoreCli.run(args, print, passphrase) else RestoreCli.run(args, print)
		return code to out.toString(Charsets.UTF_8)
	}

	/** An owner who finished the setup (TOTP confirmed), and a backup of it made with [passphrase]. */
	private fun ownerBackup(
		work: Path,
		passphrase: String?,
	): Pair<SetupEnrollment, Path> {
		val enrollment = setup.start("jane.doe", "fictitious-password-1234")
		setup.confirmTotp(Totp().generate(Base32.decode(enrollment.totpSecret), Instant.now()))
		val zip = work.resolve("backup.zip")
		zip.outputStream().use { backups.export(it, passphrase) }
		return enrollment to zip
	}

	@Test
	fun `with the passphrase the CLI restores the secrets, so TOTP works as before`(
		@TempDir work: Path,
	) {
		val (enrollment, zip) = ownerBackup(work, CLI_PASSPHRASE)
		jdbc.update("update app_user set username = 'mmuster'")

		val (code, output) = cliWithPassphrase(zip, CLI_PASSPHRASE)

		code shouldBe 0
		output shouldNotContain RestoreCli.PASSPHRASE_ENV
		usernames() shouldBe listOf("jane.doe")
		val user = users.findFirstBy()!!
		user.totpReenrollRequired shouldBe false
		user.totpSecret.reveal() shouldBe enrollment.totpSecret
	}

	@Test
	fun `without the passphrase the CLI says how to restore the secrets and the owner must re-enroll TOTP`(
		@TempDir work: Path,
	) {
		val (enrollment, zip) = ownerBackup(work, CLI_PASSPHRASE)

		val (code, output) = cliWithPassphrase(zip, null)

		code shouldBe 0
		output shouldContain "the backup's secrets are not restored; set COCKPIT_RESTORE_PASSPHRASE to its passphrase"
		val user = users.findFirstBy()!!
		user.totpReenrollRequired shouldBe true
		user.totpSecret.reveal() shouldNotBe enrollment.totpSecret
	}

	@Test
	fun `a backup without secrets restores without the hint`(
		@TempDir work: Path,
	) {
		val (_, zip) = ownerBackup(work, null)

		val (code, output) = cliWithPassphrase(zip, null)

		code shouldBe 0
		output shouldNotContain RestoreCli.PASSPHRASE_ENV
		users.findFirstBy()!!.totpReenrollRequired shouldBe true
	}

	@Test
	fun `a wrong passphrase exits with 1, says so and changes nothing`(
		@TempDir work: Path,
	) {
		val (_, zip) = ownerBackup(work, CLI_PASSPHRASE)
		jdbc.update("update app_user set username = 'mmuster'")

		val (code, output) = cliWithPassphrase(zip, "a wrong fictitious passphrase")

		code shouldBe 1
		output shouldContain "Problem: The passphrase is wrong"
		output shouldContain "nothing was changed"
		output shouldNotContain "fictitious passphrase"
		usernames() shouldBe listOf("mmuster")
		Files.exists(dataDir.resolve("pre-restore")) shouldBe false
	}

	private fun usernames() = jdbc.queryForList("select username from app_user", String::class.java)

	@Test
	fun `restores a backup on a fresh install, exits with 0 and setup is skipped afterwards`(
		@TempDir work: Path,
	) {
		transactions.executeWithoutResult { UserBackupFixture(users).seed() }
		val zip = work.resolve("backup.zip")
		zip.outputStream().use { backups.export(it) }
		RestoreTestDatabases.wipe(jdbc, dataDir)
		setup.isSetupRequired() shouldBe true

		val (code, output) = cli(zip)

		code shouldBe 0
		output shouldContain "user: 1"
		output shouldContain "setup is skipped"
		usernames() shouldBe listOf("jdoe")
		setup.isSetupRequired() shouldBe false
		dataDir
			.resolve("pre-restore")
			.listDirectoryEntries()
			.single()
			.name
			.endsWith(".zip") shouldBe true
	}

	@Test
	fun `an invalid backup exits with 1 and changes nothing`(
		@TempDir work: Path,
	) {
		val zip = work.resolve("backup.zip").also { it.writeText("not a zip") }

		val (code, output) = cli(zip)

		code shouldBe 1
		output shouldContain "Problem: Not a zip file"
		usernames().shouldBeEmpty()
		Files.exists(dataDir.resolve("pre-restore")) shouldBe false
	}

	@Test
	fun `restoring a backup without owner account exits with 0 and tells to run the setup`(
		@TempDir work: Path,
	) {
		val zip = work.resolve("backup.zip")
		zip.outputStream().use { backups.export(it) }

		// A configured port is replaced: the restore never listens.
		val (code, output) = cli(zip, "--server.port=18080")

		code shouldBe 0
		output shouldContain "Warning: The backup has no owner account"
		output shouldContain "open the app to run the setup"
		setup.isSetupRequired() shouldBe true
	}

	@Test
	fun `a valid backup whose data cannot be imported exits with 1, names the file and changes nothing`(
		@TempDir work: Path,
	) {
		transactions.executeWithoutResult { UserBackupFixture(users).seed() }
		val exported = ByteArrayOutputStream().also { backups.export(it) }.toByteArray()
		val zip = work.resolve("backup.zip")
		Files.write(
			zip,
			Zips.edit(exported, backups.canonicalJson, updateManifest = true) { entries ->
				entries["data/settings.json"] = "null".toByteArray()
			},
		)
		jdbc.update("update app_user set username = 'mmuster'")

		val (code, output) = cli(zip)

		code shouldBe 1
		output shouldContain "Problem: data/settings.json is not valid"
		output shouldContain "nothing was changed"
		usernames() shouldBe listOf("mmuster")
	}

	@Test
	fun `a backup whose import breaks a constraint exits with 1 and changes nothing`(
		@TempDir work: Path,
	) {
		transactions.executeWithoutResult {
			UserBackupFixture(users).seed()
			LinksBackupFixture(links).seed()
		}
		val exported = ByteArrayOutputStream().also { backups.export(it) }.toByteArray()
		val zip = work.resolve("backup.zip")
		Files.write(
			zip,
			Zips.edit(exported, backups.canonicalJson, updateManifest = true) { entries ->
				// The same link twice: valid JSON, but a duplicate primary key on import.
				val first = String(entries.getValue("data/links.jsonl")).lines().first()
				entries["data/links.jsonl"] = "$first\n$first\n".toByteArray()
			},
		)
		jdbc.update("update app_user set username = 'mmuster'")
		val linksBefore = jdbc.queryForObject("select count(*) from link", Long::class.java)

		val (code, output) = cli(zip)

		code shouldBe 1
		output shouldContain "Restore: The backup could not be imported; nothing was changed (see the log)"
		usernames() shouldBe listOf("mmuster")
		jdbc.queryForObject("select count(*) from link", Long::class.java) shouldBe linksBefore
	}

	@Test
	fun `a backup with a null recovery code exits with 1 and a report`(
		@TempDir work: Path,
	) {
		transactions.executeWithoutResult { UserBackupFixture(users).seed() }
		val exported = ByteArrayOutputStream().also { backups.export(it) }.toByteArray()
		val zip = work.resolve("backup.zip")
		Files.write(
			zip,
			Zips.edit(exported, backups.canonicalJson, updateManifest = true) { entries ->
				val user = backups.canonicalJson.readTree(entries.getValue("data/user.json")) as ObjectNode
				user.putArray("recoveryCodes").addNull()
				entries["data/user.json"] = backups.canonicalJson.writeValueAsBytes(user)
			},
		)
		jdbc.update("update app_user set username = 'mmuster'")

		val (code, output) = cli(zip)

		code shouldBe 1
		output shouldContain "user: 1"
		output shouldContain "Problem: data/user.json is not valid"
		output shouldContain "Restore: the backup is not valid; nothing was changed"
		usernames() shouldBe listOf("mmuster")
		Files.exists(dataDir.resolve("pre-restore")) shouldBe false
	}

	@Test
	fun `a failing pre-restore backup exits with 1 and changes nothing`(
		@TempDir work: Path,
	) {
		transactions.executeWithoutResult { UserBackupFixture(users).seed() }
		val zip = work.resolve("backup.zip")
		zip.outputStream().use { backups.export(it) }
		jdbc.update("update app_user set username = 'mmuster'")
		// A file where the pre-restore folder belongs: the current data cannot be backed up.
		dataDir.resolve("pre-restore").writeText("in the way")

		val (code, output) = cli(zip)

		code shouldBe 1
		output shouldContain "Restore: The current data could not be backed up; nothing was changed (see the log)"
		usernames() shouldBe listOf("mmuster")
		dataDir.listDirectoryEntries().map { it.name } shouldBe listOf("pre-restore")
	}

	@Test
	fun `a restore over existing data replaces it and saves it to pre-restore first`(
		@TempDir work: Path,
	) {
		transactions.executeWithoutResult { UserBackupFixture(users).seed() }
		val zip = work.resolve("backup.zip")
		zip.outputStream().use { backups.export(it) }
		jdbc.update("update app_user set username = 'mmuster'")

		val (code, output) = cli(zip)

		code shouldBe 0
		output shouldContain "setup is skipped"
		usernames() shouldBe listOf("jdoe")
		val saved =
			Zips.read(
				dataDir
					.resolve("pre-restore")
					.listDirectoryEntries()
					.single()
					.toFile()
					.readBytes(),
			)
		String(saved.getValue("data/user.json")) shouldContain "mmuster"
	}

	@Test
	fun `restores into a data directory that does not exist yet`(
		@TempDir work: Path,
	) {
		transactions.executeWithoutResult { UserBackupFixture(users).seed() }
		val zip = work.resolve("backup.zip")
		zip.outputStream().use { backups.export(it) }
		RestoreTestDatabases.wipe(jdbc, dataDir)
		val freshDataDir = work.resolve("not-created-yet")

		val (code, output) = cliWithDataDir(zip, freshDataDir)

		code shouldBe 0
		output shouldContain "setup is skipped"
		usernames() shouldBe listOf("jdoe")
		freshDataDir
			.resolve("pre-restore")
			.listDirectoryEntries()
			.single()
			.name
			.endsWith(".zip") shouldBe true
	}

	@Test
	fun `only a --restore= argument requests the restore`() {
		RestoreCli.isRequested(arrayOf("--restore=/srv/backup.zip")) shouldBe true
		RestoreCli.isRequested(arrayOf("--server.port=8080", "--restore=backup.zip")) shouldBe true
		RestoreCli.isRequested(arrayOf("--server.port=8080")) shouldBe false
		RestoreCli.isRequested(arrayOf("--restore")) shouldBe false
		RestoreCli.isRequested(emptyArray()) shouldBe false
	}

	@Test
	fun `a missing file exits with 2`(
		@TempDir work: Path,
	) {
		val (code, output) = cli(work.resolve("missing.zip"))

		code shouldBe 2
		output shouldContain "is not a file"
	}

	companion object {
		private const val DATABASE = "backup_restore_cli"
		private const val CLI_PASSPHRASE = "fictitious cli backup passphrase"
		private val dataDir: Path = Files.createTempDirectory("cockpit-restore-cli-data")

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) = RestoreTestDatabases.register(registry, DATABASE, dataDir)
	}
}
