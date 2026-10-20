package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post

/**
 * A non-default `cockpit.files.max-size` (task files-api, criterion 1) is honoured by the multipart upload (413
 * Problem Details), the resumable upload (400 on the declared size), the usage endpoint and Spring's multipart limit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@TestPropertySource(properties = ["cockpit.files.max-size=1KB"])
class FilesMaxSizeWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val environment: Environment,
) : PostgresTestBase() {
	@BeforeEach
	@AfterEach
	fun reset() {
		jdbc.execute("delete from search_document where item_type in ('FILE', 'FOLDER')")
		jdbc.execute("delete from upload_session")
		jdbc.execute("delete from file")
	}

	private fun upload(size: Int) =
		mockMvc.multipart(FILES) {
			with(csrf())
			file(MockMultipartFile("file", "data.bin", "application/octet-stream", ByteArray(size)))
		}

	private fun createUpload(size: Int) =
		mockMvc.post(UPLOADS) {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"data.bin","size":$size}"""
		}

	@Test
	fun `a multipart upload above the configured maximum is a 413 Problem Details and stores nothing`() {
		upload(LIMIT + 1).andExpect {
			status { isEqualTo(413) }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.status") { value(413) }
			jsonPath("$.detail") { value("The file exceeds the maximum size of $LIMIT bytes") }
		}
		jdbc.queryForObject("select count(*) from file", Long::class.java) shouldBe 0
		upload(LIMIT).andExpect { status { isCreated() } }
	}

	@Test
	fun `a resumable upload declaring more than the configured maximum is a 400 on size`() {
		createUpload(LIMIT + 1).andExpect {
			status { isBadRequest() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.errors[0].field") { value("size") }
		}
		createUpload(LIMIT).andExpect { status { isCreated() } }
	}

	@Test
	fun `the usage endpoint and Spring's multipart limit follow the configured maximum`() {
		mockMvc.get("$DRIVE/usage").andExpect { jsonPath("$.maxFileSize") { value(LIMIT) } }
		environment.getProperty("spring.servlet.multipart.max-file-size") shouldBe "1KB"
	}

	private companion object {
		const val LIMIT = 1024
		const val FILES = "/api/v1/files"
		const val DRIVE = "/api/v1/drive"
		const val UPLOADS = "/api/v1/uploads"
	}
}
