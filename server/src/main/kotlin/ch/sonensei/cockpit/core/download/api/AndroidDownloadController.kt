package ch.sonensei.cockpit.core.download.api

import ch.sonensei.cockpit.core.download.infra.AndroidApk
import ch.sonensei.cockpit.core.download.infra.AndroidApkStore
import ch.sonensei.cockpit.core.web.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Direct download of the Android app, without the Play Store (SPEC.md §11). Needs a login like the API. The APK is
 * sent with its version in [VERSION_HEADER] and a validator (ETag, Last-Modified), so clients can download only when
 * it changed; `version` answers the same as JSON, for an update check without the download.
 */
@RestController
@RequestMapping(AndroidDownloadController.PATH)
@Tag(name = "Download", description = "Downloads of the client apps")
class AndroidDownloadController(
	private val store: AndroidApkStore,
) {
	@GetMapping(produces = [APK_MIME, PROBLEM_JSON])
	@Operation(
		operationId = "downloadAndroidApp",
		summary = "The Android app (APK)",
		description = "The release APK deployed on this server, as an attachment `cockpit-<version>.apk`.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The APK",
		headers = [Header(name = VERSION_HEADER, description = "`versionName` of the APK, if known", schema = Schema(type = "string"))],
		content = [Content(mediaType = APK_MIME, schema = Schema(type = "string", format = "binary"))],
	)
	@NoApkResponse
	fun download(): ResponseEntity<Resource> {
		val apk = current()
		val builder =
			ResponseEntity
				.ok()
				.contentType(MediaType.parseMediaType(APK_MIME))
				.contentLength(apk.size)
				.eTag(eTag(apk))
				.lastModified(apk.modified)
				.cacheControl(CacheControl.noCache().cachePrivate())
				.header(
					HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition
						.attachment()
						.filename(fileName(apk))
						.build()
						.toString(),
				)
		apk.versionName?.let { builder.header(VERSION_HEADER, it) }
		return builder.body(FileSystemResource(apk.path))
	}

	@GetMapping("/version", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "getAndroidAppVersion", summary = "Version of the downloadable Android app")
	@ApiResponse(responseCode = "200", description = "The version of the APK `GET /download/android` sends")
	@NoApkResponse
	fun version(): AndroidAppVersion {
		val apk = current()
		return AndroidAppVersion(apk.versionName, apk.versionCode, apk.size, apk.modified)
	}

	private fun current(): AndroidApk = store.current() ?: throw NotFoundException("No Android app is available on this server")

	companion object {
		const val PATH = "/download/android"
		const val VERSION_HEADER = "X-Cockpit-App-Version"
		const val APK_MIME = "application/vnd.android.package-archive"
		private const val PROBLEM_JSON = "application/problem+json"
		private val SAFE_VERSION = Regex("[A-Za-z0-9._+-]{1,64}")

		/** A weak validator from size and modification time, so a download never has to hash the whole APK. */
		fun eTag(apk: AndroidApk): String = "W/\"${apk.size.toString(16)}-${apk.modified.toEpochMilli().toString(16)}\""

		/** `cockpit-<versionName>.apk`, or `cockpit.apk` when the version is unknown or not safe in a file name. */
		fun fileName(apk: AndroidApk): String =
			apk.versionName?.takeIf { SAFE_VERSION.matches(it) }?.let { "cockpit-$it.apk" } ?: AndroidApkStore.APK
	}
}

@Schema(description = "Version of the Android app offered for download. The version fields are null when unknown.")
data class AndroidAppVersion(
	@field:Schema(description = "`versionName` of the APK", example = "0.1.0")
	val versionName: String?,
	@field:Schema(description = "`versionCode` of the APK; higher is newer", example = "42")
	val versionCode: Long?,
	@field:Schema(description = "Size of the APK in bytes", requiredMode = RequiredMode.REQUIRED)
	val size: Long,
	@field:Schema(description = "When the APK was deployed on the server", requiredMode = RequiredMode.REQUIRED)
	val updatedAt: Instant,
)

/** `404` when no APK is deployed. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "404",
	description = "No APK is deployed on this server",
	content = [Content(mediaType = "application/problem+json", schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class NoApkResponse
