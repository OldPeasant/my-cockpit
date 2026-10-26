package ch.sonensei.cockpit.core.auth.passkey.api

import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * Digital Asset Links for the Android app ([AndroidAssetLinks]): Google's verifier and Credential Manager fetch
 * `https://<rp-id>/.well-known/assetlinks.json` without a login. Not part of the client API, so hidden from OpenAPI.
 * Without configured fingerprints it is an empty list (a valid file that links nothing).
 */
@Hidden
@RestController
class AssetLinksController(
	private val assetLinks: AndroidAssetLinks,
) {
	@GetMapping(PATH, produces = [MediaType.APPLICATION_JSON_VALUE])
	fun assetLinks(): ResponseEntity<List<AssetLinkStatement>> =
		ResponseEntity
			.ok()
			.cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
			.body(assetLinks.statements())

	companion object {
		const val PATH = "/.well-known/assetlinks.json"
	}
}
