package ch.sonensei.cockpit.calendar.sync

import ch.sonensei.cockpit.calendar.api.CalendarEventService
import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.FakeCalendarAdapter.Companion.ZURICH
import ch.sonensei.cockpit.calendar.sync.FakeCalendarAdapter.Companion.remote
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.annotation.UserConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The parts of the sync engine that need no database: the adapter registry, the scheduler tick and its wiring, and the
 * normalization of untrusted remote events.
 */
class CalendarSyncUnitTests {
	private class StubAdapter(
		override val sourceType: CalendarSourceType,
	) : CalendarProviderAdapter {
		override val pollInterval: Duration = Duration.ofMinutes(5)

		override fun listCalendars(source: CalendarSource) = emptyList<RemoteCalendar>()

		override fun fetchEvents(
			source: CalendarSource,
			calendarExternalId: String,
			cursor: String?,
		) = RemoteEventChanges()

		override fun createEvent(
			source: CalendarSource,
			calendarExternalId: String,
			event: RemoteEvent,
		) = event

		override fun updateEvent(
			source: CalendarSource,
			calendarExternalId: String,
			event: RemoteEvent,
			ifMatch: String?,
		) = event

		override fun deleteEvent(
			source: CalendarSource,
			calendarExternalId: String,
			key: RemoteEventKey,
			ifMatch: String?,
		): String? = null
	}

	// --- registry

	@Test
	fun `the registry finds each adapter by its source type and supports only those types`() {
		val google = StubAdapter(CalendarSourceType.GOOGLE)
		val caldav = StubAdapter(CalendarSourceType.CALDAV)
		val registry = CalendarProviderRegistry(listOf(google, caldav))

		registry.find(CalendarSourceType.GOOGLE) shouldBe google
		registry.find(CalendarSourceType.CALDAV) shouldBe caldav
		registry.find(CalendarSourceType.LOCAL).shouldBeNull()
		registry.supports(CalendarSourceType.GOOGLE) shouldBe true
		registry.supports(CalendarSourceType.LOCAL) shouldBe false
		CalendarSourceType.entries
			.filter { it !in setOf(CalendarSourceType.GOOGLE, CalendarSourceType.CALDAV) }
			.forEach { registry.supports(it) shouldBe false }
	}

	@Test
	fun `an empty registry syncs nothing`() {
		val registry = CalendarProviderRegistry(emptyList())

		CalendarSourceType.entries.forEach {
			registry.find(it).shouldBeNull()
			registry.supports(it) shouldBe false
		}
	}

	@Test
	fun `the registry rejects two adapters for the same type`() {
		val error =
			assertThrows<IllegalArgumentException> {
				CalendarProviderRegistry(listOf(StubAdapter(CalendarSourceType.GOOGLE), StubAdapter(CalendarSourceType.GOOGLE)))
			}
		error.message!! shouldContain "GOOGLE"
	}

	@Test
	fun `the registry rejects an adapter for LOCAL sources`() {
		val error = assertThrows<IllegalArgumentException> { CalendarProviderRegistry(listOf(StubAdapter(CalendarSourceType.LOCAL))) }
		error.message shouldBe "LOCAL sources are not synced"
	}

	// --- scheduler

	@Test
	fun `a scheduler tick syncs the due sources`() {
		val sync = mockk<CalendarSyncService>()
		every { sync.syncDue() } returns 2

		CalendarSyncScheduler(sync).run()

		verify(exactly = 1) { sync.syncDue() }
	}

	@Test
	fun `a failing scheduler tick is swallowed, and the next tick tries again`() {
		val sync = mockk<CalendarSyncService>()
		every { sync.syncDue() } throws IllegalStateException("database unavailable") andThen 1
		val scheduler = CalendarSyncScheduler(sync)

		assertDoesNotThrow { scheduler.run() }
		assertDoesNotThrow { scheduler.run() }

		verify(exactly = 2) { sync.syncDue() }
	}

	private val schedulerContext =
		ApplicationContextRunner()
			.withConfiguration(UserConfigurations.of(CalendarSyncSchedulingConfiguration::class.java))
			.withBean(CalendarSyncService::class.java, { mockk<CalendarSyncService>(relaxed = true) })

	@Test
	fun `the scheduler is on by default and checks every tick`() {
		// A long tick, so the check does not run while the context is open.
		schedulerContext.withPropertyValues("cockpit.calendar.sync.tick=PT1H").run { context ->
			context.getBeansOfType(CalendarSyncScheduler::class.java).size shouldBe 1
			val task =
				context
					.getBeansOfType(ScheduledTaskHolder::class.java)
					.values
					.flatMap { it.scheduledTasks }
					.single()
					.task
					.shouldBeInstanceOf<FixedDelayTask>()
			task.intervalDuration shouldBe Duration.ofHours(1)
			task.initialDelayDuration shouldBe Duration.ofHours(1)
		}
	}

	@Test
	fun `the scheduler ticks every 30 seconds without configuration`() {
		schedulerContext.run { context ->
			val task =
				context
					.getBeansOfType(ScheduledTaskHolder::class.java)
					.values
					.flatMap { it.scheduledTasks }
					.single()
					.task
					.shouldBeInstanceOf<FixedDelayTask>()
			task.intervalDuration shouldBe Duration.ofSeconds(30)
		}
	}

	@Test
	fun `the scheduler is off when disabled, as in the tests`() {
		schedulerContext.withPropertyValues("cockpit.calendar.sync.scheduler.enabled=false").run { context ->
			context.getBeansOfType(CalendarSyncScheduler::class.java).size shouldBe 0
		}
	}

	// --- normalizing remote events (review finding CR-2)

	@Test
	fun `an all-day remote event that does not end after its first day cannot be stored`() {
		val day = LocalDate.parse("2026-03-02")

		val sameDay = assertThrows<IllegalArgumentException> { remote("a", "Holiday").copy(timing = Timing.AllDay(day, day)).normalized() }
		sameDay.message shouldBe "An all-day event ends after its first day"
		assertThrows<IllegalArgumentException> { remote("a", "Holiday").copy(timing = Timing.AllDay(day, day.minusDays(1))).normalized() }
		remote("a", "Holiday").copy(timing = Timing.AllDay(day, day.plusDays(1))).normalized().timing shouldBe Timing.AllDay(day, day.plusDays(1))
	}

	@Test
	fun `a timed remote event of zero length is kept, one that ends before it starts cannot be stored`() {
		val start = Instant.parse("2026-03-02T09:00:00Z")

		remote("a", "Reminder").copy(timing = Timing.Timed(start, start, ZURICH)).normalized().timing shouldBe Timing.Timed(start, start, ZURICH)
		val error =
			assertThrows<IllegalArgumentException> {
				remote("a", "Broken").copy(timing = Timing.Timed(start, start.minusSeconds(1), ZURICH)).normalized()
			}
		error.message shouldBe "An event cannot end before it starts"
	}

	@Test
	fun `a series rule is stored without RRULE prefix in either case, an empty one makes a single event`() {
		remote("a", "Weekly", rrule = " rrule:FREQ=WEEKLY;COUNT=3 ").normalized().rrule shouldBe "FREQ=WEEKLY;COUNT=3"
		remote("a", "Weekly", rrule = "RRULE:FREQ=DAILY").normalized().rrule shouldBe "FREQ=DAILY"
		remote("a", "Once", rrule = "RRULE:").normalized().rrule.shouldBeNull()
		remote("a", "Once", rrule = "   ").normalized().rrule.shouldBeNull()
	}

	@Test
	fun `a series rule that is invalid, has no FREQ or is overlong cannot be stored`() {
		listOf("FREQ=SOMETIMES", "COUNT=3", "FREQ=WEEKLY;BYDAY=" + "MO,".repeat(CalendarEventService.MAX_TEXT_LENGTH / 3) + "TU")
			.forEach { rule ->
				val error = assertThrows<IllegalArgumentException> { remote("a", "Broken", rrule = rule).normalized() }
				error.message shouldBe "The series has an invalid RRULE"
			}
	}

	@Test
	fun `the rule of an override is not checked as a series rule`() {
		// Overrides keep no rule of their own (copyFrom ignores it); an odd one must not make the override unstorable.
		val override =
			remote(
				"s",
				"Weekly (late)",
				rrule = "FREQ=SOMETIMES",
			).copy(key = RemoteEventKey("s", OccurrenceId.of(Instant.parse("2026-03-09T09:00:00Z"))))

		override.normalized().rrule shouldBe "FREQ=SOMETIMES"
	}

	@Test
	fun `a blank remote title becomes a placeholder`() {
		remote("a", "   ").normalized().title shouldBe UNTITLED
		remote("a", "").normalized().title shouldBe UNTITLED
	}

	@Test
	fun `only http and https conference URLs with a host are kept`() {
		fun url(value: String) = remote("a", "Call").copy(conferenceUrl = value).normalized().conferenceUrl

		url("https://meet.example.org/abc") shouldBe "https://meet.example.org/abc"
		url("http://meet.example.org/abc") shouldBe "http://meet.example.org/abc"
		url("javascript:alert(1)").shouldBeNull()
		url("data:text/html;base64,PHNjcmlwdD4=").shouldBeNull()
		url("ftp://files.example.org/x").shouldBeNull()
		url("https:///no-host").shouldBeNull()
		url("not a url").shouldBeNull()
		url("https://meet.example.org/" + "a".repeat(CalendarEventService.MAX_TEXT_LENGTH)).shouldBeNull()
	}

	@Test
	fun `attendees and reminders are capped at the API limits, attendees without a name are kept`() {
		val many = (1..CalendarEventService.MAX_ATTENDEES + 5).map { Attendee("guest$it@example.org") }
		val reminders = (0 until CalendarEventService.MAX_REMINDERS + 5).toList()

		val normalized =
			remote("a", "All hands")
				.copy(
					attendees = listOf(Attendee(" ", "Blank"), Attendee("x".repeat(CalendarEventService.MAX_EMAIL_LENGTH) + "@example.org")) + many,
					reminderMinutes = listOf(CalendarEventService.MAX_REMINDER_MINUTES, 0) + reminders,
				).normalized()

		normalized.attendees.size shouldBe CalendarEventService.MAX_ATTENDEES
		normalized.attendees.first() shouldBe Attendee("guest1@example.org")
		normalized.reminderMinutes.size shouldBe CalendarEventService.MAX_REMINDERS
		normalized.reminderMinutes.take(2) shouldBe listOf(CalendarEventService.MAX_REMINDER_MINUTES, 0)
	}

	@Test
	fun `a valid remote event passes normalization unchanged`() {
		val event =
			remote("a", "Standup", rrule = "FREQ=WEEKLY;COUNT=3").copy(
				etag = "e1",
				description = "Daily sync",
				location = "Room 4",
				attendees = listOf(Attendee("ann@example.org", "Ann Example")),
				reminderMinutes = listOf(10),
				conferenceUrl = "https://meet.example.org/abc",
			)

		event.normalized() shouldBe event
	}
}
