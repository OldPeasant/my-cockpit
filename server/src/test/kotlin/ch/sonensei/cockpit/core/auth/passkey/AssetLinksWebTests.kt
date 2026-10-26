package ch.sonensei.cockpit.core.auth.passkey

import ch.sonensei.cockpit.core.auth.passkey.api.AndroidAssetLinks
import ch.sonensei.cockpit.core.auth.passkey.api.PasskeyRelyingParties
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper

/**
 * `/.well-known/assetlinks.json` as a deployment configures it (`COCKPIT_AUTH_PASSKEY_ANDROID_*`): public, JSON, and
 * the app origins of the listed fingerprints are accepted by the relying party. Its own context for the properties,
 * closed afterwards.
 */
@SpringBootTest(
	properties = [
		"cockpit.domain=cockpit.example.org",
		"cockpit.auth.passkey.android-package=ch.sonensei.cockpit",
		"cockpit.auth.passkey.android-fingerprints=${AssetLinksWebTests.FINGERPRINT_1}, ${AssetLinksWebTests.FINGERPRINT_2_PLAIN}",
	],
)
@DirtiesContext
@AutoConfigureMockMvc
class AssetLinksWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val relyingParties: PasskeyRelyingParties,
) : PostgresTestBase() {
	@Test
	fun `assetlinks json names the app and its fingerprints, without login`() {
		val body =
			mockMvc
				.get("/.well-known/assetlinks.json")
				.andExpect {
					status { isOk() }
					content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
				}.andReturn()
				.response.contentAsString

		jsonMapper.readTree(body) shouldBe
			jsonMapper.readTree(
				"""
				[{
				  "relation": ["delegate_permission/common.get_login_creds"],
				  "target": {
				    "namespace": "android_app",
				    "package_name": "ch.sonensei.cockpit",
				    "sha256_cert_fingerprints": ["$FINGERPRINT_1", "$FINGERPRINT_2"]
				  }
				}]
				""",
			)
	}

	@Test
	fun `the relying party accepts the app origin of each configured fingerprint, besides the web origin`() {
		val relyingParty = relyingParties.resolve(MockHttpServletRequest("POST", "/api/v1/auth/passkeys/token").apply { serverName = "server" })

		relyingParty.origins shouldBe
			setOf(
				"https://cockpit.example.org",
				AndroidAssetLinks.apkKeyHashOrigin(FINGERPRINT_1),
				AndroidAssetLinks.apkKeyHashOrigin(FINGERPRINT_2),
			)
		relyingParty.origins shouldContainAll setOf("android:apk-key-hash:ASNFZ4mrze8BI0VniavN7wEjRWeJq83vASNFZ4mrze8")
	}

	companion object {
		const val FINGERPRINT_1 = "01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF"
		const val FINGERPRINT_2 = "FE:DC:BA:98:76:54:32:10:FE:DC:BA:98:76:54:32:10:FE:DC:BA:98:76:54:32:10:FE:DC:BA:98:76:54:32:10"

		/** The same as [FINGERPRINT_2] without colons and in lower case, as some tools print it. */
		const val FINGERPRINT_2_PLAIN = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
	}
}
