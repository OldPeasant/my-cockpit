package ch.sonensei.cockpit.core.trash

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeTrashContributorsConfiguration::class, MutableClockTestConfiguration::class)
@WithCockpitUser
class TrashWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val fakes: FakeTrashContributors,
	@Autowired private val jsonMapper: JsonMapper,
) : PostgresTestBase() {
	private val start = MutableClockTestConfiguration.START

	@BeforeEach
	fun setUp() {
		fakes.reset()
	}

	private fun restore(ref: ItemRef) = mockMvc.post("$BASE/${ref.type}/${ref.id}/restore") { with(csrf()) }

	private fun purge(ref: ItemRef) = mockMvc.delete("$BASE/${ref.type}/${ref.id}") { with(csrf()) }

	private fun ResultActionsDsl.isProblem(status: Int) =
		andExpect {
			status { isEqualTo(status) }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}

	@Test
	fun `GET lists deleted items with deletion and purge time`() {
		val task = fakes.tasks.addDeleted("Call plumber", start.minus(Duration.ofDays(2)))
		val note = fakes.notes.addDeleted("Holiday ideas", start.minus(Duration.ofDays(1)))

		mockMvc.get(BASE).andExpect {
			status { isOk() }
			jsonPath("$.items.length()") { value(2) }
			jsonPath("$.items[0].type") { value("NOTE") }
			jsonPath("$.items[0].id") { value(note.id.toString()) }
			jsonPath("$.items[0].title") { value("Holiday ideas") }
			jsonPath("$.items[0].subtitle") { value("note subtitle") }
			jsonPath("$.items[0].icon") { value("note_icon") }
			jsonPath("$.items[0].deletedAt") { value("2026-02-28T08:00:00Z") }
			jsonPath("$.items[0].purgeAt") { value("2026-03-30T08:00:00Z") }
			jsonPath("$.items[1].id") { value(task.id.toString()) }
			jsonPath("$.nextCursor") { doesNotExist() }
		}
	}

	@Test
	fun `GET pages with cursor and limit`() {
		val refs = List(3) { fakes.tasks.addDeleted("Task $it", start.minus(Duration.ofMinutes(it.toLong()))) }

		val first =
			mockMvc
				.get("$BASE?limit=2")
				.andExpect {
					status { isOk() }
					jsonPath("$.items.length()") { value(2) }
					jsonPath("$.nextCursor") { isString() }
				}.andReturn()
				.response.contentAsString
		val firstJson = jsonMapper.readTree(first)
		val cursor = firstJson.get("nextCursor").asString()
		val second =
			jsonMapper.readTree(
				mockMvc
					.get("$BASE?limit=2&cursor=$cursor")
					.andExpect { jsonPath("$.nextCursor") { doesNotExist() } }
					.andReturn()
					.response.contentAsString,
			)

		val ids = (firstJson.get("items") + second.get("items")).map { it.get("id").asString() }
		ids shouldContainExactly refs.map { it.id.toString() }
	}

	@Test
	fun `GET rejects an invalid limit or cursor`() {
		mockMvc.get("$BASE?limit=0").isProblem(400)
		mockMvc.get("$BASE?limit=201").isProblem(400)
		mockMvc.get("$BASE?cursor=***").isProblem(400)
	}

	@Test
	fun `POST restore takes the item out of the Trash`() {
		val task = fakes.tasks.addDeleted("Task", start)

		restore(task).andExpect { status { isNoContent() } }

		fakes.tasks.isActive(task.id) shouldBe true
		mockMvc.get(BASE).andExpect { jsonPath("$.items.length()") { value(0) } }
		restore(task).isProblem(404)
	}

	@Test
	fun `DELETE purges the item`() {
		val note = fakes.notes.addDeleted("Note", start)

		purge(note).andExpect { status { isNoContent() } }

		fakes.notes.purged shouldContainExactly listOf(note.id)
		purge(note).isProblem(404)
	}

	@Test
	fun `restore and delete of an active, unknown or untrashable item give 404`() {
		val active = fakes.tasks.addActive("Active")
		for (ref in listOf(active, ItemRef(ItemType.TASK, UuidV7.generate()), ItemRef(ItemType.HABIT, UuidV7.generate()))) {
			restore(ref).isProblem(404)
			purge(ref).isProblem(404)
		}
		fakes.tasks.isActive(active.id) shouldBe true
	}

	@Test
	fun `an unknown type or a malformed id gives 400`() {
		mockMvc.post("$BASE/NOPE/${UuidV7.generate()}/restore") { with(csrf()) }.isProblem(400)
		mockMvc.delete("$BASE/TASK/not-a-uuid") { with(csrf()) }.isProblem(400)
	}

	@Test
	fun `changes need a CSRF token`() {
		val task = fakes.tasks.addDeleted("Task", start)

		mockMvc.post("$BASE/${task.type}/${task.id}/restore").andExpect { status { isForbidden() } }
		mockMvc.delete("$BASE/${task.type}/${task.id}").andExpect { status { isForbidden() } }
		fakes.tasks.isDeleted(task.id) shouldBe true
	}

	@Test
	@WithAnonymousUser
	fun `the Trash needs a login`() {
		mockMvc.get(BASE).andExpect { status { isUnauthorized() } }
	}

	companion object {
		private const val BASE = "/api/v1/trash"
	}
}
