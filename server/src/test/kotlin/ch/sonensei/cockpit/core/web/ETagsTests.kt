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

	@Test
	fun `a proxy content-coding suffix on the current version matches`() {
		shouldNotThrowAny {
			ETags.checkIfMatch("\"2-gzip\"", 2)
			ETags.checkIfMatch("\"2-zstd\"", 2)
			ETags.checkIfMatch("\"2-br\"", 2)
			ETags.checkIfMatch("\"1\", \"2-gzip\"", 2)
		}
	}

	@Test
	fun `a suffixed stale, weak, unknown or malformed tag is 412`() {
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("\"1-gzip\"", 2) }
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("\"12-gzip\"", 2) }
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("W/\"2-gzip\"", 2) }
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("\"2-deflate\"", 2) }
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("\"2-gzip-gzip\"", 2) }
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("\"2\"-gzip", 2) }
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("2-gzip", 2) }
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("\"1-gzip\", \"3-zstd\"", 2) }
	}

	@Test
	fun `a suffixed tag matches at version zero, a large version, and among weak or stale tags`() {
		shouldNotThrowAny {
			ETags.checkIfMatch("\"0-gzip\"", 0)
			ETags.checkIfMatch("\"${Long.MAX_VALUE}-br\"", Long.MAX_VALUE)
			ETags.checkIfMatch("W/\"2\", \"1-gzip\", \"2-zstd\"", 2)
			ETags.checkIfMatch("\"2-br\",\"3\"", 2)
		}
	}

	@Test
	fun `a bare suffix, a suffix without dash or a suffix outside the quotes is 412`() {
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("\"-gzip\"", 2) }
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("\"2gzip\"", 2) }
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("\"2-\"", 2) }
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("\"gzip-2\"", 2) }
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("\"2-br\"x", 2) }
		// A suffixed tag of a different version never matches, also when it ends with the version's digits.
		shouldThrow<PreconditionFailedException> { ETags.checkIfMatch("\"22-zstd\"", 2) }
	}

	@Test
	fun `a blank If-Match is still 428 and star still matches alongside suffixed tags`() {
		shouldThrow<PreconditionRequiredException> { ETags.checkIfMatch("", 2) }
		shouldNotThrowAny { ETags.checkIfMatch("\"1-gzip\", *", 2) }
	}
}
