package ch.sonensei.cockpit.core.download

import ch.sonensei.cockpit.core.download.infra.AndroidApk
import ch.sonensei.cockpit.core.download.infra.AndroidApkStore
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/** [AndroidApkStore] on a real directory: where it looks, and how it reads (or ignores) `output-metadata.json`. */
class AndroidApkStoreTests {
	@TempDir
	lateinit var dataDir: Path

	private val store by lazy { AndroidApkStore(dataDir, JsonMapper.builder().build()) }

	private val apkBytes = "PK\u0003\u0004 fictitious apk for store tests".toByteArray()
	private val modified = Instant.parse("2026-10-24T08:15:30Z")

	private fun deployApk() {
		store.directory.createDirectories()
		store.apk.writeBytes(apkBytes)
		Files.setLastModifiedTime(store.apk, FileTime.from(modified))
	}

	/** An AGP `output-metadata.json` whose first element is [element] (raw JSON members). */
	private fun deployMetadata(element: String) {
		store.metadata.writeText(
			"""
			{"version": 3, "artifactType": {"type": "APK", "kind": "Directory"}, "applicationId": "ch.sonensei.cockpit",
			 "variantName": "release", "elements": [{"type": "SINGLE", "filters": [], "attributes": [], $element,
			 "outputFile": "app-release.apk"}], "elementType": "File"}
			""".trimIndent(),
		)
	}

	private fun current(): AndroidApk = store.current().shouldNotBeNull()

	@Test
	fun `looks in the android folder of the data dir`() {
		store.directory shouldBe dataDir.toAbsolutePath().normalize().resolve("android")
		store.apk shouldBe store.directory.resolve("cockpit.apk")
		store.metadata shouldBe store.directory.resolve("output-metadata.json")
	}

	@Test
	fun `normalizes a data dir with dot segments`() {
		val messy = dataDir.resolve("sub").resolve("..")

		AndroidApkStore(messy, JsonMapper.builder().build()).directory shouldBe
			dataDir.toAbsolutePath().normalize().resolve("android")
	}

	@Test
	fun `has no APK when the android folder does not exist`() {
		store.current().shouldBeNull()
	}

	@Test
	fun `has no APK when the folder exists but the APK does not`() {
		store.directory.createDirectories()
		deployMetadata(""""versionCode": 1, "versionName": "0.1.0"""")

		store.current().shouldBeNull()
	}

	@Test
	fun `has no APK when cockpit apk is a directory`() {
		store.apk.createDirectories()

		store.current().shouldBeNull()
	}

	@Test
	fun `reports path, size and modification time of the APK`() {
		deployApk()

		val apk = current()

		apk.path shouldBe store.apk
		apk.size shouldBe apkBytes.size.toLong()
		apk.modified shouldBe modified
	}

	@Test
	fun `an empty APK file is still an APK of size 0`() {
		store.directory.createDirectories()
		store.apk.writeBytes(ByteArray(0))

		current().size shouldBe 0L
	}

	@Test
	fun `reads versionName and versionCode from the metadata`() {
		deployApk()
		deployMetadata(""""versionCode": 42, "versionName": "1.4.0"""")

		val apk = current()

		apk.versionName shouldBe "1.4.0"
		apk.versionCode shouldBe 42L
	}

	@Test
	fun `reads a versionCode beyond the Int range`() {
		deployApk()
		deployMetadata(""""versionCode": 2147483648, "versionName": "1.4.0"""")

		current().versionCode shouldBe 2_147_483_648L
	}

	@Test
	fun `uses the first element when the metadata lists several`() {
		deployApk()
		store.metadata.writeText(
			"""
			{"elements": [{"versionCode": 7, "versionName": "first"}, {"versionCode": 8, "versionName": "second"}]}
			""".trimIndent(),
		)

		val apk = current()

		apk.versionName shouldBe "first"
		apk.versionCode shouldBe 7L
	}

	@Test
	fun `has no version without metadata`() {
		deployApk()

		val apk = current()

		apk.versionName.shouldBeNull()
		apk.versionCode.shouldBeNull()
	}

	@Test
	fun `has no version when the metadata is a directory`() {
		deployApk()
		store.metadata.createDirectories()

		val apk = current()

		apk.versionName.shouldBeNull()
		apk.versionCode.shouldBeNull()
	}

	@Test
	fun `has no version when the metadata has no elements`() {
		deployApk()
		store.metadata.writeText("""{"version": 3, "applicationId": "ch.sonensei.cockpit"}""")

		current().versionName.shouldBeNull()
	}

	@Test
	fun `has no version when elements is empty or not an array`() {
		deployApk()
		for (elements in listOf("[]", "{}", "\"app-release.apk\"", "null")) {
			store.metadata.writeText("""{"elements": $elements}""")

			val apk = current()

			apk.versionName shouldBe null
			apk.versionCode shouldBe null
		}
	}

	@Test
	fun `has no version when the metadata is not an object`() {
		deployApk()
		for (content in listOf("[]", "null", "42", "\"1.4.0\"")) {
			store.metadata.writeText(content)

			current().versionName shouldBe null
		}
	}

	@Test
	fun `has no version when the metadata is broken JSON`() {
		deployApk()
		store.metadata.writeText("""{"elements": [{"versionCode": 42, "versionName": "1.4""")

		val apk = current()

		apk.versionName.shouldBeNull()
		apk.versionCode.shouldBeNull()
	}

	@Test
	fun `has no version when the metadata is still empty while it is copied`() {
		deployApk()
		store.metadata.writeText("")

		val apk = current()

		apk.versionName.shouldBeNull()
		apk.versionCode.shouldBeNull()
	}

	@Test
	fun `ignores a versionName that is not a string but keeps the versionCode`() {
		deployApk()
		deployMetadata(""""versionCode": 42, "versionName": 1.4""")

		val apk = current()

		apk.versionName.shouldBeNull()
		apk.versionCode shouldBe 42L
	}

	@Test
	fun `ignores a versionCode that is not an integer but keeps the versionName`() {
		for (code in listOf("\"42\"", "4.2", "true", "null", "{}")) {
			deployApk()
			deployMetadata(""""versionCode": $code, "versionName": "1.4.0"""")

			val apk = current()

			apk.versionName shouldBe "1.4.0"
			apk.versionCode shouldBe null
		}
	}

	@Test
	fun `keeps one field when the other is missing`() {
		deployApk()
		deployMetadata(""""versionName": "1.4.0"""")
		current().versionName shouldBe "1.4.0"
		current().versionCode.shouldBeNull()

		deployMetadata(""""versionCode": 42""")
		current().versionName.shouldBeNull()
		current().versionCode shouldBe 42L
	}

	@Test
	fun `reads both files again on every call, so a new deployment needs no restart`() {
		deployApk()
		deployMetadata(""""versionCode": 42, "versionName": "1.4.0"""")
		current().versionName shouldBe "1.4.0"

		val newer = "PK\u0003\u0004 a newer fictitious apk, a bit longer".toByteArray()
		store.apk.writeBytes(newer)
		Files.setLastModifiedTime(store.apk, FileTime.from(modified.plusSeconds(3600)))
		deployMetadata(""""versionCode": 43, "versionName": "1.5.0"""")

		val apk = current()
		apk.versionName shouldBe "1.5.0"
		apk.versionCode shouldBe 43L
		apk.size shouldBe newer.size.toLong()
		apk.modified shouldBe modified.plusSeconds(3600)
	}
}
