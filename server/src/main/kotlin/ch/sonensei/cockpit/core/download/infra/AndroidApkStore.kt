package ch.sonensei.cockpit.core.download.infra

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.isRegularFile

/**
 * The Android app offered for download (SPEC.md §11): `${COCKPIT_DATA_DIR}/android/cockpit.apk`, the release APK
 * built by CI (artifact `cockpit-android`). Its version comes from `output-metadata.json` next to it, the file the
 * Android Gradle plugin writes beside every APK and CI ships in the same artifact; without it the version is unknown.
 * Both files are read on every request, so replacing them takes effect without a restart.
 */
@Component
class AndroidApkStore(
	@Value("\${cockpit.data-dir}") dataDir: Path,
	private val jsonMapper: JsonMapper,
) {
	val directory: Path = dataDir.toAbsolutePath().normalize().resolve("android")
	val apk: Path = directory.resolve(APK)
	val metadata: Path = directory.resolve(METADATA)

	/** The APK on disk, or `null` when none was deployed. */
	fun current(): AndroidApk? {
		if (!apk.isRegularFile()) return null
		val (size, modified) =
			try {
				Files.size(apk) to Files.getLastModifiedTime(apk).toInstant()
			} catch (_: IOException) {
				return null // Deleted or replaced between the check and the read.
			}
		val version = readVersion()
		return AndroidApk(apk, size, modified, version?.first, version?.second)
	}

	/** `versionName` to `versionCode` of the first APK in [metadata], or `null` when it is missing or unreadable. */
	private fun readVersion(): Pair<String?, Long?>? {
		if (!metadata.isRegularFile()) return null
		return try {
			val element = jsonMapper.readTree(metadata.toFile())["elements"]?.get(0) ?: return null
			val name = element["versionName"]?.takeIf { it.isString }?.asString()
			val code = element["versionCode"]?.takeIf { it.isIntegralNumber }?.asLong()
			name to code
		} catch (e: JacksonException) {
			log.warn("Ignoring unreadable {}: {}", metadata, e.originalMessage)
			null
		}
	}

	companion object {
		const val APK = "cockpit.apk"
		const val METADATA = "output-metadata.json"
		private val log = LoggerFactory.getLogger(AndroidApkStore::class.java)
	}
}

/** The deployed APK: [path], its [size] in bytes, when it was [modified], and its version if known. */
data class AndroidApk(
	val path: Path,
	val size: Long,
	val modified: Instant,
	val versionName: String?,
	val versionCode: Long?,
)
