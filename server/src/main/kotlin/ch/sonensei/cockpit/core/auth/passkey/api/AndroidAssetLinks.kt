package ch.sonensei.cockpit.core.auth.passkey.api

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * The Android app as Digital Asset Links name it, from `cockpit.auth.passkey.android-*` (env
 * `COCKPIT_AUTH_PASSKEY_ANDROID_PACKAGE` / `_FINGERPRINTS`). Credential Manager only uses passkeys of an RP ID whose
 * domain lists the app in `/.well-known/assetlinks.json` ([AssetLinksController]).
 * - `android-package`: the app's package name (`applicationId`).
 * - `android-fingerprints`: comma-separated SHA-256 fingerprints of the signing certificate(s), hex with or without
 *   colons (`keytool`/`apksigner` print them). Empty: no statement, so the app can't use passkeys.
 *
 * An invalid fingerprint, or fingerprints without a package, fail the startup, so a typo doesn't silently turn
 * passkeys off in the app. This class is the only owner of this configuration: [PasskeyRelyingParties] accepts exactly
 * the [origins] of the fingerprints `assetlinks.json` names.
 */
@Component
class AndroidAssetLinks(
	@Value("\${cockpit.auth.passkey.android-package:ch.sonensei.cockpit}") packageName: String,
	@Value("\${cockpit.auth.passkey.android-fingerprints:}") fingerprints: List<String>,
) {
	val packageName: String = packageName.trim()

	/** Upper-case hex with colons, the format `assetlinks.json` requires. */
	val fingerprints: List<String> =
		fingerprints
			.map(String::trim)
			.filter(String::isNotEmpty)
			.map(::normalize)
			.distinct()

	init {
		require(this.packageName.isNotEmpty() || this.fingerprints.isEmpty()) {
			"cockpit.auth.passkey.android-package is blank, but android-fingerprints are configured: " +
				"set the app's package name (applicationId), e.g. ch.sonensei.cockpit"
		}
	}

	/** The statements of `assetlinks.json`: one for the app with all its fingerprints, none without fingerprints. */
	fun statements(): List<AssetLinkStatement> =
		if (fingerprints.isEmpty()) {
			emptyList()
		} else {
			listOf(AssetLinkStatement(listOf(GET_LOGIN_CREDS), AssetLinkTarget("android_app", packageName, fingerprints)))
		}

	/** The WebAuthn origins (`android:apk-key-hash:...`) the app sends when signed with one of [fingerprints]. */
	fun origins(): Set<String> = fingerprints.mapTo(linkedSetOf(), ::apkKeyHashOrigin)

	companion object {
		const val GET_LOGIN_CREDS = "delegate_permission/common.get_login_creds"
		private val SHA256_HEX = Regex("[0-9A-F]{64}")

		/** `AB:CD:...` (32 bytes) from hex with or without colons; anything else is rejected. */
		fun normalize(fingerprint: String): String {
			val hex = fingerprint.replace(":", "").uppercase()
			require(SHA256_HEX.matches(hex)) {
				"cockpit.auth.passkey.android-fingerprints: not a SHA-256 fingerprint (64 hex digits): $fingerprint"
			}
			return hex.chunked(2).joinToString(":")
		}

		/** `android:apk-key-hash:<base64url without padding of the certificate's SHA-256>` (Credential Manager). */
		fun apkKeyHashOrigin(fingerprint: String): String {
			val bytes = normalize(fingerprint).split(':').map { it.toInt(16).toByte() }.toByteArray()
			return "android:apk-key-hash:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
		}
	}
}

/** One statement of a Digital Asset Links file. */
data class AssetLinkStatement(
	val relation: List<String>,
	val target: AssetLinkTarget,
)

/** The app a statement is about; the JSON names follow the Digital Asset Links format. */
data class AssetLinkTarget(
	val namespace: String,
	@get:JsonProperty("package_name") val packageName: String,
	@get:JsonProperty("sha256_cert_fingerprints") val sha256CertFingerprints: List<String>,
)
