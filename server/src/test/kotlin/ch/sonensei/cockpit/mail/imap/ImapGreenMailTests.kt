package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnection
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.domain.OAuthResource
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenService
import ch.sonensei.cockpit.core.oauth.domain.TokenGrant
import ch.sonensei.cockpit.core.oauth.infra.OAuthConnectionRepository
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetup
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import jakarta.mail.Folder
import jakarta.mail.Session
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * IMAP/SMTP against GreenMail (plain, implicit TLS with a certificate for `localhost`, XOAUTH2): connection test,
 * folder sync with role detection and overrides, and the connection pool. GreenMail offers no STARTTLS, which proves
 * that STARTTLS is required and never silently skipped.
 */
@ExternalTest
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@DirtiesContext
@TestPropertySource(
	properties = [
		"cockpit.mail.ssl-trust=localhost",
		"cockpit.mail.pool.max-connections-per-account=2",
		"cockpit.mail.pool.borrow-timeout=300ms",
		"cockpit.mail.connect-timeout=3s",
		"cockpit.mail.read-timeout=5s",
	],
)
class ImapGreenMailTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountService,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val pool: ImapConnectionPool,
	@Autowired private val connections: OAuthConnectionRepository,
	@Autowired private val tokens: OAuthTokenService,
) : PostgresTestBase() {
	@BeforeEach
	fun setUp() {
		jdbc.update("delete from mail_account")
		jdbc.update("delete from oauth_connection")
		greenMail.setUser(EMAIL, USER, PASSWORD)
	}

	private fun server(
		setup: ServerSetup,
		security: MailSecurity,
	) = MailServer(HOST, setup.port, security)

	private fun passwordAccount(
		password: String = PASSWORD,
		imap: MailServer = server(greenMail.imap.serverSetup, MailSecurity.NONE),
		smtp: MailServer = server(greenMail.smtp.serverSetup, MailSecurity.NONE),
	): MailAccount =
		accounts.create(
			NewMailAccount(
				type = MailAccountType.IMAP_SMTP,
				displayName = "GreenMail",
				emailAddress = EMAIL,
				imap = imap,
				smtp = smtp,
				username = USER,
				password = password,
				oauthConnectionId = null,
				signatureMd = null,
				notifyNewMail = null,
				syncWindowMonths = null,
			),
		)

	private fun testViaApi(account: MailAccount) =
		json.readTree(
			mockMvc
				.post("${MailAccountController.BASE}/${account.id}/test") { with(csrf()) }
				.andExpect { status { isOk() } }
				.andReturn()
				.response.contentAsString,
		)

	/** Creates folders on the server as the owner would in another mail client. */
	private fun createRemoteFolders(vararg paths: String) {
		val session = Session.getInstance(Properties())
		session.getStore("imap").use { store ->
			store.connect(HOST, greenMail.imap.port, USER, PASSWORD)
			paths.forEach { store.getFolder(it).create(Folder.HOLDS_MESSAGES or Folder.HOLDS_FOLDERS) shouldBe true }
		}
	}

	private fun deleteRemoteFolder(path: String) {
		Session.getInstance(Properties()).getStore("imap").use { store ->
			store.connect(HOST, greenMail.imap.port, USER, PASSWORD)
			store.getFolder(path).delete(true) shouldBe true
		}
	}

	@Test
	fun `test connection logs in to plain IMAP and SMTP`() {
		val result = testViaApi(passwordAccount())

		result["imap"]["ok"].asBoolean() shouldBe true
		result["smtp"]["ok"].asBoolean() shouldBe true
		result["imap"]["errorCode"].isNull shouldBe true
	}

	@Test
	fun `a wrong password fails both logins with AUTHENTICATION_FAILED`() {
		val result = testViaApi(passwordAccount(password = "wrong-fictitious-password"))

		result["imap"]["ok"].asBoolean() shouldBe false
		result["imap"]["errorCode"].asString() shouldBe "AUTHENTICATION_FAILED"
		result["smtp"]["errorCode"].asString() shouldBe "AUTHENTICATION_FAILED"
		result.toString().contains("wrong-fictitious-password") shouldBe false
	}

	@Test
	fun `implicit TLS works with a trusted certificate whose name matches`() {
		val account =
			passwordAccount(
				imap = server(greenMail.imaps.serverSetup, MailSecurity.SSL_TLS),
				smtp = server(greenMail.smtps.serverSetup, MailSecurity.SSL_TLS),
			)

		accounts.testConnection(account.id) shouldBe ConnectionTestResult(null, null)
	}

	@Test
	fun `TLS against a plain port fails with TLS_FAILED`() {
		val account =
			passwordAccount(
				imap = server(greenMail.imap.serverSetup, MailSecurity.SSL_TLS),
				smtp = server(greenMail.smtp.serverSetup, MailSecurity.SSL_TLS),
			)

		accounts.testConnection(account.id) shouldBe ConnectionTestResult(MailConnectionError.TLS_FAILED, MailConnectionError.TLS_FAILED)
	}

	@Test
	fun `STARTTLS is required - a server without it is not used in plain text`() {
		val account =
			passwordAccount(
				imap = server(greenMail.imap.serverSetup, MailSecurity.STARTTLS),
				smtp = server(greenMail.smtp.serverSetup, MailSecurity.STARTTLS),
			)

		accounts.testConnection(account.id) shouldBe ConnectionTestResult(MailConnectionError.TLS_FAILED, MailConnectionError.TLS_FAILED)
	}

	@Test
	fun `an unreachable server fails with CONNECTION_FAILED`() {
		val closedPort = ServerSocket(0).use { it.localPort }
		val account =
			passwordAccount(imap = MailServer(HOST, closedPort, MailSecurity.NONE), smtp = MailServer(HOST, closedPort, MailSecurity.NONE))

		accounts.testConnection(account.id) shouldBe
			ConnectionTestResult(MailConnectionError.CONNECTION_FAILED, MailConnectionError.CONNECTION_FAILED)
	}

	@Test
	fun `a Google account logs in with XOAUTH2 using the connection's access token`() {
		greenMail.setUser(OAUTH_EMAIL, OAUTH_EMAIL, ACCESS_TOKEN)
		val connection = connections.save(OAuthConnection(OAuthProvider.GOOGLE, "subject-1", OAUTH_EMAIL, "Jane", "openid", null)).id
		tokens.remember(connection, OAuthResource.MAIL, TokenGrant(ACCESS_TOKEN, Duration.ofHours(1), null, null, null))
		val account = oauthAccount(connection)

		accounts.testConnection(account.id) shouldBe ConnectionTestResult(null, null)
		account.username shouldBe OAUTH_EMAIL
	}

	@Test
	fun `an OAuth connection without usable token reports OAUTH_RECONNECT_REQUIRED`() {
		val connection = connections.save(OAuthConnection(OAuthProvider.GOOGLE, "subject-2", OAUTH_EMAIL, "Jane", "openid", null)).id
		tokens.forget(connection)
		val account = oauthAccount(connection)

		accounts.testConnection(account.id) shouldBe
			ConnectionTestResult(MailConnectionError.OAUTH_RECONNECT_REQUIRED, MailConnectionError.OAUTH_RECONNECT_REQUIRED)
	}

	private fun oauthAccount(connection: UUID) =
		accounts.create(
			NewMailAccount(
				type = MailAccountType.GOOGLE,
				displayName = null,
				emailAddress = OAUTH_EMAIL,
				imap = server(greenMail.imap.serverSetup, MailSecurity.NONE),
				smtp = server(greenMail.smtp.serverSetup, MailSecurity.NONE),
				username = null,
				password = null,
				oauthConnectionId = connection,
				signatureMd = null,
				notifyNewMail = null,
				syncWindowMonths = null,
			),
		)

	@Test
	fun `folder sync stores the folder list and detects roles`() {
		createRemoteFolders("Sent", "Drafts", "Trash", "Junk", "Archive", "Projects", "Projects.Sent")
		val account = passwordAccount()

		val body =
			json.readTree(
				mockMvc
					.post("${MailAccountController.BASE}/${account.id}/folders/sync") { with(csrf()) }
					.andExpect { status { isOk() } }
					.andReturn()
					.response.contentAsString,
			)

		val items = body["items"]
		val byPath =
			(0 until items.size()).associate {
				items[it]["path"].asString() to
					items[it]["role"]
						.takeUnless { r ->
							r.isNull
						}?.asString()
			}
		byPath.keys shouldContainAll listOf("INBOX", "Sent", "Drafts", "Trash", "Junk", "Archive", "Projects", "Projects.Sent")
		byPath["INBOX"] shouldBe "INBOX"
		byPath["Sent"] shouldBe "SENT"
		byPath["Drafts"] shouldBe "DRAFTS"
		byPath["Trash"] shouldBe "TRASH"
		byPath["Junk"] shouldBe "SPAM"
		byPath["Archive"] shouldBe "ARCHIVE"
		byPath["Projects"] shouldBe null
		byPath["Projects.Sent"] shouldBe null
		folders.findByAccountIdAndPath(account.id, "Projects.Sent")!!.let {
			it.name shouldBe "Sent"
			it.delimiter shouldBe "."
		}
		mockMvc.get("${MailAccountController.BASE}/${account.id}/folders").andExpect {
			status { isOk() }
			jsonPath("$.items.length()") { value(items.size()) }
		}
	}

	private fun syncViaApi(accountId: UUID) {
		mockMvc
			.post("${MailAccountController.BASE}/$accountId/folders/sync") { with(csrf()) }
			.andExpect { status { isOk() } }
	}

	@Test
	fun `a manual role survives the next sync and vanished folders are removed`() {
		createRemoteFolders("Archive", "Old Mail", "Junk")
		val account = passwordAccount()
		syncViaApi(account.id)
		val oldMail = folders.findByAccountIdAndPath(account.id, "Old Mail")!!

		mockMvc
			.patch("${MailAccountController.BASE}/${account.id}/folders/${oldMail.id}") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"role":"ARCHIVE"}"""
				header(HttpHeaders.IF_MATCH, "\"${oldMail.version}\"")
			}.andExpect { status { isOk() } }
		deleteRemoteFolder("Junk")
		syncViaApi(account.id)

		folders.findByAccountIdAndPath(account.id, "Old Mail")!!.role shouldBe MailFolderRole.ARCHIVE
		folders.findByAccountIdAndPath(account.id, "Archive")!!.role shouldBe null
		folders.findByAccountIdOrderByPathAsc(account.id).map { it.path } shouldNotContain "Junk"
	}

	@Test
	fun `folder sync with a rejected login answers 502 with the error code`() {
		val account = passwordAccount(password = "wrong-fictitious-password")

		mockMvc
			.post("${MailAccountController.BASE}/${account.id}/folders/sync") { with(csrf()) }
			.andExpect {
				status { isEqualTo(502) }
				content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
				jsonPath("$.errorCode") { value("AUTHENTICATION_FAILED") }
			}
	}

	@Test
	fun `the pool reuses connections and never opens more than the limit`() {
		val account = passwordAccount()

		repeat(3) { pool.withStore(account) { it.isConnected shouldBe true } }
		pool.openConnections(account.id) shouldBe 1
		pool.idleConnections(account.id) shouldBe 1

		val held = CountDownLatch(2)
		val release = CountDownLatch(1)
		val executor = Executors.newFixedThreadPool(2)
		try {
			repeat(2) {
				executor.submit {
					pool.withStore(account) {
						held.countDown()
						release.await(10, TimeUnit.SECONDS)
					}
				}
			}
			held.await(10, TimeUnit.SECONDS) shouldBe true
			pool.openConnections(account.id) shouldBe 2

			shouldThrow<MailConnectionException> { pool.withStore(account) { } }.error shouldBe MailConnectionError.POOL_EXHAUSTED
		} finally {
			release.countDown()
			executor.shutdown()
			executor.awaitTermination(10, TimeUnit.SECONDS)
		}
		pool.idleConnections(account.id) shouldBe 2
		pool.withStore(account) { }
		pool.openConnections(account.id) shouldBe 2
	}

	@Test
	fun `changing the credentials closes the pooled connections`() {
		val account = passwordAccount()
		pool.withStore(account) { }
		pool.idleConnections(account.id) shouldBe 1

		accounts.update(account.id, MailAccountChanges(displayName = "Renamed"), "\"${account.version}\"")
		pool.idleConnections(account.id) shouldBe 1

		accounts.update(account.id, MailAccountChanges(password = "new-fictitious-password"), "\"${account.version + 1}\"")

		pool.openConnections(account.id) shouldBe 0
	}

	@Test
	fun `a failed operation does not return its connection to the pool`() {
		val account = passwordAccount()

		shouldThrow<IllegalStateException> { pool.withStore(account) { error("fictitious failure") } }

		pool.openConnections(account.id) shouldBe 0
	}

	companion object {
		private const val HOST = "localhost"
		private const val EMAIL = "jane.doe@example.test"
		private const val USER = "jane"
		private const val PASSWORD = "fictitious-password"
		private const val OAUTH_EMAIL = "jane@gmail.example"
		private const val ACCESS_TOKEN = "fictitious-access-token"
		private const val KEYSTORE_PASSWORD = "fictitious-keystore"

		// GreenMail reads its TLS keystore once per JVM, before the first TLS server starts: a certificate for
		// localhost, so the client's host name check passes (cockpit.mail.ssl-trust only skips the CA check).
		init {
			val keystore = Path.of("build", "greenmail", "localhost.p12").toAbsolutePath()
			Files.createDirectories(keystore.parent)
			Files.deleteIfExists(keystore)
			val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
			val process =
				ProcessBuilder(
					keytool,
					"-genkeypair",
					"-alias",
					"greenmail",
					"-keyalg",
					"RSA",
					"-keysize",
					"2048",
					"-validity",
					"2",
					"-dname",
					"CN=localhost",
					"-ext",
					"san=dns:localhost",
					"-storetype",
					"PKCS12",
					"-keystore",
					keystore.toString(),
					"-storepass",
					KEYSTORE_PASSWORD,
					"-keypass",
					KEYSTORE_PASSWORD,
				).redirectErrorStream(true).start()
			val output = process.inputStream.bufferedReader().readText()
			check(process.waitFor() == 0) { "keytool failed: $output" }
			System.setProperty("greenmail.tls.keystore.file", keystore.toString())
			System.setProperty("greenmail.tls.keystore.password", KEYSTORE_PASSWORD)
			System.setProperty("greenmail.tls.key.password", KEYSTORE_PASSWORD)
		}

		@JvmField
		@RegisterExtension
		val greenMail: GreenMailExtension =
			GreenMailExtension(
				arrayOf(ServerSetupTest.SMTP, ServerSetupTest.IMAP, ServerSetupTest.SMTPS, ServerSetupTest.IMAPS)
					.map { it.dynamicPort() }
					.toTypedArray(),
			)
	}
}
