package ch.sonensei.cockpit.settings

import ch.sonensei.cockpit.capture.queue.CaptureUploader
import org.junit.Assert.assertEquals
import org.junit.Test

/** The texts the capture queue stores are recognized, so the settings screen can show them in the user's language. */
class CaptureFailureTest {
    @Test
    fun `the uploader's fixed texts are recognized`() {
        assertEquals(CaptureFailure.Offline, CaptureFailure.of(CaptureUploader.OFFLINE))
        assertEquals(CaptureFailure.NotLoggedIn, CaptureFailure.of(CaptureUploader.NOT_LOGGED_IN))
        assertEquals(CaptureFailure.AttachmentMissing, CaptureFailure.of(CaptureUploader.ATTACHMENT_MISSING))
        assertEquals(CaptureFailure.Unexpected, CaptureFailure.of(CaptureUploader.UNEXPECTED_ERROR))
    }

    @Test
    fun `server errors and rejections keep their HTTP status`() {
        assertEquals(CaptureFailure.ServerError(503), CaptureFailure.of("Server error (HTTP 503)"))
        assertEquals(CaptureFailure.ServerError(429), CaptureFailure.of("Server error (HTTP 429)"))
        assertEquals(CaptureFailure.Rejected(422), CaptureFailure.of("Rejected by the server (HTTP 422)"))
    }

    @Test
    fun `anything else is shown as stored`() {
        assertEquals(CaptureFailure.Other("Something else"), CaptureFailure.of("Something else"))
        assertEquals(CaptureFailure.Other("Server error (HTTP 503) later"), CaptureFailure.of("Server error (HTTP 503) later"))
        assertEquals(CaptureFailure.Other("Server error (HTTP abc)"), CaptureFailure.of("Server error (HTTP abc)"))
        assertEquals(
            CaptureFailure.Other("Rejected by the server (HTTP 99999999999)"),
            CaptureFailure.of("Rejected by the server (HTTP 99999999999)"),
        )
    }
}
