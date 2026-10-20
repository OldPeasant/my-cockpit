package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.files.infra.fileIcon
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class FileIconTests {
	@ParameterizedTest
	@CsvSource(
		"image/png, image",
		"IMAGE/JPEG, image",
		"application/pdf, picture_as_pdf",
		"'application/pdf; name=x.pdf', picture_as_pdf",
		"audio/mpeg, audio_file",
		"video/mp4, video_file",
		"'text/plain; charset=utf-8', description",
		"application/zip, draft",
		"application/octet-stream, draft",
		"'', draft",
	)
	fun `the icon follows the mime type, ignoring case and parameters`(
		mime: String,
		icon: String,
	) {
		fileIcon(mime) shouldBe icon
	}
}
