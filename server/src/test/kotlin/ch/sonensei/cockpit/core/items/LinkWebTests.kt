package ch.sonensei.cockpit.core.items

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkOrigin
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.items.infra.LinkRepository
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeItemResolversConfiguration::class)
@WithCockpitUser
class LinkWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val repository: LinkRepository,
	@Autowired private val fakes: FakeItemResolvers,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val service: LinkService,
) : PostgresTestBase() {
	private lateinit var task: ItemRef
	private lateinit var note: ItemRef

	@BeforeEach
	fun setUp() {
		repository.deleteAll()
		fakes.reset()
		task = fakes.tasks.add("Call plumber")
		note = fakes.notes.add("Holiday ideas")
	}

	private fun body(
		source: ItemRef,
		target: ItemRef,
		label: String? = null,
	) = jsonMapper.writeValueAsString(
		mapOf(
			"source" to mapOf("type" to source.type, "id" to source.id),
			"target" to mapOf("type" to target.type, "id" to target.id),
			"label" to label,
		),
	)

	private fun post(content: String) =
		mockMvc.post(BASE) {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			this.content = content
		}

	private fun createLink(
		source: ItemRef,
		target: ItemRef,
		label: String? = null,
	): UUID {
		val json =
			post(body(source, target, label))
				.andExpect { status { isCreated() } }
				.andReturn()
				.response.contentAsString
		return UUID.fromString(jsonMapper.readTree(json).get("id").asString())
	}

	private fun list(item: ItemRef) = mockMvc.get("$BASE?type=${item.type}&id=${item.id}")

	private fun ResultActionsDsl.isProblem(status: Int) =
		andExpect {
			status { isEqualTo(status) }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}

	@Test
	fun `POST creates a link and returns it resolved with its location`() {
		val result =
			post(body(task, note, "origin"))
				.andExpect {
					status { isCreated() }
					jsonPath("$.source.type") { value("TASK") }
					jsonPath("$.source.id") { value(task.id.toString()) }
					jsonPath("$.source.available") { value(true) }
					jsonPath("$.source.title") { value("Call plumber") }
					jsonPath("$.target.type") { value("NOTE") }
					jsonPath("$.target.subtitle") { value("note subtitle") }
					jsonPath("$.target.icon") { value("note_icon") }
					jsonPath("$.target.deepLink") { value("/note/${note.id}") }
					jsonPath("$.direction") { value("OUTGOING") }
					jsonPath("$.label") { value("origin") }
					jsonPath("$.origin") { value("MANUAL") }
					jsonPath("$.createdAt") { isString() }
				}.andReturn()
		val id = jsonMapper.readTree(result.response.contentAsString).get("id").asString()
		UuidV7.isUuidV7(UUID.fromString(id)) shouldBe true
		result.response.getHeader(HttpHeaders.LOCATION)!! shouldEndWith "$BASE/$id"
	}

	@Test
	fun `GET lists links in both directions from the queried item's perspective`() {
		val outgoing = createLink(task, note)
		val habit = ItemRef(ItemType.HABIT, UuidV7.generate())
		val incoming = createLink(habit, task)

		list(task).andExpect {
			status { isOk() }
			jsonPath("$.items.length()") { value(2) }
			jsonPath("$.items[0].id") { value(outgoing.toString()) }
			jsonPath("$.items[0].direction") { value("OUTGOING") }
			jsonPath("$.items[1].id") { value(incoming.toString()) }
			jsonPath("$.items[1].direction") { value("INCOMING") }
			jsonPath("$.items[1].source.type") { value("HABIT") }
			jsonPath("$.items[1].source.available") { value(false) }
			jsonPath("$.items[1].source.title") { doesNotExist() }
		}
		list(note).andExpect {
			jsonPath("$.items.length()") { value(1) }
			jsonPath("$.items[0].direction") { value("INCOMING") }
		}
	}

	@Test
	fun `GET marks a deleted item unavailable instead of failing`() {
		createLink(task, note)
		fakes.notes.remove(note.id)

		list(task).andExpect {
			status { isOk() }
			jsonPath("$.items[0].target.available") { value(false) }
			jsonPath("$.items[0].target.id") { value(note.id.toString()) }
		}
	}

	@Test
	fun `GET marks the ends of a failing resolver unavailable and still answers 200`() {
		createLink(task, note)
		fakes.notes.failing = true

		list(task).andExpect {
			status { isOk() }
			jsonPath("$.items.length()") { value(1) }
			jsonPath("$.items[0].source.available") { value(true) }
			jsonPath("$.items[0].source.title") { value("Call plumber") }
			jsonPath("$.items[0].target.type") { value("NOTE") }
			jsonPath("$.items[0].target.id") { value(note.id.toString()) }
			jsonPath("$.items[0].target.available") { value(false) }
			jsonPath("$.items[0].target.title") { doesNotExist() }
		}
	}

	@Test
	fun `GET of an item without links is an empty list`() {
		list(task).andExpect {
			status { isOk() }
			jsonPath("$.items.length()") { value(0) }
		}
	}

	@Test
	fun `GET needs a valid type and id`() {
		mockMvc.get("$BASE?type=WIDGET&id=${task.id}").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("type") } }
		mockMvc.get("$BASE?type=TASK&id=nope").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("id") } }
		mockMvc.get("$BASE?type=TASK").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("id") } }
	}

	@Test
	fun `POST rejects self links, unknown items and missing ends`() {
		post(body(task, task)).isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("target") } }
		post(body(task, ItemRef(ItemType.NOTE, UuidV7.generate()))).isProblem(400)
		post("""{"source": {"type": "TASK", "id": "${task.id}"}}""").isProblem(400).andExpect {
			jsonPath("$.errors[0].field") { value("target") }
		}
		post("""{"source": {"type": "TASK"}, "target": {"type": "NOTE", "id": "${note.id}"}}""").isProblem(400).andExpect {
			jsonPath("$.errors[0].field") { value("source.id") }
		}
		post("""{"source": {"type": "WIDGET", "id": "${task.id}"}, "target": {"type": "NOTE", "id": "${note.id}"}}""")
			.isProblem(400)
		post(body(task, note, "x".repeat(201))).isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("label") } }
		repository.findAll().shouldBeEmpty()
	}

	@Test
	fun `POST of an already linked pair is a conflict`() {
		createLink(task, note)

		post(body(note, task)).isProblem(409)
	}

	@Test
	fun `DELETE removes the link from both ends`() {
		val id = createLink(task, note)

		mockMvc.delete("$BASE/$id") { with(csrf()) }.andExpect { status { isNoContent() } }

		list(task).andExpect { jsonPath("$.items.length()") { value(0) } }
		list(note).andExpect { jsonPath("$.items.length()") { value(0) } }
		mockMvc.delete("$BASE/$id") { with(csrf()) }.isProblem(404)
	}

	@Test
	fun `DELETE of an inline link is a conflict and keeps it`() {
		service.sync(note, listOf(task))
		val inlineId = repository.findAll().single().id

		list(task).andExpect {
			jsonPath("$.items.length()") { value(1) }
			jsonPath("$.items[0].id") { value(inlineId.toString()) }
			jsonPath("$.items[0].origin") { value("SYNC") }
		}

		mockMvc.delete("$BASE/$inlineId") { with(csrf()) }.isProblem(409)

		list(task).andExpect {
			jsonPath("$.items.length()") { value(1) }
			jsonPath("$.items[0].id") { value(inlineId.toString()) }
		}
	}

	@Test
	fun `state-changing requests need the CSRF token`() {
		mockMvc
			.post(BASE) {
				contentType = MediaType.APPLICATION_JSON
				content = body(task, note)
			}.andExpect { status { isForbidden() } }
		repository.findAll().shouldBeEmpty()
	}

	@Test
	@WithAnonymousUser
	fun `links need a login`() {
		list(task).andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `POST accepts an end whose type has no resolver yet and returns it unavailable`() {
		val contact = ItemRef(ItemType.CONTACT, UuidV7.generate())

		post(body(task, contact)).andExpect {
			status { isCreated() }
			jsonPath("$.target.type") { value("CONTACT") }
			jsonPath("$.target.id") { value(contact.id.toString()) }
			jsonPath("$.target.available") { value(false) }
			jsonPath("$.target.title") { doesNotExist() }
			jsonPath("$.target.deepLink") { doesNotExist() }
		}
	}

	@Test
	fun `POST rejects a source its resolver does not know`() {
		post(body(ItemRef(ItemType.TASK, UuidV7.generate()), note)).isProblem(400).andExpect {
			jsonPath("$.errors[0].field") { value("source") }
		}
		repository.findAll().shouldBeEmpty()
	}

	@Test
	fun `POST stores a blank label as none and accepts a label of the maximum length`() {
		post(body(task, note, "   ")).andExpect {
			status { isCreated() }
			jsonPath("$.label") { doesNotExist() }
		}
		val other = fakes.tasks.add("Order firewood")
		post(body(other, note, "y".repeat(200))).andExpect {
			status { isCreated() }
			jsonPath("$.label") { value("y".repeat(200)) }
		}
	}

	@Test
	fun `POST of malformed JSON or without a body is a bad request`() {
		post("{not json").isProblem(400)
		post("").isProblem(400)
		repository.findAll().shouldBeEmpty()
	}

	@Test
	fun `GET without a type is a bad request`() {
		mockMvc.get("$BASE?id=${task.id}").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("type") } }
	}

	@Test
	fun `DELETE of a malformed or unknown id is a problem`() {
		mockMvc.delete("$BASE/not-a-uuid") { with(csrf()) }.isProblem(400)
		mockMvc.delete("$BASE/${UuidV7.generate()}") { with(csrf()) }.isProblem(404)
	}

	@Test
	fun `DELETE needs the CSRF token`() {
		val id = createLink(task, note)

		mockMvc.delete("$BASE/$id").andExpect { status { isForbidden() } }
		repository.count() shouldBe 1
	}

	@Test
	@WithAnonymousUser
	fun `creating and deleting links need a login`() {
		post(body(task, note)).andExpect { status { isUnauthorized() } }
		mockMvc.delete("$BASE/${UuidV7.generate()}") { with(csrf()) }.andExpect { status { isUnauthorized() } }
		repository.findAll().shouldBeEmpty()
	}

	@Test
	fun `POST links a pair that only has inline links in both directions`() {
		service.sync(note, listOf(task))
		service.sync(task, listOf(note))

		val id = createLink(task, note)

		repository.findAll().map { it.origin } shouldContainExactlyInAnyOrder
			listOf(LinkOrigin.SYNC, LinkOrigin.SYNC, LinkOrigin.MANUAL)
		post(body(note, task)).isProblem(409)
		list(note).andExpect {
			jsonPath("$.items.length()") { value(1) }
			jsonPath("$.items[0].id") { value(id.toString()) }
		}
	}

	@Test
	fun `GET lists a pair with a manual and inline links once, as the manual link from each side`() {
		// The note's inline link is older than the manual link, but the manual one (with its label) is shown.
		service.sync(note, listOf(task))
		val manualId = createLink(task, note, "origin")

		list(task).andExpect {
			jsonPath("$.items.length()") { value(1) }
			jsonPath("$.items[0].id") { value(manualId.toString()) }
			jsonPath("$.items[0].direction") { value("OUTGOING") }
			jsonPath("$.items[0].label") { value("origin") }
			jsonPath("$.items[0].origin") { value("MANUAL") }
		}
		list(note).andExpect {
			jsonPath("$.items.length()") { value(1) }
			jsonPath("$.items[0].id") { value(manualId.toString()) }
			jsonPath("$.items[0].direction") { value("INCOMING") }
			jsonPath("$.items[0].origin") { value("MANUAL") }
			jsonPath("$.items[0].target.title") { value("Holiday ideas") }
		}

		// Removing the manual link keeps the pair linked through the note's inline link.
		mockMvc.delete("$BASE/$manualId") { with(csrf()) }.andExpect { status { isNoContent() } }

		list(task).andExpect {
			jsonPath("$.items.length()") { value(1) }
			jsonPath("$.items[0].direction") { value("INCOMING") }
			jsonPath("$.items[0].source.id") { value(note.id.toString()) }
			jsonPath("$.items[0].label") { doesNotExist() }
			jsonPath("$.items[0].origin") { value("SYNC") }
		}
	}

	@Test
	fun `DELETE of a manual link next to an inline link of the same pair removes only the manual one`() {
		service.sync(note, listOf(task))
		val inlineId = repository.findAll().single().id
		val manualId = createLink(note, task)

		mockMvc.delete("$BASE/$inlineId") { with(csrf()) }.isProblem(409)
		mockMvc.delete("$BASE/$manualId") { with(csrf()) }.andExpect { status { isNoContent() } }

		repository.findAll().map { it.id } shouldBe listOf(inlineId)
		// The inline link does not block a new manual link of the same pair.
		createLink(task, note)
	}

	private companion object {
		const val BASE = "/api/v1/links"
	}
}
