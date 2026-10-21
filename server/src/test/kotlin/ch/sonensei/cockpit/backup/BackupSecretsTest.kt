package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupSecrets
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.backup.domain.SecretsEnvelope
import ch.sonensei.cockpit.backup.domain.secret
import ch.sonensei.cockpit.core.auth.cockpitUser
import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import ch.sonensei.cockpit.core.auth.domain.SetupEnrollment
import ch.sonensei.cockpit.core.auth.domain.SetupService
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnection
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionStatus
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.infra.OAuthConnectionRepository
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * A module with a mail password (stands in for the mail module's contributor, which uses the same mechanism): exports
 * it as a secret and records what it got back on import.
 */
class MailPasswordProbeContributor : BackupContributor {
	override val id = "mail-password-probe"

	@Volatile var imported: Map<String, String>? = null

	override fun export(writer: BackupDataWriter) = writer.secret(SECRETS, mapOf("account-1" to IMAP_PASSWORD))

	override fun import(reader: BackupDataReader) {
		imported = reader.secret<Map<String, String>>(SECRETS)
	}

	companion object {
		const val SECRETS = "probe_imap_passwords"
		const val IMAP_PASSWORD = "fictitious-imap-password-4711"
	}
}

@TestConfiguration(proxyBeanMethods = false)
class MailPasswordProbeConfiguration {
	@Bean
	fun mailPasswordProbeContributor() = MailPasswordProbeContributor()
}

/**
 * Secrets in backups (SPEC.md §10.2): with a passphrase they are in `data/secrets.enc.json` (Argon2id, AES-GCM) and
 * a restore with the passphrase brings them back; without, they are omitted, OAuth connections are "reconnect
 * required" and the next login must set up TOTP again before anything else; a wrong passphrase restores nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class, MailPasswordProbeConfiguration::class)
class BackupSecretsTest(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val backups: BackupService,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactions: TransactionTemplate,
	@Autowired private val setup: SetupService,
	@Autowired private val users: UserAccountRepository,
	@Autowired private val connections: OAuthConnectionRepository,
	@Autowired private val throttle: LoginThrottle,
	@Autowired private val clock: MutableClock,
	@Autowired private val probe: MailPasswordProbeContributor,
) {
	private val totp = Totp()
	private val mapper get() = backups.canonicalJson
	private lateinit var enrollment: SetupEnrollment

	@BeforeEach
	fun setUp() {
		RestoreTestDatabases.wipe(jdbc, dataDir)
		throttle.recordSuccess()
		probe.imported = null
		clock.set(MutableClockTestConfiguration.START)
		enrollment = setup.start(USERNAME, PASSWORD)
		setup.confirmTotp(code(enrollment.totpSecret))
		transactions.executeWithoutResult {
			connections.saveAndFlush(
				OAuthConnection(OAuthProvider.GOOGLE, "100000000000000000001", "jane.doe@example.com", "Jane Doe", "openid email", null)
					.apply { markConnected(EncryptedSecret(REFRESH_TOKEN), clock.instant()) },
			)
		}
	}

	private fun code(secret: String) = totp.generate(Base32.decode(secret), clock.instant())

	private fun export(passphrase: String?): ByteArray = ByteArrayOutputStream().also { backups.export(it, passphrase) }.toByteArray()

	@Test
	fun `with a passphrase the TOTP secret, OAuth tokens and mail passwords are only in secrets enc json, encrypted`() {
		val entries = Zips.read(export(PASSPHRASE))

		entries shouldContainKey BackupSecrets.PATH
		withClue("no file of the zip has a secret in plain text") {
			entries.forEach { (name, bytes) ->
				val text = String(bytes, Charsets.UTF_8)
				listOf(enrollment.totpSecret, REFRESH_TOKEN, MailPasswordProbeContributor.IMAP_PASSWORD).forEach { secret ->
					withClue(name) { text.contains(secret) shouldBe false }
				}
			}
		}
		val envelope = mapper.readValue(entries.getValue(BackupSecrets.PATH), SecretsEnvelope::class.java)
		envelope.kdf.algorithm shouldBe "argon2id"
		envelope.cipher shouldBe "AES-256-GCM"
		val secrets = mapper.readTree(BackupSecrets.decrypt(envelope, PASSPHRASE))
		secrets["user"]["totpSecret"].asString() shouldBe enrollment.totpSecret
		secrets["oauth_refresh_tokens"]
			.properties()
			.single()
			.value
			.asString() shouldBe REFRESH_TOKEN
		secrets[MailPasswordProbeContributor.SECRETS]["account-1"].asString() shouldBe MailPasswordProbeContributor.IMAP_PASSWORD
		mapper.readTree(entries.getValue("manifest.json"))["counts"]["secrets"].asLong() shouldBe 3
	}

	@Test
	fun `without a passphrase the secrets are omitted`() {
		val entries = Zips.read(export(null))

		entries shouldNotContainKey BackupSecrets.PATH
		entries.forEach { (name, bytes) ->
			val text = String(bytes, Charsets.UTF_8)
			listOf(enrollment.totpSecret, REFRESH_TOKEN, MailPasswordProbeContributor.IMAP_PASSWORD).forEach { secret ->
				withClue(name) { text.contains(secret) shouldBe false }
			}
		}
	}

	@Test
	fun `a restore with the passphrase brings the secrets back`() {
		val zip = export(PASSPHRASE)
		val report = report(zip)
		report["hasSecrets"].asBoolean() shouldBe true

		confirm(report["id"].asString(), PASSPHRASE).andExpect { status { isOk() } }

		val user = users.findFirstBy().shouldNotBeNull()
		user.totpReenrollRequired shouldBe false
		user.totpSecret.reveal() shouldBe enrollment.totpSecret
		val connection = connections.findAll().single()
		connection.status shouldBe OAuthConnectionStatus.CONNECTED
		connection.refreshToken?.reveal() shouldBe REFRESH_TOKEN
		probe.imported shouldBe mapOf("account-1" to MailPasswordProbeContributor.IMAP_PASSWORD)
		withClue("the old TOTP code logs in with full access") {
			val session = login(totp = code(enrollment.totpSecret)).andExpect { jsonPath("$.totpEnrollmentRequired") { value(false) } }
			mockMvc.get(DEVICES) { cookie(sessionCookie(session)) }.andExpect { status { isOk() } }
		}
	}

	@Test
	fun `after a restore without secrets accounts must reconnect and the next login forces TOTP re-enrollment`() {
		// Made with a passphrase but restored without it: the same as a backup without secrets.
		val zips = listOf(export(null), export(PASSPHRASE))
		zips.forEach { zip ->
			throttle.recordSuccess()
			confirm(report(zip)["id"].asString(), passphrase = null).andExpect { status { isOk() } }

			val connection = connections.findAll().single()
			connection.status shouldBe OAuthConnectionStatus.RECONNECT_REQUIRED
			connection.refreshToken shouldBe null
			probe.imported shouldBe null
			users.findFirstBy().shouldNotBeNull().totpReenrollRequired shouldBe true
		}

		withClue("TOTP does not log in, neither with the old secret nor at all") {
			login(totp = code(enrollment.totpSecret)).andExpect { status { isUnauthorized() } }
		}
		throttle.recordSuccess()
		withClue("the Android app cannot log in before TOTP is set up again") {
			mockMvc
				.post("/api/v1/auth/token") {
					contentType = MediaType.APPLICATION_JSON
					content =
						"""{"username":"$USERNAME","password":"$PASSWORD","recoveryCode":"${enrollment.recoveryCodes[1]}","deviceName":"Pixel"}"""
				}.andExpect { status { isForbidden() } }
		}
		throttle.recordSuccess()

		val session =
			sessionCookie(
				login(recoveryCode = enrollment.recoveryCodes[0]).andExpect {
					status { isOk() }
					jsonPath("$.totpEnrollmentRequired") { value(true) }
				},
			)
		withClue("the session may only set up TOTP again") {
			mockMvc.get(DEVICES) { cookie(session) }.andExpect { status { isForbidden() } }
			mockMvc.get("/api/v1/settings") { cookie(session) }.andExpect { status { isForbidden() } }
			mockMvc.get("/api/v1/auth/me") { cookie(session) }.andExpect {
				status { isOk() }
				jsonPath("$.totpEnrollmentRequired") { value(true) }
			}
		}

		val started =
			mapper.readTree(
				mockMvc
					.post(ENROLLMENT) {
						cookie(session)
						with(csrf())
					}.andExpect { status { isOk() } }
					.andReturn()
					.response.contentAsString,
			)
		val newSecret = started["totpSecret"].asString()
		mockMvc
			.post("$ENROLLMENT/confirm") {
				cookie(session)
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"code":"${code(newSecret)}"}"""
			}.andExpect { status { isNoContent() } }

		withClue("afterwards the same session has full access and the new TOTP secret logs in") {
			mockMvc.get(DEVICES) { cookie(session) }.andExpect { status { isOk() } }
			users.findFirstBy().shouldNotBeNull().totpReenrollRequired shouldBe false
			login(totp = code(newSecret)).andExpect {
				status { isOk() }
				jsonPath("$.totpEnrollmentRequired") { value(false) }
			}
		}
	}

	@Test
	fun `a wrong passphrase is a validation error, restores nothing and keeps the upload`() {
		val zip = export(PASSPHRASE)
		jdbc.update("update oauth_connection set email = 'changed.later@example.com'")
		val id = report(zip)["id"].asString()

		confirm(id, "a wrong fictitious passphrase").andExpect {
			status { isUnprocessableContent() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.problems[0]") { value("The passphrase is wrong") }
		}

		jdbc.queryForList("select email from oauth_connection", String::class.java) shouldContainExactly listOf("changed.later@example.com")
		Files.exists(dataDir.resolve("pre-restore")) shouldBe false
		withClue("the upload is kept for another try with the right passphrase") {
			confirm(id, PASSPHRASE).andExpect { status { isOk() } }
			jdbc.queryForList("select email from oauth_connection", String::class.java) shouldContainExactly listOf("jane.doe@example.com")
		}
	}

	@Test
	fun `the restore report tells whether a backup has secrets and warns what is lost without the passphrase`() {
		val withSecrets = report(export(PASSPHRASE))
		withSecrets["hasSecrets"].asBoolean() shouldBe true
		val warnings = strings(withSecrets["warnings"])
		warnings shouldContain "Without the backup's passphrase, the 1 connected Google/Microsoft accounts must be connected again"
		warnings.any { it.startsWith("The TOTP secret is restored only with the backup's passphrase") } shouldBe true

		val withoutSecrets = report(export(null))
		withoutSecrets["hasSecrets"].asBoolean() shouldBe false
		strings(withoutSecrets["warnings"]) shouldContain
			"The backup has no OAuth tokens: the 1 connected Google/Microsoft accounts must be connected again"
	}

	@Test
	fun `a passphrase for a backup without secrets restores it like one without passphrase`() {
		confirm(report(export(null))["id"].asString(), PASSPHRASE).andExpect { status { isOk() } }

		users.findFirstBy().shouldNotBeNull().totpReenrollRequired shouldBe true
		connections.findAll().single().status shouldBe OAuthConnectionStatus.RECONNECT_REQUIRED
	}

	@Test
	fun `the pre-restore backup is made with the restore's passphrase, so it keeps the current secrets`() {
		val zip = export(PASSPHRASE)

		confirm(report(zip)["id"].asString(), PASSPHRASE).andExpect { status { isOk() } }

		val preRestore = Files.list(dataDir.resolve("pre-restore")).use { it.toList() }.single()
		val entries = Zips.read(Files.readAllBytes(preRestore))
		val envelope = mapper.readValue(entries.getValue(BackupSecrets.PATH), SecretsEnvelope::class.java)
		mapper.readTree(BackupSecrets.decrypt(envelope, PASSPHRASE))["user"]["totpSecret"].asString() shouldBe enrollment.totpSecret
	}

	@Test
	fun `a restore without passphrase makes the pre-restore backup without secrets`() {
		confirm(report(export(PASSPHRASE))["id"].asString(), passphrase = null).andExpect { status { isOk() } }

		val preRestore = Files.list(dataDir.resolve("pre-restore")).use { it.toList() }.single()
		Zips.read(Files.readAllBytes(preRestore)) shouldNotContainKey BackupSecrets.PATH
	}

	@Test
	fun `an account still waiting for TOTP re-enrollment exports no TOTP secret and waits again after a restore with passphrase`() {
		jdbc.update("update app_user set totp_reenroll_required = true")
		val zip = export(PASSPHRASE)
		val envelope = mapper.readValue(Zips.read(zip).getValue(BackupSecrets.PATH), SecretsEnvelope::class.java)
		mapper.readTree(BackupSecrets.decrypt(envelope, PASSPHRASE)).has("user") shouldBe false

		confirm(report(zip)["id"].asString(), PASSPHRASE).andExpect { status { isOk() } }

		users.findFirstBy().shouldNotBeNull().totpReenrollRequired shouldBe true
		withClue("the OAuth token is restored all the same") {
			connections.findAll().single().status shouldBe OAuthConnectionStatus.CONNECTED
		}
	}

	@Test
	fun `a TOTP secret in the secrets that is not Base32 is a validation error and restores nothing`() {
		val zip =
			Zips.edit(export(PASSPHRASE), mapper, updateManifest = true) { entries ->
				val envelope = mapper.readValue(entries.getValue(BackupSecrets.PATH), SecretsEnvelope::class.java)
				val secrets = mapper.readTree(BackupSecrets.decrypt(envelope, PASSPHRASE)) as ObjectNode
				(secrets["user"] as ObjectNode).put("totpSecret", "not base32 at all!")
				entries[BackupSecrets.PATH] = mapper.writeValueAsBytes(BackupSecrets.encrypt(mapper.writeValueAsBytes(secrets), PASSPHRASE))
			}
		jdbc.update("update oauth_connection set email = 'changed.later@example.com'")

		confirm(report(zip)["id"].asString(), PASSPHRASE).andExpect {
			status { isUnprocessableContent() }
			jsonPath("$.problems[0]") { value("The TOTP secret in the backup's secrets is not valid") }
		}

		jdbc.queryForList("select email from oauth_connection", String::class.java) shouldContainExactly listOf("changed.later@example.com")
		users
			.findFirstBy()
			.shouldNotBeNull()
			.totpSecret
			.reveal() shouldBe enrollment.totpSecret
	}

	@Test
	fun `a corrupt secrets file with a matching manifest is a validation error with the passphrase, and ignored without`() {
		val zip =
			Zips.edit(export(PASSPHRASE), mapper, updateManifest = true) { entries ->
				entries[BackupSecrets.PATH] = "{\"formatVersion\":1}".toByteArray()
			}

		confirm(report(zip)["id"].asString(), PASSPHRASE).andExpect {
			status { isUnprocessableContent() }
			jsonPath("$.problems[0]") { value("data/secrets.enc.json is not valid") }
		}
		Files.exists(dataDir.resolve("pre-restore")) shouldBe false

		confirm(report(zip)["id"].asString(), passphrase = null).andExpect { status { isOk() } }
		users.findFirstBy().shouldNotBeNull().totpReenrollRequired shouldBe true
	}

	@Test
	fun `a restore passphrase longer than 256 characters is a 400 and keeps the upload`() {
		val id = report(export(PASSPHRASE))["id"].asString()

		confirm(id, "x".repeat(257)).andExpect { status { isBadRequest() } }

		confirm(id, PASSPHRASE).andExpect { status { isOk() } }
	}

	@Test
	fun `a backup started with a passphrase through the API includes the encrypted secrets`() {
		val id = startBackup("""{"passphrase":"$PASSPHRASE"}""").andExpect { status { isAccepted() } }.let { json(it)["id"].asString() }
		val zip = download(id)

		val envelope = mapper.readValue(Zips.read(zip).getValue(BackupSecrets.PATH), SecretsEnvelope::class.java)
		mapper.readTree(BackupSecrets.decrypt(envelope, PASSPHRASE))["user"]["totpSecret"].asString() shouldBe enrollment.totpSecret
	}

	@Test
	fun `a backup started without a body, with an empty body object or an empty passphrase has no secrets`() {
		listOf(null, "{}", """{"passphrase":null}""", """{"passphrase":""}""").forEach { body ->
			withClue("body $body") {
				val id = startBackup(body).andExpect { status { isAccepted() } }.let { json(it)["id"].asString() }
				Zips.read(download(id)) shouldNotContainKey BackupSecrets.PATH
			}
		}
	}

	@Test
	fun `a backup passphrase shorter than 12 or longer than 256 characters is a 400 and starts nothing`() {
		listOf("x".repeat(11), "x".repeat(257)).forEach { passphrase ->
			withClue("${passphrase.length} characters") {
				startBackup("""{"passphrase":"$passphrase"}""").andExpect {
					status { isBadRequest() }
					content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
				}
			}
		}
		withClue("12 and 256 characters are accepted") {
			listOf("x".repeat(12), "y".repeat(256)).forEach { passphrase ->
				val id = startBackup("""{"passphrase":"$passphrase"}""").andExpect { status { isAccepted() } }.let { json(it)["id"].asString() }
				val envelope = mapper.readValue(Zips.read(download(id)).getValue(BackupSecrets.PATH), SecretsEnvelope::class.java)
				BackupSecrets.decrypt(envelope, passphrase)
			}
		}
	}

	private fun startBackup(body: String?): ResultActionsDsl =
		mockMvc.post(BACKUP) {
			with(cockpitUser())
			with(csrf())
			if (body != null) {
				contentType = MediaType.APPLICATION_JSON
				content = body
			}
		}

	private fun strings(array: JsonNode): List<String> = (0 until array.size()).map { array[it].asString() }

	private fun json(result: ResultActionsDsl): JsonNode = mapper.readTree(result.andReturn().response.contentAsString)

	/** Waits for the backup job [id] to complete and downloads its zip. */
	private fun download(id: String): ByteArray {
		val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
		while (true) {
			val state = json(mockMvc.get("$BACKUP/$id") { with(cockpitUser()) })["state"].asString()
			if (state == "COMPLETED") break
			check(state == "RUNNING" && System.nanoTime() < deadline) { "Backup $id ended as $state" }
			Thread.sleep(20)
		}
		return mockMvc
			.get("$BACKUP/$id/download") { with(cockpitUser()) }
			.andExpect { status { isOk() } }
			.andReturn()
			.response.contentAsByteArray
	}

	private fun report(zip: ByteArray): JsonNode =
		mapper.readTree(
			mockMvc
				.post(RESTORE) {
					with(cockpitUser())
					with(csrf())
					contentType = MediaType("application", "zip")
					content = zip
				}.andExpect { status { isOk() } }
				.andReturn()
				.response.contentAsString,
		)

	private fun confirm(
		id: String,
		passphrase: String?,
	): ResultActionsDsl =
		mockMvc.post("$RESTORE/$id/confirm") {
			with(cockpitUser())
			with(csrf())
			if (passphrase != null) {
				contentType = MediaType.APPLICATION_JSON
				content = mapper.writeValueAsString(mapOf("passphrase" to passphrase))
			}
		}

	private fun login(
		totp: String? = null,
		recoveryCode: String? = null,
	): ResultActionsDsl =
		mockMvc.post("/api/v1/auth/login") {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content =
				mapper.writeValueAsString(
					mapOf("username" to USERNAME, "password" to PASSWORD, "totp" to totp, "recoveryCode" to recoveryCode),
				)
		}

	private fun sessionCookie(login: ResultActionsDsl): Cookie {
		val token =
			login
				.andReturn()
				.response
				.getCookie(SessionCookies.NAME)
				.shouldNotBeNull()
				.value
		return Cookie(SessionCookies.NAME, token)
	}

	companion object {
		private const val USERNAME = "jane.doe"
		private const val PASSWORD = "fictitious-password-1234"
		private const val PASSPHRASE = "fictitious backup passphrase"
		private const val REFRESH_TOKEN = "1//fictitious-refresh-token-0815"
		private const val RESTORE = "/api/v1/restore"
		private const val BACKUP = "/api/v1/backup"
		private const val ENROLLMENT = "/api/v1/auth/totp-enrollment"
		private const val DEVICES = "/api/v1/auth/devices"
		private val dataDir: Path = Files.createTempDirectory("cockpit-secrets-data")

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) = RestoreTestDatabases.register(registry, "backup_secrets", dataDir)
	}
}
