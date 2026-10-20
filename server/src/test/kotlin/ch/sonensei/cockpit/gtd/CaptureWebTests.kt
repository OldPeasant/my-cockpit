package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.files.domain.FilesService
import ch.sonensei.cockpit.gtd.domain.Context
import ch.sonensei.cockpit.gtd.domain.Project
import ch.sonensei.cockpit.gtd.domain.ProjectStatus
import ch.sonensei.cockpit.gtd.infra.ContextRepository
import ch.sonensei.cockpit.gtd.infra.ProjectRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class CaptureWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val projects: ProjectRepository,
	@Autowired private val contexts: ContextRepository,
	@Autowired private val files: FilesService,
	@Autowired private val links: LinkService,
	@Autowired private val linkRows: LinkRepository,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	@AfterEach
	fun reset() {
		linkRows.deleteAll()
		jdbc.execute("update project set next_task_id = null")
		jdbc.execute("delete from checklist_item")
		jdbc.execute("delete from task_context")
		jdbc.execute("delete from task")
		jdbc.execute("delete from project")
		jdbc.execute("delete from context")
		jdbc.execute("delete from search_document")
		jdbc.execute("delete from file")
		clock.set(MutableClockTestConfiguration.START) // 2026-03-01 in Europe/Zurich
	}

	private fun capture(body: String) =
		mockMvc.post(CAPTURE) {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = body
		}

	private fun preview(text: String): JsonNode =
		mockMvc
			.post("$CAPTURE/preview") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = jsonMapper.writeValueAsString(mapOf("text" to text))
			}.andExpect { status { isOk() } }
			.andReturn()
			.response
			.contentAsString
			.let(jsonMapper::readTree)

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun ResultActionsDsl.isProblem(
		status: Int,
		vararg fields: String,
	) = andExpect {
		status { isEqualTo(status) }
		content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		fields.forEach { field -> jsonPath("$.errors[?(@.field == '$field')]") { isNotEmpty() } }
	}

	private fun taskCount(): Int = jdbc.queryForObject("select count(*) from task", Int::class.java)!!

	private fun storedFile(name: String) = files.storeFile(null, name, "text/plain", "fictitious content".byteInputStream())

	@Test
	fun `capture creates an inbox task with ETag and Location`() {
		val result =
			capture("""{"text":"Call the plumber","notes":"About the leak"}""")
				.andExpect {
					status { isCreated() }
					header { string(HttpHeaders.ETAG, "\"0\"") }
					jsonPath("$.list") { value("INBOX") }
					jsonPath("$.title") { value("Call the plumber") }
					jsonPath("$.notesMd") { value("About the leak") }
				}.andReturn()
		val id = jsonMapper.readTree(result.response.contentAsString).get("id").asString()
		result.response.getHeader(HttpHeaders.LOCATION) shouldBe "http://localhost/api/v1/tasks/$id"
	}

	@Test
	fun `a repeated client id returns 200 with the existing task and creates no duplicate`() {
		val id = UuidV7.generate()
		val first = capture("""{"id":"$id","text":"Buy milk"}""").andExpect { status { isCreated() } }.json()
		first.get("id").asString() shouldBe id.toString()

		capture("""{"id":"$id","text":"Buy milk, edited offline"}""")
			.andExpect {
				status { isOk() }
				header { string(HttpHeaders.ETAG, "\"0\"") }
				jsonPath("$.id") { value(id.toString()) }
				jsonPath("$.title") { value("Buy milk") }
			}
		taskCount() shouldBe 1
	}

	@Test
	fun `the syntax sets project, contexts, due date and waiting`() {
		val project = projects.save(Project("Home Renovation"))
		val home = contexts.save(Context("@home"))
		val task =
			capture("""{"text":"Get a quote #home-renovation @HOME !tomorrow ^Anna"}""")
				.andExpect { status { isCreated() } }
				.json()
		task.get("title").asString() shouldBe "Get a quote"
		task.get("projectId").asString() shouldBe project.id.toString()
		task.get("contextIds").values().map { it.asString() } shouldBe listOf(home.id.toString())
		task.get("dueDate").asString() shouldBe "2026-03-02"
		task.get("list").asString() shouldBe "WAITING"
		task.get("waitingForText").asString() shouldBe "Anna"
		task.get("waitingSince").asString() shouldBe "2026-03-01"
	}

	@Test
	fun `parse false keeps the text as it is`() {
		projects.save(Project("Garden"))
		capture("""{"text":"Plant #garden !today","parse":false}""")
			.andExpect {
				status { isCreated() }
				jsonPath("$.title") { value("Plant #garden !today") }
				jsonPath("$.projectId") { doesNotExist() }
				jsonPath("$.dueDate") { doesNotExist() }
			}
	}

	@Test
	fun `further lines go in front of the notes`() {
		capture("""{"text":"Read this\nhttps://example.org/article","notes":"Shared from the browser"}""")
			.andExpect {
				status { isCreated() }
				jsonPath("$.title") { value("Read this") }
				jsonPath("$.notesMd") { value("https://example.org/article\n\nShared from the browser") }
			}
	}

	@Test
	fun `file ids are linked to the task, once each`() {
		val scan = storedFile("scan.txt")
		val photo = storedFile("photo.txt")
		val task =
			capture("""{"text":"File the receipt","fileIds":["${scan.id}","${photo.id}","${scan.id}"]}""")
				.andExpect { status { isCreated() } }
				.json()
		val ref = ItemRef(ItemType.TASK, UUID.fromString(task.get("id").asString()))
		links.linksOf(ref).map { it.target.ref } shouldContainExactlyInAnyOrder
			listOf(ItemRef(ItemType.FILE, scan.id), ItemRef(ItemType.FILE, photo.id))
	}

	@Test
	fun `a repeated capture links no files again`() {
		val scan = storedFile("scan.txt")
		val id = UuidV7.generate()
		val body = """{"id":"$id","text":"File the receipt","fileIds":["${scan.id}"]}"""
		capture(body).andExpect { status { isCreated() } }
		capture(body).andExpect { status { isOk() } }
		links.linksOf(ItemRef(ItemType.TASK, id)).size shouldBe 1
	}

	@Test
	fun `an unknown or deleted file is rejected and nothing is created`() {
		val deleted = storedFile("old.txt")
		files.deleteFile(deleted.id)
		capture("""{"text":"x","fileIds":["${UuidV7.generate()}"]}""").isProblem(400, "fileIds")
		capture("""{"text":"x","fileIds":["${deleted.id}"]}""").isProblem(400, "fileIds")
		taskCount() shouldBe 0
	}

	@Test
	fun `invalid captures are rejected`() {
		capture("""{}""").isProblem(400, "text")
		capture("""{"text":"   "}""").isProblem(400, "text")
		capture("""{"text":"!today ^waiting"}""").isProblem(400, "text")
		capture("""{"id":"${UUID.randomUUID()}","text":"x"}""").isProblem(400, "id")
		capture("""{"text":"${"x".repeat(100_001)}"}""").isProblem(400, "text")
		capture("""{"text":"x","notes":"${"n".repeat(100_001)}"}""").isProblem(400, "notes")
		taskCount() shouldBe 0
	}

	@Test
	fun `a capture with the id of a trashed task is a conflict`() {
		val id = UuidV7.generate()
		capture("""{"id":"$id","text":"x"}""").andExpect { status { isCreated() } }
		jdbc.update("update task set deleted_at = now() where id = ?", id)
		capture("""{"id":"$id","text":"x"}""").isProblem(409)
	}

	@Test
	fun `preview returns the parse result without storing anything`() {
		val project = projects.save(Project("Garden"))
		projects.save(Project("garden", ProjectStatus.COMPLETED))
		val home = contexts.save(Context("@home"))
		val result = preview("#Garden Plant tulips @home @office !2026-04-01 ^waiting\nbulbs from the shop")
		result.get("title").asString() shouldBe "Plant tulips @office"
		result.get("extraNotes").asString() shouldBe "bulbs from the shop"
		result.get("list").asString() shouldBe "WAITING"
		result.get("project").get("id").asString() shouldBe project.id.toString()
		result.get("project").get("title").asString() shouldBe "Garden"
		result.get("contexts").values().map { it.get("id").asString() } shouldBe listOf(home.id.toString())
		result.get("contexts").values().map { it.get("name").asString() } shouldBe listOf("@home")
		result.get("dueDate").asString() shouldBe "2026-04-01"
		result.get("waitingFor").isNull shouldBe true
		taskCount() shouldBe 0
	}

	@Test
	fun `completed and deleted projects are not matched, active ones are preferred`() {
		projects.save(Project("Garden", ProjectStatus.DROPPED))
		val deleted = projects.save(Project("Garden"))
		jdbc.update("update project set deleted_at = now() where id = ?", deleted.id)
		preview("Weeds #garden").get("project").isNull shouldBe true

		projects.save(Project("Garden", ProjectStatus.ON_HOLD))
		val active = projects.save(Project("garden"))
		preview("Weeds #garden").get("project").get("id").asString() shouldBe active.id.toString()
	}

	@Test
	fun `preview uses today in the user's time zone`() {
		clock.set(java.time.Instant.parse("2026-03-01T23:30:00Z")) // already 2 March in Zurich
		preview("Pay !today").get("dueDate").asString() shouldBe "2026-03-02"
	}

	@Test
	fun `preview needs a text`() {
		mockMvc
			.post("$CAPTURE/preview") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = "{}"
			}.isProblem(400, "text")
	}

	@Test
	@WithAnonymousUser
	fun `capture needs a login`() {
		capture("""{"text":"x"}""").andExpect { status { isUnauthorized() } }
	}

	private companion object {
		const val CAPTURE = "/api/v1/capture"
	}
}
