package ch.sonensei.cockpit.calendar.infra.caldav

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * The origin check of [CalDavClient.resolve]: hrefs a CalDAV server sends are only followed (and get the password)
 * when they are on the source's scheme, host and port, default ports included. All hosts are fictitious.
 */
class CalDavClientTests {
	private val https = CalDavClient(URI("https://caldav.example.test/dav/"), "alice", "fictitious-secret", CalDavClient.httpClient())
	private val http = CalDavClient(URI("http://caldav.example.test/dav/"), "alice", "fictitious-secret", CalDavClient.httpClient())

	@Test
	fun `paths and full URIs on the source's origin resolve, default ports and host case included`() {
		https.resolve("/dav/calendars/alice/work/") shouldBe URI("https://caldav.example.test/dav/calendars/alice/work/")
		https.resolve("calendars/alice/") shouldBe URI("https://caldav.example.test/dav/calendars/alice/")
		https.resolve("https://caldav.example.test:443/dav/x/") shouldBe URI("https://caldav.example.test:443/dav/x/")
		https.resolve("HTTPS://CALDAV.Example.TEST/dav/x/")?.path shouldBe "/dav/x/"
		http.resolve("http://caldav.example.test:80/dav/x/") shouldBe URI("http://caldav.example.test:80/dav/x/")
		// Spaces some servers send unencoded.
		https.resolve(" /dav/team b/ ")?.path shouldBe "/dav/team b/"
	}

	@Test
	fun `hrefs on another scheme, host or port, and malformed ones, resolve to nothing`() {
		listOf(
			"http://caldav.example.test/dav/x/",
			"https://caldav.example.test:8443/dav/x/",
			"https://caldav.example.test:80/dav/x/",
			"https://elsewhere.example.test/dav/x/",
			"https://caldav.example.test.elsewhere.example.test/dav/x/",
			"//elsewhere.example.test/dav/x/",
			"mailto:alice@example.test",
			"http://[broken",
		).forEach { href -> https.resolve(href).shouldBeNull() }
		http.resolve("http://caldav.example.test:443/dav/x/").shouldBeNull()
		http.resolve("https://caldav.example.test/dav/x/").shouldBeNull()
	}
}
