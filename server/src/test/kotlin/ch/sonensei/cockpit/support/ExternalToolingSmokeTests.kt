package ch.sonensei.cockpit.support

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Proves the `integrationTest` wiring: GreenMail and WireMock start and answer. */
@ExternalTest
class ExternalToolingSmokeTests {
	@Test
	fun `GreenMail receives a message over SMTP`() {
		GreenMailUtil.sendTextEmailTest("alice@example.test", "bob@example.test", "Hello", "Fictitious body")

		greenMail.waitForIncomingEmail(5_000, 1) shouldBe true
		greenMail.receivedMessages.single().subject shouldBe "Hello"
	}

	@Test
	fun `WireMock serves a stubbed response`() {
		val server = WireMockServer(wireMockConfig().dynamicPort())
		server.start()
		try {
			server.stubFor(get("/ping").willReturn(aResponse().withStatus(200).withBody("pong")))
			val response =
				HttpClient.newHttpClient().send(
					HttpRequest.newBuilder(URI.create("${server.baseUrl()}/ping")).build(),
					HttpResponse.BodyHandlers.ofString(),
				)
			response.statusCode() shouldBe 200
			response.body() shouldBe "pong"
		} finally {
			server.stop()
		}
	}

	companion object {
		@JvmField
		@RegisterExtension
		val greenMail: GreenMailExtension = GreenMailExtension(ServerSetupTest.SMTP)
	}
}
