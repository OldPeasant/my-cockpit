package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.push.domain.PushService
import ch.sonensei.cockpit.core.push.infra.PushSubscriptionRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.web.ErrorResponseException
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [PushService.subscribe] when the upsert fails: only a foreign key violation (the login session ended during the
 * request) becomes 401; any other integrity error is not disguised as a logout and stays a server error.
 */
class PushServiceSubscribeFailureTests {
	private val subscriptions = mockk<PushSubscriptionRepository>()
	private val service =
		PushService(
			subscriptions,
			mockk(relaxed = true),
			mockk(relaxed = true),
			mockk(relaxed = true),
			mockk(relaxed = true),
			Clock.fixed(Instant.parse("2026-10-26T09:00:00Z"), ZoneOffset.UTC),
		)
	private val browser = PushTestBrowser()
	private val sessionId = UUID.fromString("01900000-0000-7000-8000-000000000001")
	private val endpoint = "https://push.example.test/send/fictitious-1"

	private fun failingWith(sqlState: String): DataIntegrityViolationException {
		// Like Hibernate: the driver's SQLException sits a few causes down.
		val driver = SQLException("constraint violated (fictitious)", sqlState)
		val failure = DataIntegrityViolationException("could not execute statement", RuntimeException("wrapped", driver))
		every { subscriptions.upsert(any(), any(), any(), any(), any(), any()) } throws failure
		return failure
	}

	private fun subscribe() = service.subscribe(sessionId, endpoint, browser.p256dh, browser.auth)

	@Test
	fun `a foreign key violation, deep in the causes, answers 401 and looks nothing up`() {
		failingWith("23503")

		val error = shouldThrow<ErrorResponseException> { subscribe() }

		error.statusCode shouldBe HttpStatus.UNAUTHORIZED
		error.body.status shouldBe 401
		error.body.detail shouldBe "The login session has ended"
		verify(exactly = 0) { subscriptions.findByEndpoint(any()) }
	}

	@Test
	fun `any other integrity violation is rethrown unchanged, not turned into 401`() {
		val failure = failingWith("23505")

		val error = shouldThrow<DataIntegrityViolationException> { subscribe() }

		error shouldBeSameInstanceAs failure
	}

	@Test
	fun `an integrity violation without any SQL state is rethrown`() {
		val failure = DataIntegrityViolationException("no driver cause")
		every { subscriptions.upsert(any(), any(), any(), any(), any(), any()) } throws failure

		val error = shouldThrow<DataIntegrityViolationException> { subscribe() }

		error shouldBeSameInstanceAs failure
	}
}
