package ch.sonensei.cockpit.core.web

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ETagsTests {
	@Test
	fun `ETag is the quoted version`() {
		ETags.of(42) shouldBe "\"42\""
		ETags.ok("body", 3).headers.eTag shouldBe "\"3\""
	}

	@Test
	fun `missing or blank If-Match is 428`() {
		shouldThrow<PreconditionRequiredException> { ETags.checkIfMatch(null, 1) }
		shouldThrow<PreconditionRequiredException> { ETags.checkIfMatch("  ", 1) }
	}

	@Test
	fun `stale, unquoted or weak If-Match is 412`() {
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("\"1\"", 2) }
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("2", 2) }
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("W/\"2\"", 2) }
	}

	@Test
	fun `current version, a list containing it, or star matches`() {
		shouldNotThrowAny {
			ETags.checkIfMatch("\"2\"", 2)
			ETags.checkIfMatch("\"1\", \"2\"", 2)
			ETags.checkIfMatch("*", 2)
			ETags.checkIfMatch("W/\"2\", \"2\"", 2)
		}
	}

	@Test
	fun `a list of only stale or weak tags is 412`() {
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("\"1\", W/\"2\", \"3\"", 2) }
		// A tag that merely contains the version is not a match.
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("\"12\"", 2) }
	}
}
