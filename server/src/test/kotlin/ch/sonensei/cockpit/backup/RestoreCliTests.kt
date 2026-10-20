package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.api.RestoreCli
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.backupcontrib.LinksBackupFixture
import ch.sonensei.cockpit.core.backupcontrib.UserBackupFixture
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
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
	): Pair<Int, String> {
		val (url, username, password) = RestoreTestDatabases.create(DATABASE)
		val out = ByteArrayOutputStream()
		val code =
			RestoreCli.run(
				arrayOf(
					"--restore=$zip",
					"--spring.datasource.url=$url",
					"--spring.datasource.username=$username",
					"--spring.datasource.password=$password",
					"--cockpit.data-dir=$dataDir",
					*extra,
				),
				PrintStream(out, true, Charsets.UTF_8),
			)
		return code to out.toString(Charsets.UTF_8)
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
		private val dataDir: Path = Files.createTempDirectory("cockpit-restore-cli-data")

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) = RestoreTestDatabases.register(registry, DATABASE, dataDir)
	}
}
