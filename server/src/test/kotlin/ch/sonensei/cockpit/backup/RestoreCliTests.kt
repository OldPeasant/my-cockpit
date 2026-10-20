package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.api.RestoreCli
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.backupcontrib.UserBackupFixture
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
) {
	@BeforeEach
	fun setUp() = RestoreTestDatabases.wipe(jdbc, dataDir)

	private fun cli(zip: Path): Pair<Int, String> {
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
