package ch.sonensei.cockpit.calendar.sync

import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/** The parts of the sync engine that need no database: the adapter registry and the scheduler tick. */
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
}
