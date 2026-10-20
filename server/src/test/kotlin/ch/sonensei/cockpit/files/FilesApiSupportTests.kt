package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.files.api.ContentDispositions
import ch.sonensei.cockpit.files.api.FilesPatch
import ch.sonensei.cockpit.files.api.parseFilesPatch
import ch.sonensei.cockpit.files.domain.MoveTarget
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.http.ContentDisposition
import java.util.UUID

/** Plain unit tests of the files API helpers: `Content-Disposition` values (RFC 6266/8187) and PATCH body parsing. */
class FilesApiSupportTests {
	// --- ContentDispositions

	@Test
	fun `star and apostrophe are percent-encoded in filename star, since they are no RFC 8187 attr-chars`() {
		ContentDispositions.attachment("Q*4 'draft'.txt") shouldBe
			"attachment; filename=\"Q*4 'draft'.txt\"; filename*=UTF-8''Q%2A4%20%27draft%27.txt"
	}

	@Test
	fun `plus, percent and semicolon survive the encoding and are not confused with spaces`() {
		ContentDispositions.attachment("a+b 100%;x.txt") shouldBe
			"attachment; filename=\"a+b 100%;x.txt\"; filename*=UTF-8''a%2Bb%20100%25%3Bx.txt"
	}

	@Test
	fun `backslash, quote, control and non-ASCII characters become underscores in the fallback only`() {
		ContentDispositions.attachment("C:\\tmp\t\"Ö\".txt") shouldBe
			"attachment; filename=\"C:_tmp____.txt\"; filename*=UTF-8''C%3A%5Ctmp%09%22%C3%96%22.txt"
	}

	@Test
	fun `characters outside the BMP are encoded as UTF-8 and replaced in the fallback`() {
		// One emoji is a surrogate pair (two chars), so the fallback has two underscores.
		ContentDispositions.attachment("\uD83D\uDCC4.pdf") shouldBe "attachment; filename=\"__.pdf\"; filename*=UTF-8''%F0%9F%93%84.pdf"
	}

	@Test
	fun `Spring's own parser reads the exact name back from the header`() {
		for (name in listOf("Q*4 'draft'.txt", "a+b 100%;x.txt", "Ärger \"neu\".txt", "日本語 ファイル.pdf", "\uD83D\uDCC4.pdf")) {
			ContentDisposition.parse(ContentDispositions.attachment(name)).filename shouldBe name
		}
	}

	// --- parseFilesPatch

	private val folderId = UUID.fromString("01900000-0000-7000-8000-0000000000aa")

	@Test
	fun `an empty body changes nothing`() {
		parseFilesPatch(emptyMap(), "parentId") shouldBe FilesPatch(null, null)
	}

	@Test
	fun `a name and a folder id are parsed, and a null folder id is the drive root`() {
		parseFilesPatch(mapOf("name" to "Plans", "parentId" to folderId.toString()), "parentId") shouldBe
			FilesPatch("Plans", MoveTarget(folderId))
		parseFilesPatch(mapOf("parentId" to null), "parentId") shouldBe FilesPatch(null, MoveTarget(null))
		parseFilesPatch(mapOf("folderId" to null), "folderId") shouldBe FilesPatch(null, MoveTarget(null))
	}

	@Test
	fun `the other entity's parent field is unknown`() {
		val error = shouldThrow<InvalidRequestException> { parseFilesPatch(mapOf("parentId" to null), "folderId") }
		error.fieldErrors.map { it.field } shouldContainExactly listOf("parentId")
	}

	@Test
	fun `a null or non-string name is rejected`() {
		shouldThrow<InvalidRequestException> { parseFilesPatch(mapOf("name" to null), "parentId") }
			.fieldErrors
			.map { it.field } shouldContainExactly listOf("name")
		shouldThrow<InvalidRequestException> { parseFilesPatch(mapOf("name" to 42), "parentId") }
			.fieldErrors
			.map { it.field } shouldContainExactly listOf("name")
	}

	@Test
	fun `a folder id that is not a UUID string is rejected`() {
		for (value in listOf("not-a-uuid", "", 7, true, mapOf("id" to folderId.toString()))) {
			shouldThrow<InvalidRequestException> { parseFilesPatch(mapOf("parentId" to value), "parentId") }
				.fieldErrors
				.map { it.field } shouldContainExactly listOf("parentId")
		}
	}

	@Test
	fun `all problems are reported together, unknown fields sorted`() {
		val error =
			shouldThrow<InvalidRequestException> {
				parseFilesPatch(mapOf("size" to 3, "mime" to "x", "name" to 1, "parentId" to "x"), "parentId")
			}
		error.fieldErrors.map { it.field } shouldContainExactly listOf("mime", "size", "name", "parentId")
	}
}
