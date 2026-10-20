package ch.sonensei.cockpit.core.web

import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps the committed contract `api/openapi.yaml` in sync with the server (see `api/README.md`).
 * `UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` rewrites the file; without the
 * flag the test fails when the generated contract differs from the committed one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser
class OpenApiContractTest(
	@Autowired private val mockMvc: MockMvc,
) : PostgresTestBase() {
	// Gradle runs tests with the project directory (server/) as working directory.
	private val contractFile: Path = Path.of("..", "api", "openapi.yaml").toAbsolutePath().normalize()

	@Test
	fun `api openapi yaml matches the generated contract`() {
		val generated =
			mockMvc
				.get("/v3/api-docs.yaml")
				.andExpect { status { isOk() } }
				.andReturn()
				.response
				.getContentAsString(Charsets.UTF_8)
				.normalized()

		generated shouldContain "openapi:"
		generated shouldContain "title: My Cockpit API"
		// The shared error schemas are part of the contract that both clients generate from.
		generated shouldContain "    ProblemDetail:\n"
		generated shouldContain "    FieldError:\n"
		// Test-only controllers must never leak into the published contract.
		generated shouldNotContain ConventionsTestApi.BASE

		if (System.getenv("UPDATE_OPENAPI") == "1") {
			Files.createDirectories(contractFile.parent)
			Files.writeString(contractFile, generated)
			return
		}
		val committed = if (Files.exists(contractFile)) Files.readString(contractFile).normalized() else ""
		withClue(
			"$contractFile is out of date. Regenerate it: cd server && UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'",
		) {
			committed shouldBe generated
		}
	}

	private fun String.normalized(): String = replace("\r\n", "\n").trimEnd() + "\n"
}
