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
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
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

/**
 * Edge cases of quick capture (gtd-capture-api) beyond [CaptureWebTests]: idempotent repeats with different content,
 * limits, title-line-only parsing through the API, and project preference by status.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MutableClockTestConfiguration::class)
@WithCockpitUser
class CaptureEdgeCaseWebTests(
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

	private fun captureJson(vararg fields: Pair<String, Any?>) = capture(jsonMapper.writeValueAsString(mapOf(*fields)))

	private fun preview(body: String) =
		mockMvc.post("$CAPTURE/preview") {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = body
		}

	private fun ResultActionsDsl.json(): JsonNode = andReturn().response.contentAsString.let(jsonMapper::readTree)

	private fun ResultActionsDsl.isProblem(
		status: Int,
		vararg fields: String,
	) = andExpect {
		status { isEqualTo(status) }
		content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		fields.forEach { field -> jsonPath("$.errors[?(@.field == '$field')]") { isNotEmpty() } }
	}

	private fun count(
		sql: String,
		vararg args: Any,
	): Int = jdbc.queryForObject(sql, Int::class.java, *args)!!

	private fun taskCount(): Int = count("select count(*) from task")

	private fun storedFile(name: String) = files.storeFile(null, name, "text/plain", "fictitious content".byteInputStream())

	@Test
	fun `a repeated id returns the original task whatever the repeat contains`() {
		projects.save(Project("Garden"))
		val id = UuidV7.generate()
		capture("""{"id":"$id","text":"Plant tulips"}""").andExpect { status { isCreated() } }

		capture("""{"id":"$id","text":"Changed #garden !today ^Anna","notes":"other","parse":true}""").andExpect {
			status { isOk() }
			jsonPath("$.title") { value("Plant tulips") }
			jsonPath("$.list") { value("INBOX") }
			jsonPath("$.projectId") { doesNotExist() }
			jsonPath("$.dueDate") { doesNotExist() }
			jsonPath("$.notesMd") { doesNotExist() }
		}
		taskCount() shouldBe 1
	}

	@Test
	fun `a repeat whose text alone would be invalid still returns the existing task`() {
		val id = UuidV7.generate()
		capture("""{"id":"$id","text":"Plant tulips"}""").andExpect { status { isCreated() } }
		// An offline queue may replay a request whose file was deleted meanwhile; the capture already happened.
		capture("""{"id":"$id","text":"   ","fileIds":["${UuidV7.generate()}"]}""").andExpect {
			status { isOk() }
			jsonPath("$.id") { value(id.toString()) }
		}
		taskCount() shouldBe 1
	}

	@Test
	fun `a capture with the id of a task created through the tasks api returns that task`() {
		val id = UuidV7.generate()
		mockMvc
			.post("/api/v1/tasks") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"id":"$id","title":"Created elsewhere","list":"NEXT"}"""
			}.andExpect { status { isCreated() } }

		capture("""{"id":"$id","text":"Created elsewhere"}""").andExpect {
			status { isOk() }
			jsonPath("$.list") { value("NEXT") }
		}
		taskCount() shouldBe 1
	}

	@Test
	fun `captures without an id are never deduplicated`() {
		capture("""{"text":"Buy milk"}""").andExpect { status { isCreated() } }
		capture("""{"text":"Buy milk"}""").andExpect { status { isCreated() } }
		taskCount() shouldBe 2
	}

	@Test
	fun `a captured task is indexed for search under its parsed title`() {
		contexts.save(Context("@home"))
		val id =
			captureJson("text" to "Water plants @home")
				.andExpect { status { isCreated() } }
				.json()
				.get("id")
				.asString()
		jdbc.queryForObject(
			"select title from search_document where item_type = 'TASK' and item_id = ?::uuid",
			String::class.java,
			id,
		) shouldBe "Water plants"
	}

	@Test
	fun `a captured inbox task keeps its due date and is not waiting`() {
		capture("""{"text":"Pay bill !today"}""").andExpect {
			status { isCreated() }
			jsonPath("$.list") { value("INBOX") }
			jsonPath("$.dueDate") { value("2026-03-01") }
			jsonPath("$.waitingForText") { doesNotExist() }
			jsonPath("$.waitingSince") { doesNotExist() }
		}
	}

	@Test
	fun `waiting without a name sets waiting since but no waiting for`() {
		capture("""{"text":"Quote for the roof ^waiting"}""").andExpect {
			status { isCreated() }
			jsonPath("$.list") { value("WAITING") }
			jsonPath("$.waitingForText") { doesNotExist() }
			jsonPath("$.waitingSince") { value("2026-03-01") }
		}
	}

	@Test
	fun `a title line of only tokens applies them and takes the next line, unparsed, as the title`() {
		val garden = projects.save(Project("Garden"))
		contexts.save(Context("@home"))
		captureJson("text" to "#garden !tomorrow\n\nPlant tulips @home\n  bulbs ^Anna", "notes" to "from the shop")
			.andExpect {
				status { isCreated() }
				jsonPath("$.title") { value("Plant tulips @home") }
				jsonPath("$.projectId") { value(garden.id.toString()) }
				jsonPath("$.dueDate") { value("2026-03-02") }
				jsonPath("$.contextIds") { isEmpty() }
				jsonPath("$.list") { value("INBOX") }
				jsonPath("$.notesMd") { value("  bulbs ^Anna\n\nfrom the shop") }
			}
	}

	@Test
	fun `tokens only in the body set nothing and are stored verbatim, windows line endings become newlines`() {
		projects.save(Project("Garden"))
		contexts.save(Context("@home"))
		captureJson("text" to "Shared article\r\n#garden @home\r\n!today ^Anna\r\n").andExpect {
			status { isCreated() }
			jsonPath("$.title") { value("Shared article") }
			jsonPath("$.notesMd") { value("#garden @home\n!today ^Anna") }
			jsonPath("$.projectId") { doesNotExist() }
			jsonPath("$.contextIds") { isEmpty() }
			jsonPath("$.dueDate") { doesNotExist() }
			jsonPath("$.list") { value("INBOX") }
		}
	}

	@Test
	fun `a too long first line is cut and kept in full in the notes`() {
		val line = "x".repeat(600)
		capture("""{"text":"$line","notes":"shared"}""").andExpect {
			status { isCreated() }
			jsonPath("$.title") { value("x".repeat(499) + "…") }
			jsonPath("$.notesMd") { value("$line\n\nshared") }
		}
	}

	@Test
	fun `notes are limited together with the rest of the text, at the limit and just above`() {
		// rest + "\n\n" + notes is exactly the limit.
		val rest = "r".repeat(50_000)
		val notesAtLimit = "n".repeat(100_000 - rest.length - 2)
		capture("""{"text":"first line\n$rest","notes":"$notesAtLimit"}""").andExpect {
			status { isCreated() }
			jsonPath("$.title") { value("first line") }
		}
		capture("""{"text":"first line\n$rest","notes":"${notesAtLimit}n"}""").isProblem(400, "notes")
		taskCount() shouldBe 1
	}

	@Test
	fun `the capture text is accepted at its maximum length`() {
		capture("""{"text":"${"x".repeat(100_000)}"}""").andExpect { status { isCreated() } }
		taskCount() shouldBe 1
	}

	@Test
	fun `blank notes are ignored`() {
		capture("""{"text":"Call mom","notes":"  \n "}""").andExpect {
			status { isCreated() }
			jsonPath("$.notesMd") { doesNotExist() }
		}
	}

	@Test
	fun `at most 50 files per capture`() {
		val fifty = (1..50).map { storedFile("f$it.txt").id }
		capture("""{"text":"Many files","fileIds":${jsonMapper.writeValueAsString(fifty)}}""").andExpect { status { isCreated() } }
		capture("""{"text":"Too many","fileIds":${jsonMapper.writeValueAsString(fifty + UuidV7.generate())}}""")
			.isProblem(400, "fileIds")
		taskCount() shouldBe 1
		count("select count(*) from link") shouldBe 50
	}

	@Test
	fun `an empty file list links nothing`() {
		val task = capture("""{"text":"No files","fileIds":[]}""").andExpect { status { isCreated() } }.json()
		links.linksOf(ItemRef(ItemType.TASK, UUID.fromString(task.get("id").asString()))).size shouldBe 0
	}

	@Test
	fun `files are linked also when the syntax is not parsed`() {
		val scan = storedFile("scan.txt")
		val task =
			captureJson("text" to "Receipt #garden", "parse" to false, "fileIds" to listOf(scan.id))
				.andExpect { status { isCreated() } }
				.json()
		links.linksOf(ItemRef(ItemType.TASK, UUID.fromString(task.get("id").asString()))).map { it.target.ref } shouldBe
			listOf(ItemRef(ItemType.FILE, scan.id))
	}

	@Test
	fun `a waiting for name longer than a title is rejected, not a server error`() {
		capture("""{"text":"Quote ^${"a".repeat(501)}"}""").isProblem(400, "waitingForText")
		taskCount() shouldBe 0
	}

	@Test
	fun `projects on hold are preferred over someday ones, and the oldest wins among equals`() {
		projects.save(Project("Garden", ProjectStatus.SOMEDAY))
		val onHold = projects.save(Project("Garden", ProjectStatus.ON_HOLD))
		projects.save(Project("garden", ProjectStatus.ON_HOLD))
		preview("""{"text":"Weeds #garden"}""")
			.andExpect { status { isOk() } }
			.json()
			.get("project")
			.get("id")
			.asString() shouldBe onHold.id.toString()
	}

	@Test
	fun `a someday project is matched when it is the only one`() {
		val someday = projects.save(Project("Garden", ProjectStatus.SOMEDAY))
		capture("""{"text":"Weeds #garden"}""").andExpect {
			status { isCreated() }
			jsonPath("$.projectId") { value(someday.id.toString()) }
		}
	}

	@Test
	fun `contexts captured together are all stored`() {
		val home = contexts.save(Context("@home"))
		val phone = contexts.save(Context("@phone", position = 1))
		val task = capture("""{"text":"@phone Call mom @home"}""").andExpect { status { isCreated() } }.json()
		val contextIds = task.get("contextIds").values().map { it.asString() }
		contextIds.toSet() shouldBe setOf(home.id.toString(), phone.id.toString())
		task.get("title").asString() shouldBe "Call mom"
	}

	@Test
	fun `preview of a text without a title answers 200 with an empty title`() {
		preview("""{"text":"!today ^waiting"}""").andExpect {
			status { isOk() }
			jsonPath("$.title") { value("") }
			jsonPath("$.list") { value("WAITING") }
			jsonPath("$.dueDate") { value("2026-03-01") }
			jsonPath("$.contexts") { isEmpty() }
		}
	}

	@Test
	fun `preview of a plain text has no project, due date or waiting for`() {
		preview("""{"text":"Call the plumber"}""").andExpect {
			status { isOk() }
			jsonPath("$.title") { value("Call the plumber") }
			jsonPath("$.list") { value("INBOX") }
			jsonPath("$.project") { value(null) }
			jsonPath("$.extraNotes") { value(null) }
			jsonPath("$.dueDate") { value(null) }
			jsonPath("$.contexts") { isEmpty() }
		}
	}

	@Test
	fun `preview rejects a too long text`() {
		preview("""{"text":"${"x".repeat(100_001)}"}""").isProblem(400, "text")
	}

	@Test
	fun `capture rejects malformed bodies`() {
		capture("""{"text":"x","fileIds":["not-a-uuid"]}""").andExpect { status { isBadRequest() } }
		// Jackson's Kotlin module rejects a null element of the non-null List<UUID>: Problem Details 400, not a 500.
		capture("""{"text":"x","fileIds":[null]}""").andExpect {
			status { isBadRequest() }
			content { contentType("application/problem+json") }
		}
		capture("""{"text":"x","parse":"maybe"}""").andExpect { status { isBadRequest() } }
		capture("""not json""").andExpect { status { isBadRequest() } }
		taskCount() shouldBe 0
	}

	@Test
	@WithAnonymousUser
	fun `preview needs a login`() {
		preview("""{"text":"x"}""").andExpect { status { isUnauthorized() } }
	}

	private companion object {
		const val CAPTURE = "/api/v1/capture"
	}
}
