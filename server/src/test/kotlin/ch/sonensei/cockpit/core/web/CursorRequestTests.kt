package ch.sonensei.cockpit.core.web

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CursorRequestTests {
	@Test
	fun `defaults to the first page with the default limit`() {
		CursorRequest.of(null, null) shouldBe CursorRequest(null, CursorRequest.DEFAULT_LIMIT)
		CursorRequest.of("", null).position shouldBe null
	}

	@Test
	fun `accepts limits within bounds`() {
		CursorRequest.of(null, 1).limit shouldBe 1
		CursorRequest.of(null, CursorRequest.MAX_LIMIT).limit shouldBe CursorRequest.MAX_LIMIT
	}

	@Test
	fun `rejects limits outside bounds with a field error`() {
		for (limit in listOf(0, -1, CursorRequest.MAX_LIMIT + 1)) {
			val ex = shouldThrow<InvalidRequestException> { CursorRequest.of(null, limit) }
			ex.fieldErrors.single().field shouldBe "limit"
		}
	}

	@Test
	fun `cursor round-trips through encoding`() {
		val position = "2026-10-20T08:00:00Z|0192a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"
		CursorRequest.of(CursorRequest.encodeCursor(position), 10).position shouldBe position
	}

	@Test
	fun `rejects malformed cursors with a field error`() {
		for (cursor in listOf("%%%", "not base64!", "eA=x", "_w")) {
			// "_w" is valid base64url but decodes to 0xFF, which is not UTF-8.
			val ex = shouldThrow<InvalidRequestException> { CursorRequest.of(cursor, null) }
			ex.fieldErrors.single().field shouldBe "cursor"
		}
	}

	@Test
	fun `page takes limit items and sets nextCursor only when more exist`() {
		val request = CursorRequest(null, 2)
		val more = request.page(listOf(1, 2, 3)) { it.toString() }
		more.items shouldBe listOf(1, 2)
		CursorRequest.of(more.nextCursor, 2).position shouldBe "2"

		val last = request.page(listOf(1, 2)) { it.toString() }
		last.items shouldBe listOf(1, 2)
		last.nextCursor shouldBe null

		request.page(emptyList<Int>()) { it.toString() } shouldBe CursorPage(emptyList(), null)
	}
}
