package ch.sonensei.cockpit.core.download

import ch.sonensei.cockpit.core.auth.cockpitUser
import ch.sonensei.cockpit.core.download.api.AndroidDownloadController
import ch.sonensei.cockpit.core.download.infra.AndroidApkStore
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.head
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

@SpringBootTest
@AutoConfigureMockMvc
class AndroidDownloadWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val store: AndroidApkStore,
) : PostgresTestBase() {
	private val apkBytes = "PK\u0003\u0004 fictitious apk".toByteArray()
	private val modified = Instant.parse("2026-10-24T08:15:30Z")

	@BeforeEach
	@AfterEach
	fun reset() {
		store.apk.deleteIfExists()
		store.metadata.deleteIfExists()
	}

	private fun deployApk() {
		store.directory.createDirectories()
		store.apk.writeBytes(apkBytes)
		Files.setLastModifiedTime(store.apk, FileTime.from(modified))
	}

	private fun deployMetadata(
		versionName: String = "1.4.0",
		versionCode: Int = 42,
	) {
		store.metadata.writeText(
			"""
			{"version": 3, "artifactType": {"type": "APK", "kind": "Directory"}, "applicationId": "ch.sonensei.cockpit",
			 "variantName": "release", "elements": [{"type": "SINGLE", "filters": [], "attributes": [],
			 "versionCode": $versionCode, "versionName": "$versionName", "outputFile": "app-release.apk"}], "elementType": "File"}
			""".trimIndent(),
		)
	}

	@Test
	fun `serves the APK with its version to a logged-in user`() {
		deployApk()
		deployMetadata()

		val response =
			mockMvc
				.get(PATH) { with(cockpitUser()) }
				.andExpect {
					status { isOk() }
					content { contentType(AndroidDownloadController.APK_MIME) }
					header { string(AndroidDownloadController.VERSION_HEADER, "1.4.0") }
					header { string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cockpit-1.4.0.apk\"") }
					header { longValue(HttpHeaders.CONTENT_LENGTH, apkBytes.size.toLong()) }
					header { exists(HttpHeaders.ETAG) }
				}.andReturn()
				.response

		response.contentAsByteArray shouldBe apkBytes
	}

	@Test
	fun `answers 304 when the APK did not change`() {
		deployApk()
		val eTag =
			mockMvc
				.get(PATH) { with(cockpitUser()) }
				.andReturn()
				.response
				.getHeader(HttpHeaders.ETAG)!!

		mockMvc
			.get(PATH) {
				with(cockpitUser())
				header(HttpHeaders.IF_NONE_MATCH, eTag)
			}.andExpect { status { isNotModified() } }
	}

	@Test
	fun `reports the version as JSON`() {
		deployApk()
		deployMetadata()

		mockMvc.get("$PATH/version") { with(cockpitUser()) }.andExpect {
			status { isOk() }
			jsonPath("$.versionName") { value("1.4.0") }
			jsonPath("$.versionCode") { value(42) }
			jsonPath("$.size") { value(apkBytes.size) }
			jsonPath("$.updatedAt") { value("2026-10-24T08:15:30Z") }
		}
	}

	@Test
	fun `serves the APK without version when the metadata is missing or broken`() {
		deployApk()

		mockMvc.get(PATH) { with(cockpitUser()) }.andExpect {
			status { isOk() }
			header { doesNotExist(AndroidDownloadController.VERSION_HEADER) }
			header { string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cockpit.apk\"") }
		}

		store.metadata.writeText("{ not json")
		mockMvc.get("$PATH/version") { with(cockpitUser()) }.andExpect {
			status { isOk() }
			jsonPath("$.versionName") { value(null) }
			jsonPath("$.versionCode") { value(null) }
		}
	}

	@Test
	fun `keeps an unsafe version out of the file name`() {
		deployApk()
		deployMetadata(versionName = "1.0/../x")

		mockMvc.get(PATH) { with(cockpitUser()) }.andExpect {
			status { isOk() }
			header { string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cockpit.apk\"") }
		}
	}

	@Test
	fun `answers 404 problem details when no APK is deployed`() {
		mockMvc.get(PATH) { with(cockpitUser()) }.andExpect {
			status { isNotFound() }
			content { contentType("application/problem+json") }
		}
		mockMvc.get("$PATH/version") { with(cockpitUser()) }.andExpect { status { isNotFound() } }
	}

	@Test
	fun `needs a login`() {
		deployApk()

		mockMvc.get(PATH).andExpect { status { isUnauthorized() } }
		mockMvc.get("$PATH/version").andExpect { status { isUnauthorized() } }
		mockMvc.head(PATH).andExpect { status { isUnauthorized() } }
	}

	companion object {
		private const val PATH = AndroidDownloadController.PATH
	}
}
