package ch.sonensei.cockpit.calendar.infra.microsoft

import ch.sonensei.cockpit.calendar.sync.CalendarProviderException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.ZoneId
import java.time.ZoneOffset

/** [WindowsZones] (Windows and IANA names) and the paging-link rule of [GraphClient]. */
class WindowsZonesTests {
	@ParameterizedTest
	@CsvSource(
		"W. Europe Standard Time, Europe/Berlin",
		"Pacific Standard Time, America/Los_Angeles",
		"UTC, UTC",
		"UTC+12, Etc/GMT-12",
		"Europe/Zurich, Europe/Zurich",
	)
	fun `Windows and IANA names are zones`(
		name: String,
		zone: String,
	) {
		WindowsZones.zone(name) shouldBe ZoneId.of(zone)
	}

	@ParameterizedTest
	@ValueSource(strings = ["Customized Time Zone", "tzone://Microsoft/Custom", "Mars/Olympus_Mons", " "])
	fun `unknown and custom names are no zone`(name: String) {
		WindowsZones.zone(name).shouldBeNull()
		WindowsZones.zone(null).shouldBeNull()
	}

	@ParameterizedTest
	@CsvSource(
		"Europe/Berlin, W. Europe Standard Time",
		"America/New_York, Eastern Standard Time",
		// Not a golden zone: Graph accepts the IANA id.
		"Europe/Zurich, Europe/Zurich",
		"Etc/UTC, UTC",
		"Z, UTC",
	)
	fun `zones are named like Graph knows them`(
		zone: String,
		name: String,
	) {
		WindowsZones.name(ZoneId.of(zone)) shouldBe name
	}

	@Test
	fun `a fixed offset other than UTC has no name`() {
		WindowsZones.name(ZoneOffset.ofHours(2)).shouldBeNull()
		WindowsZones.name(ZoneOffset.ofHoursMinutes(-3, -30)).shouldBeNull()
		// Etc/GMT zones are fixed offsets too.
		WindowsZones.name(ZoneId.of("Etc/GMT+12")).shouldBeNull()
	}

	// --- GraphClient paging links

	private val client = GraphClient("http://localhost:1/v1.0", MicrosoftCalendarAdapterTests.FixedTokens(), JsonMapper.builder().build())

	@Test
	fun `paging links are made relative to the API, whatever their host`() {
		client.relative("https://graph.microsoft.com/v1.0/me/events/AAMk1/instances?\$skip=6") shouldBe "/me/events/AAMk1/instances?\$skip=6"
		client.relative("https://graph.microsoft.com/v1.0/me/calendarView/delta") shouldBe "/me/calendarView/delta"
	}

	@ParameterizedTest
	@ValueSource(strings = ["https://graph.microsoft.com/v1.0evil/me/events", "https://graph.microsoft.com/beta/me/events", "/v1.0"])
	fun `paging links outside the API path are refused`(link: String) {
		shouldThrow<CalendarProviderException> { client.relative(link) }.message shouldContain "outside the API"
	}

	@Test
	fun `an invalid paging link is refused`() {
		shouldThrow<CalendarProviderException> { client.relative("https://graph.example.test/v1.0/me events") }.message shouldContain "invalid"
	}
}
