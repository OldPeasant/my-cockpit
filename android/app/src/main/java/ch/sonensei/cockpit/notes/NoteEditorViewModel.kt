package ch.sonensei.cockpit.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

/** Where the text stands against the server. */
enum class SaveStatus { SAVED, UNSAVED, SAVING, OFFLINE, FAILED }

/** A one-off problem shown to the user. */
enum class NoteEditorMessage { LOAD_OFFLINE, LOAD_FAILED, PHOTO_UNREADABLE, PHOTO_OFFLINE, PHOTO_FAILED }

/**
 * The note was changed elsewhere while it was edited here: the server refused the save. [current] is the server's
 * version, `null` when the note was deleted.
 */
data class NoteConflict(
    val current: Note?,
)

data class NoteEditorUiState(
    /** The note as last loaded or saved; `null` for a new note that was not saved yet. */
    val note: Note? = null,
    val title: String = "",
    val body: String = "",
    /** Loading an existing note; nothing can be edited yet. */
    val loading: Boolean = false,
    /**
     * An existing note was asked for and is not loaded (yet, or its load failed): nothing can be edited, so no edit
     * or photo can end up in a separate new note.
     */
    val unloaded: Boolean = false,
    /** Plain-text editor instead of the rendered Markdown. */
    val editing: Boolean = false,
    val status: SaveStatus = SaveStatus.SAVED,
    val conflict: NoteConflict? = null,
    val uploading: Boolean = false,
    val message: NoteEditorMessage? = null,
    /** The note was deleted elsewhere and the user chose the server's side: the editor closes. */
    val closed: Boolean = false,
) {
    val draft: NoteDraft get() = NoteDraft(title, body)

    /** Editing is possible: the note is loaded and no conflict waits for a decision. */
    val editable: Boolean get() = !loading && !unloaded && conflict == null && !closed
}

/**
 * One note: the rendered Markdown or the plain editor, saved automatically [AUTOSAVE_DELAY_MS] after the last change.
 * Saves run one at a time, each over the version the previous one returned. A save the server refuses because the
 * note changed elsewhere stops the autosave until the user keeps their text or takes the server's.
 */
@HiltViewModel
class NoteEditorViewModel
    @Inject
    constructor(
        private val notes: NotesRepository,
        images: NoteImages,
    ) : ViewModel() {
        val imageLoader = images.loader

        private val state = MutableStateFlow(NoteEditorUiState())
        val uiState: StateFlow<NoteEditorUiState> = state.asStateFlow()

        private val saving = Mutex()
        private var autosave: Job? = null
        private var opened = false
        private var opening: Any? = null

        /**
         * Image Markdown uploaded while a conflict came up, with where it goes: inserted once the user decided. Only one
         * photo uploads at a time, and none starts during a conflict, so there is at most one.
         */
        private var pendingImage: Pair<String, Int?>? = null

        /**
         * Loads note [id], or starts a new note (`null`, opened in the editor). [opening] identifies one opening of the
         * editor: calls with the same one (e.g. after a configuration change) do nothing. The view model outlives the
         * editor, so when the same note is opened again, it is loaded again unless something is still to be saved or
         * decided here (that text then stays, and goes on to the server).
         */
        fun open(
            id: UUID?,
            opening: Any? = null,
        ) {
            if (opened) {
                if (opening == null || opening == this.opening) return
                this.opening = opening
                if (id != null && !pending()) {
                    pendingImage = null
                    state.value = NoteEditorUiState(loading = true, unloaded = true)
                    viewModelScope.launch { load(id) }
                }
                return
            }
            opened = true
            this.opening = opening
            if (id == null) {
                state.update { it.copy(editing = true) }
                return
            }
            state.update { it.copy(loading = true, unloaded = true) }
            viewModelScope.launch { load(id) }
        }

        /** Something here is not on the server yet, or waits for the user: the shown note must not be replaced. */
        private fun pending(): Boolean {
            val s = state.value
            if (s.closed) return false
            return s.loading || s.uploading || s.conflict != null || s.status != SaveStatus.SAVED || saving.isLocked
        }

        /** Loads the note again after a failed load. */
        fun retryLoad(id: UUID) {
            if (state.value.loading || !state.value.unloaded) return
            state.update { it.copy(loading = true, message = null) }
            viewModelScope.launch { load(id) }
        }

        private suspend fun load(id: UUID) {
            val result = notes.get(id)
            state.update {
                when (result) {
                    is NotesResult.Ok ->
                        it.copy(
                            note = result.value,
                            title = result.value.title.orEmpty(),
                            body = result.value.bodyMd,
                            loading = false,
                            unloaded = false,
                        )
                    NotesResult.Offline -> it.copy(loading = false, message = NoteEditorMessage.LOAD_OFFLINE)
                    is NotesResult.Failed -> it.copy(loading = false, message = NoteEditorMessage.LOAD_FAILED)
                }
            }
        }

        fun setEditing(editing: Boolean) {
            if (editing && !state.value.editable) return
            state.update { it.copy(editing = editing) }
            if (!editing) saveSoon(0)
        }

        fun setTitle(title: String) = change { it.copy(title = title) }

        fun setBody(body: String) = change { it.copy(body = body) }

        private fun change(edit: (NoteEditorUiState) -> NoteEditorUiState) {
            if (!state.value.editable) return
            state.update { edit(it).let { s -> s.copy(status = if (s.isSaved()) SaveStatus.SAVED else SaveStatus.UNSAVED) } }
            saveSoon(AUTOSAVE_DELAY_MS)
        }

        /**
         * Saves now, e.g. when the editor is left or after a failed save. The returned job ends once the text is saved
         * (or the save failed), so the list can reload after it.
         */
        fun saveNow(): Job {
            autosave?.cancel()
            autosave = null
            return viewModelScope.launch { save() }
        }

        /**
         * Saves after [delayMs], unless another change comes first. Only the wait is cancelled: a save that started
         * runs to its end (cancelling it could leave the note saved without its new version, so the next save would
         * conflict with itself). Text typed meanwhile is saved by its own autosave, over the new version.
         */
        private fun saveSoon(delayMs: Long) {
            autosave?.cancel()
            autosave =
                viewModelScope.launch {
                    delay(delayMs)
                    viewModelScope.launch { save() }
                }
        }

        /** Saves the current text if it differs from the saved note; returns whether the save went through. */
        private suspend fun save(): Boolean =
            saving.withLock {
                val before = state.value
                if (before.conflict != null || before.closed || before.loading || before.unloaded) return false
                // A new note is only created once it has content.
                if (before.isSaved()) return true
                state.update { it.copy(status = SaveStatus.SAVING) }
                val result = notes.save(before.note, before.draft)
                state.update {
                    when (result) {
                        is SaveResult.Saved -> {
                            val saved = it.copy(note = result.note)
                            saved.copy(status = if (saved.isSaved()) SaveStatus.SAVED else SaveStatus.UNSAVED)
                        }
                        is SaveResult.Conflict ->
                            it.copy(
                                conflict = NoteConflict(result.current),
                                status = SaveStatus.UNSAVED,
                                editing = false,
                            )
                        SaveResult.Offline -> it.copy(status = SaveStatus.OFFLINE)
                        is SaveResult.Failed -> it.copy(status = SaveStatus.FAILED)
                    }
                }
                result is SaveResult.Saved
            }

        /** Resolves the conflict with the text written here: it is saved over the server's version (or as a new note). */
        fun keepMine() {
            val conflict = state.value.conflict ?: return
            state.update { it.copy(note = conflict.current, conflict = null, status = SaveStatus.UNSAVED) }
            insertPendingImage(atCursor = true)
            saveSoon(0)
        }

        /** Resolves the conflict with the server's version: the text written here is dropped. */
        fun useTheirs() {
            val conflict = state.value.conflict ?: return
            val current = conflict.current
            state.update {
                if (current == null) {
                    it.copy(conflict = null, closed = true)
                } else {
                    it.copy(
                        note = current,
                        title = current.title.orEmpty(),
                        body = current.bodyMd,
                        conflict = null,
                        status = SaveStatus.SAVED,
                    )
                }
            }
            if (current == null) {
                pendingImage = null
            } else if (insertPendingImage(atCursor = false)) {
                saveSoon(0)
            }
        }

        /**
         * Inserts the image uploaded while the conflict came up: at its cursor when the text is still the one it was added
         * to, else at the end. Returns whether there was one.
         */
        private fun insertPendingImage(atCursor: Boolean): Boolean {
            val (image, at) = pendingImage ?: return false
            pendingImage = null
            state.update { it.copy(body = insertImage(it.body, image, if (atCursor) at else null), status = SaveStatus.UNSAVED) }
            return true
        }

        /**
         * Uploads [photo] (`null`: it could not be read) and inserts its Markdown image at [at] in the body (the end
         * when `null`), on a line of its own. A new note is saved first, as the image belongs to a note.
         */
        fun addPhoto(
            photo: NotePhoto?,
            at: Int? = null,
        ) {
            if (photo == null) {
                state.update { it.copy(message = NoteEditorMessage.PHOTO_UNREADABLE) }
                return
            }
            if (!state.value.editable || state.value.uploading) return
            state.update { it.copy(uploading = true, message = null) }
            viewModelScope.launch {
                val note = state.value.note ?: create()
                if (note == null) {
                    state.update { it.copy(uploading = false, message = NoteEditorMessage.PHOTO_OFFLINE) }
                    return@launch
                }
                when (val result = notes.uploadImage(note.id, photo)) {
                    is NotesResult.Ok -> {
                        state.update { it.copy(uploading = false) }
                        when {
                            state.value.editable -> {
                                state.update { it.copy(body = insertImage(it.body, result.value, at), status = SaveStatus.UNSAVED) }
                                saveSoon(0)
                            }
                            // A conflict came up meanwhile: the image is added once the user decided.
                            state.value.conflict != null -> pendingImage = result.value to at
                        }
                    }
                    NotesResult.Offline -> state.update { it.copy(uploading = false, message = NoteEditorMessage.PHOTO_OFFLINE) }
                    is NotesResult.Failed -> state.update { it.copy(uploading = false, message = NoteEditorMessage.PHOTO_FAILED) }
                }
            }
        }

        /** Creates the new note with the current text (also when it is empty); `null` when that failed. */
        private suspend fun create(): Note? =
            saving.withLock {
                state.value.note?.let { return it }
                val result = notes.save(null, state.value.draft) as? SaveResult.Saved ?: return null
                state.update {
                    it.copy(note = result.note).let { s ->
                        s.copy(status = if (s.isSaved()) SaveStatus.SAVED else SaveStatus.UNSAVED)
                    }
                }
                result.note
            }

        fun dismissMessage() = state.update { it.copy(message = null) }

        /** The text equals the saved note (for a new note: there is nothing to save yet). */
        private fun NoteEditorUiState.isSaved(): Boolean {
            val saved = note ?: return title.isBlank() && body.isEmpty()
            return title.trim() == saved.title.orEmpty() && body == saved.bodyMd
        }

        companion object {
            const val AUTOSAVE_DELAY_MS = 1_000L

            /** [body] with [image] inserted at [at] (clamped; `null`: the end), on a line of its own. */
            fun insertImage(
                body: String,
                image: String,
                at: Int?,
            ): String {
                val index = (at ?: body.length).coerceIn(0, body.length)
                val before = body.substring(0, index)
                val after = body.substring(index)
                val lead = if (before.isEmpty() || before.endsWith("\n")) "" else "\n"
                val trail = if (after.startsWith("\n")) "" else "\n"
                return before + lead + image + trail + after
            }
        }
    }
