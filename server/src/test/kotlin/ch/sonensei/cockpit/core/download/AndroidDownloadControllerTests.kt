package ch.sonensei.cockpit.core.download

import ch.sonensei.cockpit.core.download.api.AndroidDownloadController
import ch.sonensei.cockpit.core.download.infra.AndroidApk
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant

/** The pure helpers of [AndroidDownloadController]: the validator and the attachment file name. */
class AndroidDownloadControllerTests {
	private val modified = Instant.parse("2026-10-24T08:15:30Z")

	private fun apk(
		versionName: String? = "1.4.0",
		size: Long = 4096,
		modified: Instant = this.modified,
	) = AndroidApk(Path.of("android", "cockpit.apk"), size, modified, versionName, 42)

	@Test
	fun `the ETag is a weak validator from size and modification time in hex`() {
		AndroidDownloadController.eTag(apk()) shouldBe "W/\"1000-${modified.toEpochMilli().toString(16)}\""
	}

	@Test
	fun `the ETag changes when size or modification time change, not with the version`() {
		val base = AndroidDownloadController.eTag(apk())

		AndroidDownloadController.eTag(apk(size = 4097)) shouldNotBe base
		AndroidDownloadController.eTag(apk(modified = modified.plusMillis(1))) shouldNotBe base
		AndroidDownloadController.eTag(apk(versionName = "9.9.9")) shouldBe base
	}

	@Test
	fun `the file name carries a safe version`() {
		for (version in listOf("1.4.0", "0.1.0-SNAPSHOT", "1.4.0+build.7", "2026_10_25", "7")) {
			AndroidDownloadController.fileName(apk(versionName = version)) shouldBe "cockpit-$version.apk"
		}
	}

	@Test
	fun `the file name is cockpit apk without a version`() {
		AndroidDownloadController.fileName(apk(versionName = null)) shouldBe "cockpit.apk"
		AndroidDownloadController.fileName(apk(versionName = "")) shouldBe "cockpit.apk"
	}

	@Test
	fun `the file name drops a version that is unsafe in a file name or header`() {
		val unsafe = listOf("1.0/../x", "..\\x", "1.0 beta", "1.0\"x", "1.0;x", "1.0\r\nX-Evil: 1", "1.0é", "1.0\u0000")
		for (version in unsafe) {
			AndroidDownloadController.fileName(apk(versionName = version)) shouldBe "cockpit.apk"
		}
	}

	@Test
	fun `the file name allows a version of at most 64 characters`() {
		val longest = "1".repeat(64)

		AndroidDownloadController.fileName(apk(versionName = longest)) shouldBe "cockpit-$longest.apk"
		AndroidDownloadController.fileName(apk(versionName = longest + "1")) shouldBe "cockpit.apk"
	}
}
