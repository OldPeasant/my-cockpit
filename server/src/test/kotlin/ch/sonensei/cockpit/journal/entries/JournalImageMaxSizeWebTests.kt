package ch.sonensei.cockpit.journal.entries

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.multipart

/** A journal image above the configured `cockpit.files.max-size` is a 413 Problem Details and stores nothing. */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@TestPropertySource(properties = ["cockpit.files.max-size=1KB"])
class JournalImageMaxSizeWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from search_document where item_type in ('FILE', 'FOLDER')")
		jdbc.execute("delete from file")
	}

	private fun upload(size: Int) =
		mockMvc.multipart("/api/v1/journal/images") {
			file(MockMultipartFile("file", "big.png", "image/png", ByteArray(size)))
			with(csrf())
		}

	@Test
	fun `an image above the maximum size is a 413 Problem Details, one at the maximum is stored`() {
		upload(LIMIT + 1).andExpect {
			status { isEqualTo(413) }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.status") { value(413) }
			jsonPath("$.detail") { value("The image exceeds the maximum size of $LIMIT bytes") }
		}
		jdbc.queryForObject("select count(*) from file", Long::class.java) shouldBe 0

		upload(LIMIT).andExpect {
			status { isCreated() }
			jsonPath("$.size") { value(LIMIT) }
		}
		jdbc.queryForObject("select count(*) from file", Long::class.java) shouldBe 1
	}

	private companion object {
		const val LIMIT = 1024
	}
}
