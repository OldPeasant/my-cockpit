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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject

enum class ShareError { EMPTY_TEXT, TOO_MANY_FILES, TEXT_TOO_LONG, NOTES_TOO_LONG, UNREADABLE_FILE, FAILED }

/** Where a shared URL goes (SPEC.md §8.4): a task in the Inbox or a bookmark in the read-later list. */
enum class ShareDestination { INBOX, READ_LATER }

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
    /** The shared web page: when set, the sheet offers [ShareDestination.READ_LATER] next to the Inbox. */
    val url: String? = null,
    val destination: ShareDestination = ShareDestination.INBOX,
    /** The syntax recognized in [text], null while nothing is typed. */
    val preview: CapturePreview? = null,
    /** Counts the captures saved with "save and add another": each one clears the sheet for the next capture. */
    val savedAndCleared: Int = 0,
    /** Whether the capture counted last by [savedAndCleared] went to the read-later list. */
    val lastSavedReadLater: Boolean = false,
) {
    /** A shared web page can go to the read-later list, unless files were attached: files cannot become a bookmark. */
    val offersReadLater: Boolean get() = url != null && files.isEmpty()

    val readLater: Boolean get() = offersReadLater && destination == ShareDestination.READ_LATER

    /** A read-later bookmark needs no title: the server takes the page's one. */
    val canSave: Boolean get() = !loadingFiles && !saving && !saved && (readLater || text.isNotBlank())
}

/**
 * The capture sheet of the share target and of the empty capture: edit the text (with a live preview of the capture
 * syntax), attach files, then queue it with copies of the files.
 */
@HiltViewModel
class ShareViewModel internal constructor(
    private val queue: CaptureQueue,
    context: Context,
    vocabularies: Flow<CaptureVocabulary> = flowOf(CaptureVocabulary.EMPTY),
    /** The device's date, for `!today` and `!tomorrow`: the queue pins them to the same date when it stores a capture. */
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    @Inject
    constructor(
        queue: CaptureQueue,
        @ApplicationContext context: Context,
        vocabulary: CaptureVocabularySource,
    ) : this(queue, context, vocabulary.observe())

    private val resolver: ContentResolver = context.contentResolver
    private val state = MutableStateFlow(ShareUiState())
    val uiState: StateFlow<ShareUiState> = state.asStateFlow()
    private var loaded = false
    private var vocabulary = CaptureVocabulary.EMPTY

    init {
        viewModelScope.launch {
            vocabularies.collect { value ->
                vocabulary = value
                state.update { it.copy(preview = preview(it.text)) }
            }
        }
    }

    private fun preview(text: String): CapturePreview? = if (text.isBlank()) null else CaptureSyntax.parse(text, today(), vocabulary)

    /** Fills the sheet from the share once; later calls (the activity recreated) keep the user's edits. */
    fun load(content: SharedContent) {
        if (loaded) return
        loaded = true
        state.update { it.withText(content.title).copy(notes = content.notes.orEmpty(), url = content.url) }
        // Without files the state is complete already: no file names to read.
        if (content.uris.isEmpty()) return
        startFileLoad()
        viewModelScope.launch {
            val files =
                withContext(Dispatchers.IO) {
                    content.uris.mapIndexed { i, uri -> SharedFile(uri, displayName(uri) ?: "attachment-${i + 1}") }
                }
            state.update { s ->
                s.withText(s.text.ifBlank { defaultTitle(files) }).copy(files = (files + s.files).distinctBy { it.uri })
            }
            endFileLoad()
        }
    }

    /**
     * Adds files the user picked with the system picker. Like shared files they are copied into the queue's storage
     * only on save, while the picker's read grant is valid; a file already attached is not added twice.
     */
    fun addFiles(uris: List<Uri>) {
        val form = state.value
        if (uris.isEmpty() || form.saving || form.saved) return
        startFileLoad()
        viewModelScope.launch {
            val picked =
                withContext(Dispatchers.IO) {
                    val offset = state.value.files.size
                    uris.distinct().mapIndexed { i, uri -> SharedFile(uri, displayName(uri) ?: "attachment-${offset + i + 1}") }
                }
            state.update { s ->
                val files = (s.files + picked).distinctBy { it.uri }
                val error = if (files.size > CaptureQueue.MAX_ATTACHMENTS) ShareError.TOO_MANY_FILES else s.error
                s.withText(s.text.ifBlank { defaultTitle(picked) }).copy(files = files, error = error)
            }
            endFileLoad()
        }
    }

    /** File names still being read (the share's and picked ones): saving waits until all are in. Main thread only. */
    private var fileLoads = 0

    private fun startFileLoad() {
        fileLoads++
        state.update { it.copy(loadingFiles = true) }
    }

    private fun endFileLoad() {
        fileLoads--
        state.update { it.copy(loadingFiles = fileLoads > 0) }
    }

    /** Removes an attachment before saving. */
    fun removeFile(uri: Uri) =
        state.update { s ->
            if (s.saving || s.saved) {
                s
            } else {
                val files = s.files.filterNot { it.uri == uri }
                val error = if (s.error == ShareError.TOO_MANY_FILES && files.size <= CaptureQueue.MAX_ATTACHMENTS) null else s.error
                s.copy(files = files, error = error)
            }
        }

    fun onTextChange(value: String) = state.update { it.withText(value).copy(error = null) }

    private fun ShareUiState.withText(value: String) = copy(text = value, preview = preview(value))

    fun onNotesChange(value: String) = state.update { it.copy(notes = value, error = null) }

    fun onDestinationChange(value: ShareDestination) =
        state.update { if (it.url == null || it.saving || it.saved) it else it.copy(destination = value, error = null) }

    /**
     * Queues the capture; the attachments are copied into the queue's storage before [ShareUiState.saved] is set.
     * With [addAnother] the sheet stays open instead: it is cleared for the next capture and
     * [ShareUiState.savedAndCleared] counts up.
     */
    fun save(addAnother: Boolean = false) {
        val form = state.value
        if (!form.canSave) {
            if (!form.loadingFiles && !form.saving && !form.saved) state.update { it.copy(error = ShareError.EMPTY_TEXT) }
            return
        }
        if (form.url != null && form.readLater) {
            saveReadLater(form, form.url, addAnother)
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
            finish(error, addAnother)
        }
    }

    /** Ends a save: the error to fix, the sheet cleared for the next capture, or [ShareUiState.saved] to close it. */
    private fun finish(
        error: ShareError?,
        addAnother: Boolean,
    ) = state.update {
        when {
            error != null -> it.copy(saving = false, error = error)
            addAnother -> ShareUiState(savedAndCleared = it.savedAndCleared + 1, lastSavedReadLater = it.readLater)
            else -> it.copy(saving = false, saved = true)
        }
    }

    /** Queues the URL for the read-later list; the notes are not part of a bookmark. */
    private fun saveReadLater(
        form: ShareUiState,
        url: String,
        addAnother: Boolean,
    ) {
        state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            // A title that is only the URL (a bare link was shared) would hide the page's real title.
            val title = form.text.trim().takeUnless { it.isEmpty() || it == url }
            val error =
                try {
                    queue.readLater(url, title)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ShareError.FAILED
                }
            finish(error, addAnother)
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
