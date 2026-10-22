package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.mail.imap.MailConnectionError
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.imap.MailCredentials
import ch.sonensei.cockpit.mail.imap.MailImapProperties
import ch.sonensei.cockpit.mail.imap.MailSessions
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailSecurity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * SMTP sending ([MailSender]) against a scripted in-process SMTP server: what is transmitted (envelope with Bcc, no Bcc
 * header), the login (password or XOAUTH2), and how refusals and failures are reported.
 */
class MailSenderTests {
	private val servers = mutableListOf<FakeSmtpServer>()

	@AfterEach
	fun tearDown() {
		servers.forEach(FakeSmtpServer::close)
	}

	private fun server(
		rejectRecipients: Set<String> = emptySet(),
		dataReply: String = "250 2.0.0 queued",
		authReply: String = "235 2.7.0 accepted",
	) = FakeSmtpServer(rejectRecipients, dataReply, authReply).also(servers::add)

	private fun account(port: Int) =
		MailAccount(
			type = MailAccountType.IMAP_SMTP,
			displayName = "Work",
			emailAddress = "jane.doe@example.test",
			imapHost = "127.0.0.1",
			imapPort = 1,
			imapSecurity = MailSecurity.NONE,
			smtpHost = "127.0.0.1",
			smtpPort = port,
			smtpSecurity = MailSecurity.NONE,
			username = "jane",
		)

	private fun sender(xoauth2: Boolean = false): MailSender {
		val properties =
			MailImapProperties(
				Duration.ofSeconds(5),
				Duration.ofSeconds(5),
				"",
				MailImapProperties.Pool(1, Duration.ofSeconds(5), Duration.ofMinutes(1)),
			)
		val sessions = spyk(MailSessions(properties, mockk()))
		every { sessions.credentials(any()) } returns
			MailCredentials("jane", if (xoauth2) "fictitious-access-token" else "fictitious-password", xoauth2)
		return MailSender(sessions)
	}

	private fun message() =
		DraftMime.build(
			ComposedMessage(
				messageId = "<01900000-0000-7000-8000-000000000001@example.test>",
				from = MailAddress("jane.doe@example.test", "Jane Doe"),
				replyTo = null,
				to = listOf(MailAddress("bob@example.test")),
				cc = listOf(MailAddress("carol@example.test")),
				bcc = listOf(MailAddress("dave@example.test")),
				subject = "Project status",
				html = "<p>All on track.</p>",
				text = "All on track.\n",
				inReplyTo = null,
				references = null,
				attachments = emptyList(),
			),
			Instant.parse("2026-03-05T14:07:00Z"),
			draft = false,
		)

	@Test
	fun `all recipients including Bcc get the message, without a Bcc header`() {
		val smtp = server()

		sender().send(account(smtp.port), message())

		smtp.awaitDone()
		smtp.recipients shouldContainExactly listOf("bob@example.test", "carol@example.test", "dave@example.test")
		smtp.mailFrom shouldBe "jane.doe@example.test"
		val data = smtp.data.joinToString("\n")
		data shouldContain "Subject: Project status"
		data shouldContain "Message-ID: <01900000-0000-7000-8000-000000000001@example.test>"
		data shouldNotContain "Bcc:"
		data shouldNotContain "dave@example.test"
	}

	@Test
	fun `a password account logs in with the password`() {
		val smtp = server()

		sender().send(account(smtp.port), message())

		smtp.awaitDone()
		val auth = smtp.commands.first { it.startsWith("AUTH") }
		auth shouldStartWith "AUTH PLAIN"
		smtp.authPayloads.single() shouldContain "fictitious-password"
	}

	@Test
	fun `an OAuth account logs in with XOAUTH2 and its access token`() {
		val smtp = server()

		sender(xoauth2 = true).send(account(smtp.port), message())

		smtp.awaitDone()
		smtp.commands.first { it.startsWith("AUTH") } shouldStartWith "AUTH XOAUTH2"
		smtp.authPayloads.single() shouldBe "user=jane\u0001auth=Bearer fictitious-access-token\u0001\u0001"
		smtp.authPayloads.single() shouldNotContain "fictitious-password"
	}

	@Test
	fun `refused recipients are reported and nothing is sent`() {
		val smtp = server(rejectRecipients = setOf("carol@example.test"))

		val e = shouldThrow<MailSendRejectedException> { sender().send(account(smtp.port), message()) }

		e.rejected shouldContainExactly listOf("carol@example.test")
		e.message shouldBe "The mail server refused the message"
		smtp.awaitDone()
		smtp.commands shouldNotContain "DATA"
	}

	@Test
	fun `a message refused after DATA is rejected as a whole`() {
		val smtp = server(dataReply = "554 5.7.1 message refused as spam")

		val e = shouldThrow<MailSendRejectedException> { sender().send(account(smtp.port), message()) }

		e.rejected.shouldBeEmpty()
		e.message.orEmpty() shouldNotContain "spam"
	}

	@Test
	fun `a refused login is AUTHENTICATION_FAILED`() {
		val smtp = server(authReply = "535 5.7.8 bad credentials")

		val e = shouldThrow<MailConnectionException> { sender().send(account(smtp.port), message()) }

		e.error shouldBe MailConnectionError.AUTHENTICATION_FAILED
		smtp.commands.filter { it.startsWith("RCPT") }.shouldBeEmpty()
	}

	@Test
	fun `an unreachable server is CONNECTION_FAILED`() {
		val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

		val e = shouldThrow<MailConnectionException> { sender().send(account(port), message()) }

		e.error shouldBe MailConnectionError.CONNECTION_FAILED
	}

	@Test
	fun `an SSL-TLS account talks TLS from the start, never plain SMTP`() {
		// The fake server speaks plain SMTP only, so a TLS client cannot get past its greeting.
		val smtp = server()
		val account = account(smtp.port).apply { smtpSecurity = MailSecurity.SSL_TLS }

		shouldThrow<MailConnectionException> { sender().send(account, message()) }

		smtp.awaitDone()
		smtp.commands.filter { it.uppercase().startsWith("EHLO") || it.uppercase().startsWith("MAIL") }.shouldBeEmpty()
		smtp.recipients.shouldBeEmpty()
	}

	@Test
	fun `the connection is closed after sending`() {
		val smtp = server()

		sender().send(account(smtp.port), message())

		smtp.awaitDone()
		smtp.commands shouldContain "QUIT"
	}

	/**
	 * A minimal SMTP server for one connection: advertises `AUTH PLAIN XOAUTH2`, refuses [rejectRecipients] with 550,
	 * answers the end of DATA with [dataReply] and AUTH with [authReply]. It records the commands, the envelope, the data
	 * lines and the decoded AUTH payloads.
	 */
	class FakeSmtpServer(
		private val rejectRecipients: Set<String>,
		private val dataReply: String,
		private val authReply: String,
	) : AutoCloseable {
		private val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
		val port: Int = socket.localPort
		val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
		val recipients: MutableList<String> = Collections.synchronizedList(mutableListOf())
		val data: MutableList<String> = Collections.synchronizedList(mutableListOf())
		val authPayloads: MutableList<String> = Collections.synchronizedList(mutableListOf())

		@Volatile
		var mailFrom: String? = null

		private val worker =
			thread(isDaemon = true, name = "fake-smtp") {
				runCatching {
					socket.accept().use { client ->
						client.soTimeout = 5000
						val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
						val output = PrintWriter(client.getOutputStream(), true)

						fun reply(line: String) = output.print("$line\r\n").also { output.flush() }
						reply("220 fake.example.test ESMTP")
						while (true) {
							val line = input.readLine() ?: break
							commands += line
							val upper = line.uppercase()
							when {
								upper.startsWith("EHLO") -> {
									reply("250-fake.example.test")
									reply("250-AUTH PLAIN XOAUTH2")
									reply("250 8BITMIME")
								}
								upper == "AUTH PLAIN" || upper == "AUTH XOAUTH2" -> {
									reply("334 ")
									val payload = input.readLine() ?: break
									authPayloads += String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
									reply(authReply)
								}
								upper.startsWith("AUTH ") -> {
									authPayloads += String(Base64.getDecoder().decode(line.substringAfterLast(' ')), Charsets.UTF_8)
									reply(authReply)
								}
								upper.startsWith("MAIL FROM:") -> {
									mailFrom = line.substringAfter('<').substringBefore('>')
									reply("250 2.1.0 ok")
								}
								upper.startsWith("RCPT TO:") -> {
									val address = line.substringAfter('<').substringBefore('>')
									if (address in rejectRecipients) {
										reply("550 5.1.1 <$address> unknown user")
									} else {
										recipients += address
										reply("250 2.1.5 ok")
									}
								}
								upper == "DATA" -> {
									reply("354 go ahead")
									while (true) {
										val dataLine = input.readLine() ?: break
										if (dataLine == ".") break
										data += dataLine
									}
									reply(dataReply)
								}
								upper == "QUIT" -> {
									reply("221 bye")
									break
								}
								else -> reply("250 ok")
							}
						}
					}
				}
			}

		fun awaitDone() {
			worker.join(TimeUnit.SECONDS.toMillis(5))
		}

		override fun close() {
			runCatching { socket.close() }
		}
	}
}
