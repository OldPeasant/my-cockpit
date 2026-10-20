package ch.sonensei.cockpit.core.events

import ch.sonensei.cockpit.core.auth.domain.AuthSession
import ch.sonensei.cockpit.core.auth.domain.LoginService
import ch.sonensei.cockpit.core.auth.domain.UserAccount
import ch.sonensei.cockpit.core.auth.infra.AuthSessionRepository
import ch.sonensei.cockpit.core.auth.infra.SessionCookies
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.events.infra.SseEventHub
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.transaction.support.TransactionTemplate
import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The stream over real HTTP (embedded server, session cookie of a stored login): a committed event arrives, a
 * rolled-back one does not, and a client that disconnects is dropped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventsHttpStreamTests(
	@Autowired private val accounts: UserAccountRepository,
	@Autowired private val sessions: AuthSessionRepository,
	@Autowired private val publisher: ChangeEventPublisher,
	@Autowired private val hub: SseEventHub,
	@Autowired private val transactions: TransactionTemplate,
	@Autowired private val clock: Clock,
	@LocalServerPort private val port: Int,
) : PostgresTestBase() {
	private val client = HttpClient.newHttpClient()
	private val reader = Executors.newSingleThreadExecutor()
	private lateinit var account: UserAccount

	@BeforeEach
	fun login() {
		val now = clock.instant()
		account = accounts.save(UserAccount("sse.tester", "fictitious-password-hash", EncryptedSecret("fictitious-totp-secret")))
		sessions.save(AuthSession(account, LoginService.hash(TOKEN), rememberDevice = false, now, now.plus(Duration.ofHours(1))))
	}

	@AfterEach
	fun cleanUp() {
		reader.shutdownNow()
		sessions.deleteAll(sessions.findAll().filter { it.tokenHash == LoginService.hash(TOKEN) })
		accounts.deleteById(account.id)
	}

	@Test
	fun `without a session cookie the stream answers 401`() {
		val response = client.send(request().build(), HttpResponse.BodyHandlers.discarding())
		response.statusCode() shouldBe 401
	}

	@Test
	fun `a committed event reaches the connected client, a rolled-back one does not, a disconnect is cleaned up`() {
		val clientsBefore = hub.clientCount
		val response = client.send(request().header("Cookie", "${SessionCookies.NAME}=$TOKEN").build(), HttpResponse.BodyHandlers.ofInputStream())
		response.statusCode() shouldBe 200
		response.headers().firstValue("Content-Type").orElseThrow() shouldStartWith "text/event-stream"
		val lines = response.body().bufferedReader()
		lines.awaitLine { it == ":connected" }

		val rolledBack = UuidV7.generate()
		val committed = UuidV7.generate()
		transactions.executeWithoutResult { status ->
			publisher.publish("TASK", rolledBack, ChangeOp.UPDATED)
			status.setRollbackOnly()
		}
		transactions.executeWithoutResult { publisher.publish("TASK", committed, ChangeOp.CREATED) }

		lines.awaitLine { it == "event:${SseEventHub.EVENT_NAME}" }
		// The first event after the rolled-back one is the committed one: events are sent in order.
		val data = lines.awaitLine { it.startsWith("data:") }
		data shouldContain committed.toString()
		data shouldContain "\"op\":\"CREATED\""

		response.body().close()
		// The next write (event or heartbeat) to the closed connection fails and drops the client.
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
		while (hub.clientCount > clientsBefore && System.nanoTime() < deadline) {
			publisher.publish("TASK", UuidV7.generate(), ChangeOp.UPDATED)
			Thread.sleep(50)
		}
		hub.clientCount shouldBe clientsBefore
	}

	private fun request() =
		HttpRequest
			.newBuilder(URI.create("http://localhost:$port/api/v1/events"))
			.header("Accept", "text/event-stream")
			.GET()

	private fun BufferedReader.awaitLine(condition: (String) -> Boolean): String =
		reader
			.submit<String> {
				generateSequence { readLine() }.first(condition)
			}.get(5, TimeUnit.SECONDS)

	private companion object {
		const val TOKEN = "fictitious-sse-session-token"
	}
}
