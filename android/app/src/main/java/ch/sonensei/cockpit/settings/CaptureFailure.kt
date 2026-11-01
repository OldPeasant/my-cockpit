package ch.sonensei.cockpit.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.capture.queue.CaptureUploader

/**
 * Why the last capture upload failed, read from the English text the capture queue stores (`CaptureUploader`), so the
 * settings screen can show it in the user's language. Unknown texts are shown as they are.
 */
sealed interface CaptureFailure {
    data object Offline : CaptureFailure

    data object NotLoggedIn : CaptureFailure

    data object AttachmentMissing : CaptureFailure

    data object Unexpected : CaptureFailure

    data class ServerError(
        val code: Int,
    ) : CaptureFailure

    data class Rejected(
        val code: Int,
    ) : CaptureFailure

    data class Other(
        val text: String,
    ) : CaptureFailure

    companion object {
        private val serverError = Regex("""Server error \(HTTP (\d+)\)""")
        private val rejected = Regex("""Rejected by the server \(HTTP (\d+)\)""")

        fun of(lastError: String): CaptureFailure =
            when (lastError) {
                CaptureUploader.OFFLINE -> Offline
                CaptureUploader.NOT_LOGGED_IN -> NotLoggedIn
                CaptureUploader.ATTACHMENT_MISSING -> AttachmentMissing
                CaptureUploader.UNEXPECTED_ERROR -> Unexpected
                else ->
                    code(serverError, lastError)?.let(::ServerError)
                        ?: code(rejected, lastError)?.let(::Rejected)
                        ?: Other(lastError)
            }

        private fun code(
            pattern: Regex,
            text: String,
        ): Int? =
            pattern
                .matchEntire(text)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
    }
}

/** The failure in the user's language. */
@Composable
fun captureFailureText(lastError: String): String =
    when (val failure = CaptureFailure.of(lastError)) {
        CaptureFailure.Offline -> stringResource(R.string.settings_capture_error_offline)
        CaptureFailure.NotLoggedIn -> stringResource(R.string.settings_capture_error_not_logged_in)
        CaptureFailure.AttachmentMissing -> stringResource(R.string.settings_capture_error_attachment_missing)
        CaptureFailure.Unexpected -> stringResource(R.string.settings_capture_error_unexpected)
        is CaptureFailure.ServerError -> stringResource(R.string.settings_capture_error_server, failure.code)
        is CaptureFailure.Rejected -> stringResource(R.string.settings_capture_error_rejected, failure.code)
        is CaptureFailure.Other -> failure.text
    }
