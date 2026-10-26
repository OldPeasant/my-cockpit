package ch.sonensei.cockpit.contacts.carddav

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.any
import com.github.tomakehurst.wiremock.client.WireMock.anyUrl
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.util.Base64

/**
 * [CardDavClient] without Spring against WireMock: Basic authentication, requests and redirects only within the
 * account's origin (the password never reaches another host), which answers become a [CardDavException] (401, 5xx,
 * network failures; message without password or response body) and which go to the caller, and which requests are
 * sent again after a dropped connection. The user name and password are fictitious.
 */
class CardDavClientTests {
	private val client = CardDavClient(URI("${wireMock.baseUrl()}/dav/"), USER, PASSWORD)

	@BeforeEach
	fun reset() {
		wireMock.resetAll()
		other.resetAll()
	}

	private fun uri(path: String): URI = URI("${wireMock.baseUrl()}$path")

	private fun answer(
		path: String,
		status: Int,
		body: String = "",
	) {
		wireMock.stubFor(any(urlPathEqualTo(path)).willReturn(aResponse().withStatus(status).withBody(body)))
	}

	private fun requests(): List<LoggedRequest> = wireMock.allServeEvents.map { it.request }.reversed()

	private fun propfind(path: String = "/dav/"): CardDavClient.Response = client.propfind(uri(path), 0, "<D:getetag/>", WHAT)

	private fun put(headers: Map<String, String>): CardDavClient.Response =
		client.send("PUT", uri("/dav/book/ada.vcf"), "BEGIN:VCARD", CardDavClient.VCARD, headers, WHAT)

	private fun failure(block: () -> Unit): CardDavException = shouldThrow<CardDavException> { block() }

	// --- requests

	@Test
	fun `a PROPFIND carries Basic authentication, Depth, the XML body and its content type`() {
		wireMock.stubFor(
			any(urlPathEqualTo("/dav/")).willReturn(aResponse().withStatus(207).withHeader("ETag", "\"e1\"").withBody("<x/>")),
		)

		val response = propfind()

		response.status shouldBe 207
		response.body shouldBe "<x/>"
		response.header("ETag") shouldBe "\"e1\""
		response.header("X-Missing").shouldBeNull()
		val request = requests().single()
		request.method.value() shouldBe "PROPFIND"
		request.getHeader("Authorization") shouldBe AUTHORIZATION
		request.getHeader("Depth") shouldBe "0"
		request.getHeader("Content-Type") shouldStartWith "application/xml"
		request.bodyAsString shouldContain "<D:prop><D:getetag/></D:prop>"
		request.bodyAsString shouldContain "xmlns:CR=\"urn:ietf:params:xml:ns:carddav\""
	}

	@Test
	fun `without a user name no Authorization header is sent`() {
		answer("/dav/", 207)

		CardDavClient(URI("${wireMock.baseUrl()}/dav/"), null, null).propfind(uri("/dav/"), 0, "", WHAT)

		requests().single().containsHeader("Authorization").shouldBeFalse()
	}

	@Test
	fun `a write sends its headers and content type`() {
		answer("/dav/book/ada.vcf", 204)

		val response = put(mapOf("If-Match" to "\"e1\""))

		response.ok.shouldBeTrue()
		val request = requests().single()
		request.method.value() shouldBe "PUT"
		request.getHeader("If-Match") shouldBe "\"e1\""
		request.getHeader("Content-Type") shouldStartWith "text/vcard"
		request.bodyAsString shouldBe "BEGIN:VCARD"
	}

	// --- answers

	@Test
	fun `403, 404, 409 and 412 go to the caller`() {
		listOf(403, 404, 409, 412).forEach { status ->
			answer("/dav/", status, "refused")

			val response = propfind()

			response.status shouldBe status
			response.ok.shouldBeFalse()
			response.body shouldBe "refused"
		}
	}

	@Test
	fun `401 fails without the password or the response body in the message`() {
		answer("/dav/", 401, "secret-response-body")

		val e = failure { propfind() }

		e.message shouldBe "$WHAT: the CardDAV server rejected the user name or password"
		e.message.orEmpty() shouldNotContain PASSWORD
		e.message.orEmpty() shouldNotContain "secret-response-body"
	}

	@Test
	fun `5xx answers fail with the status but without the response body`() {
		listOf(500, 503).forEach { status ->
			answer("/dav/", status, "secret-response-body")

			val e = failure { propfind() }

			e.message shouldBe "$WHAT failed at the CardDAV server (HTTP $status)"
		}
	}

	@Test
	fun `an unreachable server fails as could not be reached`() {
		val port = ServerSocket(0).use { it.localPort }
		val down = CardDavClient(URI("http://localhost:$port/dav/"), USER, PASSWORD)

		val e = failure { down.propfind(URI("http://localhost:$port/dav/"), 0, "", WHAT) }

		e.message shouldBe "$WHAT: the CardDAV server could not be reached"
	}

	// --- origin and redirects

	@Test
	fun `a redirect within the origin is followed with the same method and authentication`() {
		wireMock.stubFor(any(urlPathEqualTo("/dav/")).willReturn(aResponse().withStatus(301).withHeader("Location", "/dav/moved/")))
		answer("/dav/moved/", 207, "moved")

		propfind().body shouldBe "moved"

		val moved = requests().last()
		moved.url shouldBe "/dav/moved/"
		moved.method.value() shouldBe "PROPFIND"
		moved.getHeader("Authorization") shouldBe AUTHORIZATION
	}

	@Test
	fun `a redirect to another host fails before anything is sent there`() {
		wireMock.stubFor(
			any(urlPathEqualTo("/dav/")).willReturn(aResponse().withStatus(302).withHeader("Location", "${other.baseUrl()}/collect")),
		)
		other.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(207)))

		val e = failure { propfind() }

		e.message shouldBe "$WHAT: the CardDAV server pointed to another host"
		other.allServeEvents.shouldBeEmpty()
	}

	@Test
	fun `a request to another origin is not sent at all`() {
		other.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)))

		val e = failure { client.send("GET", URI("${other.baseUrl()}/dav/ada.vcf"), what = WHAT) }

		e.message shouldBe "$WHAT: the CardDAV server pointed to another host"
		other.allServeEvents.shouldBeEmpty()
	}

	@Test
	fun `a redirect without Location fails`() {
		answer("/dav/", 307)

		failure { propfind() }.message shouldBe "$WHAT: the CardDAV server redirected without a target"
	}

	@Test
	fun `a redirect loop stops after five redirects`() {
		wireMock.stubFor(any(urlPathEqualTo("/dav/")).willReturn(aResponse().withStatus(308).withHeader("Location", "/dav/")))

		failure { propfind() }.message shouldBe "$WHAT: the CardDAV server redirected too often"
		requests().size shouldBe 6
	}

	// --- resolve

	@Test
	fun `hrefs resolve within the origin only`() {
		val dav = CardDavClient(URI("https://dav.example.test/dav/book/"), USER, PASSWORD)

		dav.resolve("/dav/book/ada.vcf") shouldBe URI("https://dav.example.test/dav/book/ada.vcf")
		dav.resolve("bob.vcf") shouldBe URI("https://dav.example.test/dav/book/bob.vcf")
		dav.resolve(" https://DAV.example.test:443/dav/book/cleo.vcf ") shouldBe URI("https://DAV.example.test:443/dav/book/cleo.vcf")
		dav.resolve("/dav/book/with space.vcf") shouldBe URI("https://dav.example.test/dav/book/with%20space.vcf")
		dav.resolve("https://evil.example.test/dav/book/ada.vcf").shouldBeNull()
		dav.resolve("//evil.example.test/dav/book/ada.vcf").shouldBeNull()
		dav.resolve("http://dav.example.test/dav/book/ada.vcf").shouldBeNull()
		dav.resolve("https://dav.example.test:8443/dav/book/ada.vcf").shouldBeNull()
		dav.resolve("mailto:ada@example.test").shouldBeNull()
		dav.resolve("/dav/book/%zz.vcf").shouldBeNull()
	}

	// --- dropped connections

	private fun dropFirst(
		path: String,
		then: Int,
	) {
		wireMock.stubFor(
			any(urlPathEqualTo(path))
				.inScenario("drop")
				.whenScenarioStateIs(Scenario.STARTED)
				.willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
				.willSetStateTo("up"),
		)
		wireMock.stubFor(
			any(urlPathEqualTo(path))
				.inScenario("drop")
				.whenScenarioStateIs("up")
				.willReturn(aResponse().withStatus(then).withBody("again")),
		)
	}

	@Test
	fun `a reading request is sent once more after the connection dropped before an answer`() {
		dropFirst("/dav/", 207)

		propfind().body shouldBe "again"
		requests().size shouldBe 2
	}

	@Test
	fun `a write guarded by If-Match is sent once more after a dropped connection`() {
		dropFirst("/dav/book/ada.vcf", 204)

		val response = put(mapOf("If-Match" to "\"e1\""))

		response.status shouldBe 204
		requests().size shouldBe 2
	}

	@Test
	fun `an unguarded write is not sent again after a dropped connection`() {
		dropFirst("/dav/book/ada.vcf", 204)

		val e = failure { put(emptyMap()) }

		e.message shouldBe "$WHAT: the CardDAV server could not be reached"
		requests().size shouldBe 1
	}

	@Test
	fun `which requests may be sent twice`() {
		val ifMatch = mapOf("If-Match" to "\"e1\"")
		listOf("GET", "head", "OPTIONS", "PROPFIND", "REPORT").forEach { CardDavClient.repeatable(it, emptyMap()).shouldBeTrue() }
		listOf("PUT", "DELETE", "POST", "MKCOL", "PROPPATCH").forEach { CardDavClient.repeatable(it, emptyMap()).shouldBeFalse() }
		CardDavClient.repeatable("PUT", ifMatch).shouldBeTrue()
		CardDavClient.repeatable("delete", mapOf("if-match" to "\"e1\"")).shouldBeTrue()
		CardDavClient.repeatable("PUT", mapOf("IF-NONE-MATCH" to "*")).shouldBeTrue()
		CardDavClient.repeatable("POST", ifMatch).shouldBeFalse()
		CardDavClient.repeatable("PUT", mapOf("Depth" to "0")).shouldBeFalse()
	}

	companion object {
		const val USER = "alice-fictitious"
		const val PASSWORD = "fictitious-password-123"
		const val WHAT = "Listing the contacts"
		val AUTHORIZATION = "Basic " + Base64.getEncoder().encodeToString("$USER:$PASSWORD".toByteArray())

		val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		/** Another origin (another port), which must never receive a request. */
		val other: WireMockServer = WireMockServer(wireMockConfig().dynamicPort()).apply { start() }

		@JvmStatic
		@AfterAll
		fun stop() {
			wireMock.stop()
			other.stop()
		}
	}
}
