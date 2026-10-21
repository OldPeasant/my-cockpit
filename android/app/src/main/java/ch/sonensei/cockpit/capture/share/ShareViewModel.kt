package ch.sonensei.cockpit.capture.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.capture.queue.CaptureAttachment
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

enum class ShareError { EMPTY_TEXT, TOO_MANY_FILES, TEXT_TOO_LONG, NOTES_TOO_LONG, UNREADABLE_FILE, FAILED }

data class SharedFile(
    val uri: Uri,
    val name: String,
)

data class ShareUiState(
    val text: String = "",
    val notes: String = "",
    val files: List<SharedFile> = emptyList(),
    /** The names of the shared files are still being read: saving now would drop the files. */
    val loadingFiles: Boolean = false,
    val saving: Boolean = false,
    val error: ShareError? = null,
    /** The capture is queued, with its own copies of the files: the sheet can close. */
    val saved: Boolean = false,
) {
    val canSave: Boolean get() = !loadingFiles && !saving && !saved && text.isNotBlank()
}

/** The capture sheet of the share target: edit the shared text, then queue it with copies of the shared files. */
@HiltViewModel
class ShareViewModel
    @Inject
    constructor(
        private val queue: CaptureQueue,
        @ApplicationContext context: Context,
    ) : ViewModel() {
        private val resolver: ContentResolver = context.contentResolver
        private val state = MutableStateFlow(ShareUiState())
        val uiState: StateFlow<ShareUiState> = state.asStateFlow()
        private var loaded = false

        /** Fills the sheet from the share once; later calls (the activity recreated) keep the user's edits. */
        fun load(content: SharedContent) {
            if (loaded) return
            loaded = true
            state.update { it.copy(text = content.title, notes = content.notes.orEmpty(), loadingFiles = content.uris.isNotEmpty()) }
            viewModelScope.launch {
                val files =
                    withContext(Dispatchers.IO) {
                        content.uris.mapIndexed { i, uri -> SharedFile(uri, displayName(uri) ?: "attachment-${i + 1}") }
                    }
                state.update { s -> s.copy(files = files, loadingFiles = false, text = s.text.ifBlank { defaultTitle(files) }) }
            }
        }

        fun onTextChange(value: String) = state.update { it.copy(text = value, error = null) }

        fun onNotesChange(value: String) = state.update { it.copy(notes = value, error = null) }

        /** Queues the capture; the attachments are copied into the queue's storage before [ShareUiState.saved] is set. */
        fun save() {
            val form = state.value
            if (!form.canSave) {
                if (!form.loadingFiles && !form.saving && !form.saved) state.update { it.copy(error = ShareError.EMPTY_TEXT) }
                return
            }
            if (form.files.size > CaptureQueue.MAX_ATTACHMENTS) {
                state.update { it.copy(error = ShareError.TOO_MANY_FILES) }
                return
            }
            if (form.notes.length > MAX_NOTES_LENGTH) {
                state.update { it.copy(error = ShareError.NOTES_TOO_LONG) }
                return
            }
            state.update { it.copy(saving = true, error = null) }
            viewModelScope.launch {
                val error =
                    try {
                        queue.capture(form.text, form.notes, form.files.map { CaptureAttachment(it.uri, it.name) })
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: IllegalArgumentException) {
                        if (form.text.isBlank()) ShareError.EMPTY_TEXT else ShareError.TEXT_TOO_LONG
                    } catch (_: IOException) {
                        ShareError.UNREADABLE_FILE
                    } catch (_: Exception) {
                        ShareError.FAILED
                    }
                state.update { it.copy(saving = false, error = error, saved = error == null) }
            }
        }

        private fun defaultTitle(files: List<SharedFile>): String =
            when (files.size) {
                0 -> ""
                1 -> files.single().name
                else ->
                    files.take(TITLE_FILES).joinToString(", ") { it.name } +
                        if (files.size > TITLE_FILES) " +${files.size - TITLE_FILES}" else ""
            }

        private fun displayName(uri: Uri): String? =
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }

        companion object {
            /**
             * The server accepts at most this many characters of notes (`TaskService.MAX_NOTES_LENGTH`) and rejects the
             * capture for good otherwise, so a longer share is refused before it is queued.
             */
            const val MAX_NOTES_LENGTH = 100_000

            /** At most this many file names make up the default title of a share without text. */
            private const val TITLE_FILES = 3
        }
    }
