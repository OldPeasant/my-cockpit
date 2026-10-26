package ch.sonensei.cockpit.core.auth.passkey

import ch.sonensei.cockpit.core.auth.passkey.api.AndroidAssetLinks
import ch.sonensei.cockpit.core.auth.passkey.api.AssetLinkStatement
import ch.sonensei.cockpit.core.auth.passkey.api.AssetLinkTarget
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import tools.jackson.databind.json.JsonMapper

/** The app's Digital Asset Links configuration: fingerprint format, statements and the apk-key-hash origins. */
class AndroidAssetLinksTests {
	@Test
	fun `fingerprints with or without colons, in any case, are normalized to upper-case hex with colons`() {
		AndroidAssetLinks.normalize(PLAIN_LOWER) shouldBe COLONS
		AndroidAssetLinks.normalize(COLONS.lowercase()) shouldBe COLONS
		AndroidAssetLinks.normalize(COLONS) shouldBe COLONS
	}

	@Test
	fun `anything that is not 32 bytes of hex is rejected, naming the property`() {
		val invalid =
			listOf(
				"",
				":::",
				PLAIN_LOWER.dropLast(1), // 63 digits
				PLAIN_LOWER + "0", // 65 digits
				PLAIN_LOWER.dropLast(2), // 31 bytes
				PLAIN_LOWER.replaceFirst('a', 'g'), // not hex
				PLAIN_LOWER.replaceFirst("ab", "a b"), // inner whitespace
				COLONS.replace(':', '-'), // other separator
				"SHA256:$COLONS", // with a label
			)
		for (fingerprint in invalid) {
			val error = shouldThrow<IllegalArgumentException> { AndroidAssetLinks.normalize(fingerprint) }
			error.message.shouldNotBeNull() shouldContain "cockpit.auth.passkey.android-fingerprints"
		}
	}

	@Test
	fun `an invalid fingerprint fails the construction, so a typo can't silently disable passkeys in the app`() {
		shouldThrow<IllegalArgumentException> { AndroidAssetLinks("ch.sonensei.cockpit", listOf(COLONS, "not-a-fingerprint")) }
	}

	@Test
	fun `the apk-key-hash origin is the unpadded base64url of the certificate's SHA-256`() {
		// Expected values computed independently (Python: base64.urlsafe_b64encode(bytes.fromhex(...)).rstrip('=')).
		AndroidAssetLinks.apkKeyHashOrigin(COLONS) shouldBe "android:apk-key-hash:qxLNNO9Wq3jNkO8SqzTNVu94q5DNEu80q1bNeO-QqxI"
		AndroidAssetLinks.apkKeyHashOrigin(PLAIN_LOWER) shouldBe "android:apk-key-hash:qxLNNO9Wq3jNkO8SqzTNVu94q5DNEu80q1bNeO-QqxI"
		// All bits set: base64url uses '_' and '-' instead of '/' and '+', and no '=' padding.
		AndroidAssetLinks.apkKeyHashOrigin("FF".repeat(32)) shouldBe
			"android:apk-key-hash:__________________________________________8"
	}

	@Test
	fun `blank entries are ignored, duplicates in another notation are listed once, and the package is trimmed`() {
		val assetLinks = AndroidAssetLinks(" ch.sonensei.cockpit ", listOf(" $COLONS ", "", "  ", PLAIN_LOWER, OTHER))

		assetLinks.packageName shouldBe "ch.sonensei.cockpit"
		assetLinks.fingerprints shouldBe listOf(COLONS, OTHER)
		assetLinks.statements() shouldBe
			listOf(
				AssetLinkStatement(
					listOf("delegate_permission/common.get_login_creds"),
					AssetLinkTarget("android_app", "ch.sonensei.cockpit", listOf(COLONS, OTHER)),
				),
			)
		assetLinks.origins() shouldBe
			setOf(
				"android:apk-key-hash:qxLNNO9Wq3jNkO8SqzTNVu94q5DNEu80q1bNeO-QqxI",
				AndroidAssetLinks.apkKeyHashOrigin(OTHER),
			)
	}

	@Test
	fun `without fingerprints or without a package there is no statement`() {
		AndroidAssetLinks("ch.sonensei.cockpit", emptyList()).statements().shouldBeEmpty()
		AndroidAssetLinks("ch.sonensei.cockpit", listOf("", " ")).statements().shouldBeEmpty()
		AndroidAssetLinks("ch.sonensei.cockpit", emptyList()).origins().shouldBeEmpty()
		AndroidAssetLinks("  ", listOf(COLONS)).statements().shouldBeEmpty()
	}

	@Test
	fun `a statement serializes with the Digital Asset Links JSON names`() {
		val json = JsonMapper.builder().build()

		json.readTree(json.writeValueAsString(AndroidAssetLinks("ch.sonensei.cockpit", listOf(COLONS)).statements())) shouldBe
			json.readTree(
				"""
				[{
				  "relation": ["delegate_permission/common.get_login_creds"],
				  "target": {"namespace": "android_app", "package_name": "ch.sonensei.cockpit", "sha256_cert_fingerprints": ["$COLONS"]}
				}]
				""",
			)
	}

	private fun runner(environment: Map<String, Any>) =
		ApplicationContextRunner()
			.withBean(AndroidAssetLinks::class.java)
			.withInitializer { context ->
				context.beanFactory.conversionService = ApplicationConversionService.getSharedInstance()
				context.environment.propertySources.addFirst(
					SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment),
				)
			}

	@Test
	fun `the deployment's COCKPIT_AUTH_PASSKEY_ANDROID_ environment variables are read`() {
		runner(emptyMap()).run { context ->
			val assetLinks = context.getBean(AndroidAssetLinks::class.java)
			assetLinks.packageName shouldBe "ch.sonensei.cockpit"
			assetLinks.fingerprints.shouldBeEmpty()
			assetLinks.statements().shouldBeEmpty()
		}
		runner(
			mapOf(
				"COCKPIT_AUTH_PASSKEY_ANDROID_PACKAGE" to "org.example.fictitious",
				"COCKPIT_AUTH_PASSKEY_ANDROID_FINGERPRINTS" to "$PLAIN_LOWER, $OTHER",
			),
		).run { context ->
			val assetLinks = context.getBean(AndroidAssetLinks::class.java)
			assetLinks.packageName shouldBe "org.example.fictitious"
			assetLinks.fingerprints shouldBe listOf(COLONS, OTHER)
		}
	}

	@Test
	fun `an invalid fingerprint in the environment fails the startup`() {
		runner(mapOf("COCKPIT_AUTH_PASSKEY_ANDROID_FINGERPRINTS" to "AB:CD")).run { context ->
			context.startupFailure.shouldNotBeNull()
		}
	}

	companion object {
		/** Fictitious fingerprints, not of any real certificate. */
		const val COLONS = "AB:12:CD:34:EF:56:AB:78:CD:90:EF:12:AB:34:CD:56:EF:78:AB:90:CD:12:EF:34:AB:56:CD:78:EF:90:AB:12"
		const val PLAIN_LOWER = "ab12cd34ef56ab78cd90ef12ab34cd56ef78ab90cd12ef34ab56cd78ef90ab12"
		const val OTHER = "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"
	}
}
