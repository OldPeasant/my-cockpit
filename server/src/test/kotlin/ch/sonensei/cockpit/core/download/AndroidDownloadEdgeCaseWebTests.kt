package ch.sonensei.cockpit.core.download

import ch.sonensei.cockpit.core.auth.TestCockpitUser
import ch.sonensei.cockpit.core.auth.cockpitUser
import ch.sonensei.cockpit.core.download.api.AndroidDownloadController
import ch.sonensei.cockpit.core.download.infra.AndroidApkStore
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/** Edge cases of `/download/android`: restricted sessions, HEAD, caching validators, replacement and byte ranges. */
@SpringBootTest
@AutoConfigureMockMvc
class AndroidDownloadEdgeCaseWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val store: AndroidApkStore,
) : PostgresTestBase() {
	private val apkBytes = "PK\u0003\u0004 fictitious apk for edge cases".toByteArray()
	private val modified = Instant.parse("2026-10-24T08:15:30Z")

	@BeforeEach
	@AfterEach
	fun reset() {
		store.apk.deleteIfExists()
		store.metadata.deleteIfExists()
	}

	private fun deployApk(
		bytes: ByteArray = apkBytes,
		at: Instant = modified,
	) {
		store.directory.createDirectories()
		store.apk.writeBytes(bytes)
		Files.setLastModifiedTime(store.apk, FileTime.from(at))
	}

	private fun deployMetadata(
		versionName: String,
		versionCode: Int,
	) {
		store.metadata.writeText(
			"""{"version": 3, "elements": [{"type": "SINGLE", "versionCode": $versionCode, "versionName": "$versionName"}]}""",
		)
	}

	@Test
	fun `a session that must enroll TOTP again gets 403`() {
		deployApk()
		deployMetadata("1.4.0", 42)
		val enrolling = cockpitUser(TestCockpitUser.principal.copy(totpEnrollmentRequired = true))

		withClue("GET $PATH") { mockMvc.get(PATH) { with(enrolling) }.andExpect { status { isForbidden() } } }
		withClue("GET $PATH/version") { mockMvc.get("$PATH/version") { with(enrolling) }.andExpect { status { isForbidden() } } }
		withClue("HEAD $PATH") { mockMvc.head(PATH) { with(enrolling) }.andExpect { status { isForbidden() } } }
	}

	@Test
	fun `a 403 for an enrolling session does not leak the version`() {
		deployApk()
		deployMetadata("1.4.0", 42)
		val enrolling = cockpitUser(TestCockpitUser.principal.copy(totpEnrollmentRequired = true))

		mockMvc.get(PATH) { with(enrolling) }.andExpect {
			status { isForbidden() }
			header { doesNotExist(AndroidDownloadController.VERSION_HEADER) }
			header { doesNotExist(HttpHeaders.CONTENT_DISPOSITION) }
		}
	}

	@Test
	fun `an anonymous request does not leak the version or the APK`() {
		deployApk()
		deployMetadata("1.4.0", 42)

		val response =
			mockMvc
				.get(PATH)
				.andExpect {
					status { isUnauthorized() }
					header { doesNotExist(AndroidDownloadController.VERSION_HEADER) }
				}.andReturn()
				.response

		response.contentAsByteArray.size shouldBe 0
	}

	@Test
	fun `HEAD answers with the headers of the download`() {
		deployApk()
		deployMetadata("1.4.0", 42)

		mockMvc.head(PATH) { with(cockpitUser()) }.andExpect {
			status { isOk() }
			header { string(AndroidDownloadController.VERSION_HEADER, "1.4.0") }
			header { string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cockpit-1.4.0.apk\"") }
			header { exists(HttpHeaders.ETAG) }
		}
	}

	@Test
	fun `the download may only be cached privately and must be revalidated`() {
		deployApk()

		mockMvc.get(PATH) { with(cockpitUser()) }.andExpect {
			status { isOk() }
			header { string(HttpHeaders.CACHE_CONTROL, "no-cache, private") }
			header { dateValue(HttpHeaders.LAST_MODIFIED, modified.toEpochMilli()) }
		}
	}

	@Test
	fun `answers 304 to If-Modified-Since the deployment`() {
		deployApk()
		val since = DateTimeFormatter.RFC_1123_DATE_TIME.format(modified.atZone(ZoneOffset.UTC))

		mockMvc
			.get(PATH) {
				with(cockpitUser())
				header(HttpHeaders.IF_MODIFIED_SINCE, since)
			}.andExpect { status { isNotModified() } }
	}

	@Test
	fun `a replaced APK is downloaded again despite the old ETag`() {
		deployApk()
		deployMetadata("1.4.0", 42)
		val oldETag =
			mockMvc
				.get(PATH) { with(cockpitUser()) }
				.andReturn()
				.response
				.getHeader(HttpHeaders.ETAG)!!

		val newer = "PK\u0003\u0004 a newer fictitious apk, one release later".toByteArray()
		deployApk(newer, modified.plusSeconds(86_400))
		deployMetadata("1.5.0", 43)

		val response =
			mockMvc
				.get(PATH) {
					with(cockpitUser())
					header(HttpHeaders.IF_NONE_MATCH, oldETag)
				}.andExpect {
					status { isOk() }
					header { string(AndroidDownloadController.VERSION_HEADER, "1.5.0") }
					header { string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cockpit-1.5.0.apk\"") }
				}.andReturn()
				.response

		response.getHeader(HttpHeaders.ETAG) shouldNotBe oldETag
		response.contentAsByteArray shouldBe newer
		mockMvc.get("$PATH/version") { with(cockpitUser()) }.andExpect {
			jsonPath("$.versionName") { value("1.5.0") }
			jsonPath("$.versionCode") { value(43) }
			jsonPath("$.size") { value(newer.size) }
		}
	}

	@Test
	fun `a removed APK answers 404 again`() {
		deployApk()
		mockMvc.get(PATH) { with(cockpitUser()) }.andExpect { status { isOk() } }

		store.apk.deleteIfExists()

		mockMvc.get(PATH) { with(cockpitUser()) }.andExpect { status { isNotFound() } }
		mockMvc.get("$PATH/version") { with(cockpitUser()) }.andExpect { status { isNotFound() } }
	}

	@Test
	fun `metadata without an APK still answers 404`() {
		store.directory.createDirectories()
		deployMetadata("1.4.0", 42)

		mockMvc.get(PATH) { with(cockpitUser()) }.andExpect { status { isNotFound() } }
		mockMvc.get("$PATH/version") { with(cockpitUser()) }.andExpect { status { isNotFound() } }
	}

	@Test
	fun `serves a byte range, so an interrupted download can resume`() {
		deployApk()

		val response =
			mockMvc
				.get(PATH) {
					with(cockpitUser())
					header(HttpHeaders.RANGE, "bytes=0-3")
				}.andExpect {
					status { isPartialContent() }
					header { string(HttpHeaders.CONTENT_RANGE, "bytes 0-3/${apkBytes.size}") }
					header { longValue(HttpHeaders.CONTENT_LENGTH, 4) }
				}.andReturn()
				.response

		response.contentAsByteArray shouldBe apkBytes.copyOfRange(0, 4)
	}

	@Test
	fun `answers 416 to a range beyond the end of the APK`() {
		deployApk()

		mockMvc
			.get(PATH) {
				with(cockpitUser())
				header(HttpHeaders.RANGE, "bytes=${apkBytes.size + 100}-")
			}.andExpect {
				status { isRequestedRangeNotSatisfiable() }
				header { string(HttpHeaders.CONTENT_RANGE, "bytes */${apkBytes.size}") }
			}
	}

	companion object {
		private const val PATH = AndroidDownloadController.PATH
	}
}
