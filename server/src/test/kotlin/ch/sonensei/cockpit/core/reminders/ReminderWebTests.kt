package ch.sonensei.cockpit.core.reminders

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.items.FakeItemResolvers
import ch.sonensei.cockpit.core.items.FakeItemResolversConfiguration
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.reminders.infra.ReminderRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** `/api/v1/reminders` over HTTP (SPEC.md §4.6), against Postgres, with fake item resolvers and the test clock. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeItemResolversConfiguration::class, MutableClockTestConfiguration::class)
@WithCockpitUser
class ReminderWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val repository: ReminderRepository,
	@Autowired private val service: ReminderService,
	@Autowired private val fakes: FakeItemResolvers,
	@Autowired private val clock: MutableClock,
	@Autowired private val jsonMapper: JsonMapper,
) : PostgresTestBase() {
	private val start = MutableClockTestConfiguration.START
	private lateinit var task: ItemRef

	@BeforeEach
	fun setUp() {
		repository.deleteAll()
		fakes.reset()
		clock.set(start)
		task = fakes.tasks.add("Call plumber")
	}

	private fun body(
		item: ItemRef,
		remindAt: Instant,
		channel: String? = null,
	) = jsonMapper.writeValueAsString(
		buildMap {
			put("itemType", item.type.name)
			put("itemId", item.id.toString())
			put("remindAt", remindAt.toString())
			if (channel != null) put("channel", channel)
		},
	)

	private fun post(content: String) =
		mockMvc.post(BASE) {
			with(csrf())
			contentType = MediaType.APPLICATION_JSON
			this.content = content
		}

	private fun create(
		item: ItemRef,
		remindAt: Instant,
	): UUID {
		val json =
			post(body(item, remindAt))
				.andExpect { status { isCreated() } }
				.andReturn()
				.response.contentAsString
		return UUID.fromString(jsonMapper.readTree(json).get("id").asString())
	}

	private fun ResultActionsDsl.isProblem(status: Int) =
		andExpect {
			status { isEqualTo(status) }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}

	@Test
	fun `POST creates a pending reminder with the resolved item and its location`() {
		val remindAt = start.plus(Duration.ofHours(2))

		val result =
			post(body(task, remindAt))
				.andExpect {
					status { isCreated() }
					jsonPath("$.item.type") { value("TASK") }
					jsonPath("$.item.id") { value(task.id.toString()) }
					jsonPath("$.item.available") { value(true) }
					jsonPath("$.item.title") { value("Call plumber") }
					jsonPath("$.item.icon") { value("task_icon") }
					jsonPath("$.item.deepLink") { value("/task/${task.id}") }
					jsonPath("$.remindAt") { value("2026-03-01T10:00:00Z") }
					jsonPath("$.channel") { value("NOTIFICATION") }
					jsonPath("$.firedAt") { doesNotExist() }
					jsonPath("$.createdAt") { isString() }
				}.andReturn()
		val id = UUID.fromString(jsonMapper.readTree(result.response.contentAsString).get("id").asString())
		UuidV7.isUuidV7(id) shouldBe true
		result.response.getHeader(HttpHeaders.LOCATION)!! shouldEndWith "$BASE/$id"
		repository.findById(id).get().remindAt shouldBe remindAt
	}

	@Test
	fun `POST accepts an explicit channel, a time in the past and an item type without a resolver`() {
		post(body(task, start.minus(Duration.ofDays(1)), channel = "NOTIFICATION")).andExpect {
			status { isCreated() }
			jsonPath("$.channel") { value("NOTIFICATION") }
		}
		val contact = ItemRef(ItemType.CONTACT, UuidV7.generate())
		post(body(contact, start)).andExpect {
			status { isCreated() }
			jsonPath("$.item.type") { value("CONTACT") }
			jsonPath("$.item.available") { value(false) }
			jsonPath("$.item.title") { doesNotExist() }
		}
		repository.count() shouldBe 2
	}

	@Test
	fun `POST rejects missing fields, an unknown item and invalid values`() {
		post("""{"itemId": "${task.id}", "remindAt": "$start"}""").isProblem(400).andExpect {
			jsonPath("$.errors[0].field") { value("itemType") }
		}
		post("""{"itemType": "TASK", "remindAt": "$start"}""").isProblem(400).andExpect {
			jsonPath("$.errors[0].field") { value("itemId") }
		}
		post("""{"itemType": "TASK", "itemId": "${task.id}"}""").isProblem(400).andExpect {
			jsonPath("$.errors[0].field") { value("remindAt") }
		}
		post(body(ItemRef(ItemType.TASK, UuidV7.generate()), start)).isProblem(400).andExpect {
			jsonPath("$.errors[0].field") { value("itemId") }
		}
		post("""{"itemType": "WIDGET", "itemId": "${task.id}", "remindAt": "$start"}""").isProblem(400)
		post("""{"itemType": "TASK", "itemId": "${task.id}", "remindAt": "tomorrow"}""").isProblem(400)
		post(body(task, start, channel = "SMS")).isProblem(400)
		post("{not json").isProblem(400)
		post("").isProblem(400)
		repository.findAll().shouldBeEmpty()
	}

	@Test
	fun `GET lists the reminders of one item, fired or not, earliest first`() {
		val later = create(task, start.plus(Duration.ofDays(2)))
		val earlier = create(task, start.minus(Duration.ofMinutes(5)))
		create(fakes.tasks.add("Other task"), start)
		service.fireDue() shouldBe 2

		mockMvc.get("$BASE?itemType=TASK&itemId=${task.id}").andExpect {
			status { isOk() }
			jsonPath("$.items.length()") { value(2) }
			jsonPath("$.items[0].id") { value(earlier.toString()) }
			jsonPath("$.items[0].firedAt") { value(start.toString()) }
			jsonPath("$.items[1].id") { value(later.toString()) }
			jsonPath("$.items[1].firedAt") { doesNotExist() }
			jsonPath("$.items[1].item.title") { value("Call plumber") }
		}
		mockMvc.get("$BASE?itemType=NOTE&itemId=${UuidV7.generate()}").andExpect {
			status { isOk() }
			jsonPath("$.items.length()") { value(0) }
		}
	}

	@Test
	fun `GET of a list with a failing resolver still answers with the item unavailable`() {
		create(task, start.plus(Duration.ofHours(1)))
		fakes.tasks.failing = true

		mockMvc.get("$BASE?itemType=TASK&itemId=${task.id}").andExpect {
			status { isOk() }
			jsonPath("$.items[0].item.available") { value(false) }
			jsonPath("$.items[0].item.id") { value(task.id.toString()) }
		}
	}

	@Test
	fun `GET needs a valid itemType and itemId`() {
		mockMvc.get("$BASE?itemId=${task.id}").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("itemType") } }
		mockMvc.get("$BASE?itemType=TASK").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("itemId") } }
		mockMvc.get("$BASE?itemType=WIDGET&itemId=${task.id}").isProblem(400).andExpect {
			jsonPath("$.errors[0].field") { value("itemType") }
		}
		mockMvc.get("$BASE?itemType=TASK&itemId=nope").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("itemId") } }
		mockMvc.get(BASE).isProblem(400)
	}

	@Test
	fun `GET by id returns the reminder, an unknown id is 404 and a malformed one 400`() {
		val id = create(task, start.plus(Duration.ofHours(3)))

		mockMvc.get("$BASE/$id").andExpect {
			status { isOk() }
			jsonPath("$.id") { value(id.toString()) }
			jsonPath("$.item.title") { value("Call plumber") }
			jsonPath("$.remindAt") { value("2026-03-01T11:00:00Z") }
		}
		mockMvc.get("$BASE/${UuidV7.generate()}").isProblem(404)
		mockMvc.get("$BASE/not-a-uuid").isProblem(400)
	}

	@Test
	fun `GET upcoming returns unfired reminders up to until, overdue ones included and the boundary inclusive`() {
		val overdue = create(task, start.minus(Duration.ofHours(1)))
		val atUntil = create(task, start.plus(Duration.ofDays(1)))
		create(task, start.plus(Duration.ofDays(1)).plusNanos(1_000))
		val note = fakes.notes.add("Holiday ideas")
		val soon = create(note, start.plus(Duration.ofHours(2)))
		val until = start.plus(Duration.ofDays(1))

		mockMvc.get("$BASE/upcoming?until=$until").andExpect {
			status { isOk() }
			jsonPath("$.items.length()") { value(3) }
			jsonPath("$.items[0].id") { value(overdue.toString()) }
			jsonPath("$.items[1].id") { value(soon.toString()) }
			jsonPath("$.items[1].item.title") { value("Holiday ideas") }
			jsonPath("$.items[2].id") { value(atUntil.toString()) }
		}

		service.fireDue() shouldBe 1
		mockMvc.get("$BASE/upcoming?until=$until").andExpect {
			jsonPath("$.items.length()") { value(2) }
			jsonPath("$.items[0].id") { value(soon.toString()) }
		}
		mockMvc.get("$BASE/upcoming?until=${start.minus(Duration.ofDays(1))}").andExpect {
			status { isOk() }
			jsonPath("$.items.length()") { value(0) }
		}
	}

	@Test
	fun `GET upcoming needs a valid until`() {
		mockMvc.get("$BASE/upcoming").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("until") } }
		mockMvc.get("$BASE/upcoming?until=next-week").isProblem(400).andExpect { jsonPath("$.errors[0].field") { value("until") } }
	}

	@Test
	fun `DELETE removes the reminder and a second DELETE is 404`() {
		val id = create(task, start.plus(Duration.ofHours(1)))

		mockMvc.delete("$BASE/$id") { with(csrf()) }.andExpect { status { isNoContent() } }

		repository.findAll().shouldBeEmpty()
		mockMvc.get("$BASE/$id").isProblem(404)
		mockMvc.delete("$BASE/$id") { with(csrf()) }.isProblem(404)
		mockMvc.delete("$BASE/not-a-uuid") { with(csrf()) }.isProblem(400)
	}

	@Test
	fun `state-changing requests need the CSRF token`() {
		mockMvc
			.post(BASE) {
				contentType = MediaType.APPLICATION_JSON
				content = body(task, start)
			}.andExpect { status { isForbidden() } }
		repository.findAll().shouldBeEmpty()

		val id = create(task, start)
		mockMvc.delete("$BASE/$id").andExpect { status { isForbidden() } }
		repository.count() shouldBe 1
	}

	@Test
	@WithAnonymousUser
	fun `reminders need a login`() {
		mockMvc.get("$BASE?itemType=TASK&itemId=${task.id}").andExpect { status { isUnauthorized() } }
		mockMvc.get("$BASE/upcoming?until=$start").andExpect { status { isUnauthorized() } }
		mockMvc.get("$BASE/${UuidV7.generate()}").andExpect { status { isUnauthorized() } }
		post(body(task, start)).andExpect { status { isUnauthorized() } }
		mockMvc.delete("$BASE/${UuidV7.generate()}") { with(csrf()) }.andExpect { status { isUnauthorized() } }
		repository.findAll().shouldBeEmpty()
	}

	private companion object {
		const val BASE = "/api/v1/reminders"
	}
}
