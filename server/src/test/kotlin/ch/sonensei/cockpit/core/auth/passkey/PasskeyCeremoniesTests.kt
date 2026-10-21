package ch.sonensei.cockpit.core.auth.passkey

import ch.sonensei.cockpit.core.auth.passkey.domain.Ceremony
import ch.sonensei.cockpit.core.auth.passkey.domain.PasskeyCeremonies
import ch.sonensei.cockpit.core.auth.passkey.domain.RelyingParty
import ch.sonensei.cockpit.core.persistence.MutableClock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class PasskeyCeremoniesTests {
	private val clock = MutableClock(Instant.parse("2026-10-20T08:00:00Z"))
	private val ceremonies = PasskeyCeremonies(clock)
	private val rp = RelyingParty("localhost", "My Cockpit", setOf("http://localhost"))

	private fun ceremony() = Ceremony.Authentication(ceremonies.newChallenge(), rp, ceremonies.expiry())

	@Test
	fun `a ceremony can be finished once`() {
		val ceremony = ceremony()
		val id = ceremonies.start(ceremony)
		ceremonies.finish(id) shouldBeSameInstanceAs ceremony
		ceremonies.finish(id) shouldBe null
		ceremonies.finish("unknown") shouldBe null
	}

	@Test
	fun `a ceremony expires after its lifetime`() {
		val id = ceremonies.start(ceremony())
		clock.advance(PasskeyCeremonies.LIFETIME.minusMillis(1))
		val other = ceremonies.start(ceremony())
		clock.advance(Duration.ofMillis(1))
		ceremonies.finish(id) shouldBe null
		ceremonies.finish(other) shouldNotBe null
	}

	@Test
	fun `challenges and ids are random`() {
		ceremonies.newChallenge().size shouldBe 32
		ceremonies.newChallenge().contentEquals(ceremonies.newChallenge()) shouldBe false
		ceremonies.start(ceremony()) shouldNotBe ceremonies.start(ceremony())
	}

	@Test
	fun `at most MAX_PENDING ceremonies are kept, the oldest are dropped first`() {
		val first = ceremonies.start(ceremony())
		val second = ceremonies.start(ceremony())
		repeat(PasskeyCeremonies.MAX_PENDING - 1) { ceremonies.start(ceremony()) }
		ceremonies.finish(first) shouldBe null
		ceremonies.finish(second) shouldNotBe null
	}

	@Test
	fun `authentication churn never evicts a pending registration`() {
		val registration =
			Ceremony.Registration(UUID.randomUUID(), ceremonies.newChallenge(), rp, ceremonies.expiry())
		val registrationId = ceremonies.start(registration)
		val firstAuthentication = ceremonies.start(ceremony())
		repeat(PasskeyCeremonies.MAX_PENDING * 2) { ceremonies.start(ceremony()) }
		ceremonies.finish(firstAuthentication) shouldBe null
		ceremonies.finish(registrationId) shouldBeSameInstanceAs registration
	}

	@Test
	fun `a registration ceremony expires after its lifetime and can be finished once`() {
		fun registration() = Ceremony.Registration(UUID.randomUUID(), ceremonies.newChallenge(), rp, ceremonies.expiry())
		val expiring = ceremonies.start(registration())
		clock.advance(PasskeyCeremonies.LIFETIME.minusMillis(1))
		val fresh = registration()
		val freshId = ceremonies.start(fresh)
		clock.advance(Duration.ofMillis(1))
		ceremonies.finish(expiring) shouldBe null
		ceremonies.finish(freshId) shouldBeSameInstanceAs fresh
		ceremonies.finish(freshId) shouldBe null
	}

	@Test
	fun `registrations are bounded separately, the oldest are dropped first`() {
		fun registration() = Ceremony.Registration(UUID.randomUUID(), ceremonies.newChallenge(), rp, ceremonies.expiry())
		val authentication = ceremonies.start(ceremony())
		val first = ceremonies.start(registration())
		val second = ceremonies.start(registration())
		repeat(PasskeyCeremonies.MAX_PENDING - 1) { ceremonies.start(registration()) }
		ceremonies.finish(first) shouldBe null
		ceremonies.finish(second) shouldNotBe null
		ceremonies.finish(authentication) shouldNotBe null
	}
}
