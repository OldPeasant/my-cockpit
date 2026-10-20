package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * The image proxy endpoint against a local image server (private networks allowed only for this test context, with a
 * 1 KB size and 1 s time limit).
 */
@SpringBootTest(
	properties = [
		"cockpit.mail.read.image-proxy.allow-private-networks=true",
		"cockpit.mail.read.image-proxy.max-size=1KB",
		"cockpit.mail.read.image-proxy.timeout=1s",
	],
)
@AutoConfigureMockMvc
@WithCockpitUser
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MailImageProxyWebTests(
	@Autowired private val mockMvc: MockMvc,
) : PostgresTestBase() {
	private val server = WireMockServer(wireMockConfig().dynamicPort())

	@BeforeAll
	fun start() = server.start()

	@AfterAll
	fun stop() = server.stop()

	@BeforeEach
	fun reset() = server.resetAll()

	// As a parameter: a query string in the URL template would be percent-encoded a second time.
	private fun proxy(path: String) = mockMvc.get("/api/v1/mail/image-proxy") { param("url", "http://127.0.0.1:${server.port()}$path") }

	@Test
	fun `an image is returned with its type as untrusted, privately cacheable inline content`() {
		server.stubFor(
			get(urlEqualTo("/logo.png")).willReturn(
				aResponse().withHeader("Content-Type", "image/png").withHeader("Set-Cookie", "tracking=fictitious").withBody(PNG),
			),
		)

		val response = proxy("/logo.png").andReturn().response

		response.status shouldBe 200
		response.contentType shouldBe "image/png"
		response.contentAsByteArray.toList() shouldBe PNG.toList()
		response.getHeader("X-Content-Type-Options") shouldBe "nosniff"
		response.getHeader("Content-Security-Policy") shouldBe "default-src 'none'; sandbox"
		response.getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "max-age=86400, private"
		response.getHeader(HttpHeaders.CONTENT_DISPOSITION) shouldBe "inline"
		// The image server's cookie is not passed on (the app's own CSRF cookie may be set).
		response.getHeaders(HttpHeaders.SET_COOKIE).none { it.startsWith("tracking=") } shouldBe true
	}

	@Test
	fun `failures are Problem Details with an error code and no URL`() {
		server.stubFor(get(urlEqualTo("/x.svg")).willReturn(aResponse().withHeader("Content-Type", "image/svg+xml").withBody("<svg/>")))
		server.stubFor(get(urlEqualTo("/missing.png")).willReturn(aResponse().withStatus(404)))

		proxy("/x.svg").andExpect {
			status { isBadGateway() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.errorCode") { value("NOT_AN_IMAGE") }
			jsonPath("$.detail") { value("The remote resource is not a supported image") }
		}
		proxy("/missing.png").andExpect {
			status { isBadGateway() }
			jsonPath("$.errorCode") { value("UPSTREAM_FAILED") }
		}
		mockMvc.get("/api/v1/mail/image-proxy?url=file%3A%2F%2F%2Fetc%2Fpasswd").andExpect {
			status { isBadRequest() }
			jsonPath("$.errorCode") { value("INVALID_URL") }
		}
	}

	@Test
	fun `a too large image is 502 and a slow server 504`() {
		server.stubFor(get(urlEqualTo("/big.png")).willReturn(aResponse().withHeader("Content-Type", "image/png").withBody(ByteArray(2048))))
		server.stubFor(
			get(urlEqualTo("/slow.png")).willReturn(aResponse().withHeader("Content-Type", "image/png").withBody(PNG).withFixedDelay(3000)),
		)

		proxy("/big.png").andExpect {
			status { isBadGateway() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.errorCode") { value("TOO_LARGE") }
		}
		proxy("/slow.png").andExpect {
			status { isGatewayTimeout() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.errorCode") { value("TIMEOUT") }
			jsonPath("$.detail") { value("The image server did not answer in time") }
		}
	}

	@Test
	fun `the url parameter is required`() {
		mockMvc.get("/api/v1/mail/image-proxy").andExpect { status { isBadRequest() } }
	}

	private companion object {
		val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
	}
}
