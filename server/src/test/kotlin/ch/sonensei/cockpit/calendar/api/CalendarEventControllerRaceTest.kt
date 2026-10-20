package ch.sonensei.cockpit.calendar.api

import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.core.persistence.BaseEntity
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.web.ConflictException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * A concurrent create with the same client id can insert between [CalendarEventService.create]'s existence check and
 * its own insert. The service is mocked to force that interleaving deterministically.
 */
class CalendarEventControllerRaceTest {
	private val service = mockk<CalendarEventService>()
	private val controller = CalendarEventController(service, JsonMapper.builder().build())

	private fun storedEvent(id: UUID): CalendarEvent =
		CalendarEvent(UUID.fromString("01900000-0000-7000-8000-0000000000aa"), "Dentist", id).apply {
			setTimed(Instant.parse("2026-03-02T13:00:00Z"), Instant.parse("2026-03-02T14:00:00Z"), ZoneId.of("Europe/Zurich"))
			val now = Instant.parse("2026-03-01T10:00:00Z")
			listOf("createdAt", "updatedAt").forEach { name ->
				BaseEntity::class.java
					.getDeclaredField(name)
					.apply { isAccessible = true }
					.set(this, now)
			}
		}

	@Test
	fun `a create that loses the insert race to the same client id answers like an idempotent repeat`() {
		val id = UuidV7.generate()
		every { service.create(id, any()) } throws DataIntegrityViolationException("duplicate key value violates calendar_event_pkey")
		every { service.existingCreate(id) } returns CreatedEvent(storedEvent(id), created = false)

		val response =
			controller.create(
				CreateEventRequest(id = id, title = "Dentist (second tab)", start = Instant.parse("2026-03-02T13:00:00Z")),
			)

		response.statusCode shouldBe HttpStatus.OK
		response.headers.getFirst(HttpHeaders.ETAG) shouldBe "\"0\""
		response.body!!.id shouldBe id
		response.body!!.title shouldBe "Dentist"
		verify(exactly = 1) { service.existingCreate(id) }
	}

	@Test
	fun `an integrity violation without a client id is not swallowed`() {
		val failure = DataIntegrityViolationException("some other constraint")
		every { service.create(null, any()) } throws failure

		shouldThrow<DataIntegrityViolationException> {
			controller.create(CreateEventRequest(title = "Dentist", start = Instant.parse("2026-03-02T13:00:00Z")))
		} shouldBe failure
	}

	@Test
	fun `an integrity violation for a client id that still does not exist is not swallowed`() {
		val id = UuidV7.generate()
		every { service.create(id, any()) } throws DataIntegrityViolationException("some other constraint")
		every { service.existingCreate(id) } returns null

		shouldThrow<DataIntegrityViolationException> {
			controller.create(CreateEventRequest(id = id, title = "Dentist", start = Instant.parse("2026-03-02T13:00:00Z")))
		}
	}

	@Test
	fun `the racing create whose winner was trashed meanwhile is a conflict`() {
		val id = UuidV7.generate()
		every { service.create(id, any()) } throws DataIntegrityViolationException("duplicate key value violates calendar_event_pkey")
		every { service.existingCreate(id) } throws ConflictException("An event with this id is in the Trash")

		shouldThrow<ConflictException> {
			controller.create(CreateEventRequest(id = id, title = "Dentist", start = Instant.parse("2026-03-02T13:00:00Z")))
		}
	}
}
