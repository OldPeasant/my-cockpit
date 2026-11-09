package ch.sonensei.cockpit.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.search.SearchHit
import ch.sonensei.cockpit.search.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Where the text stands against the server. */
enum class SaveStatus { SAVED, UNSAVED, SAVING, OFFLINE, FAILED }

/** A one-off problem shown to the user. */
enum class NoteEditorMessage {
    LOAD_OFFLINE,
    LOAD_FAILED,
    PHOTO_UNREADABLE,
    PHOTO_OFFLINE,
    PHOTO_FAILED,

    /** A pin, archive or delete did not get through: no connection. */
    CHANGE_OFFLINE,

    /** A pin, archive or delete was refused. */
    CHANGE_FAILED,

    /** A pin or archive was refused because the note changed elsewhere: its latest version is shown instead. */
    CHANGE_CONFLICT,
}

/**
 * The note was changed elsewhere while it was edited here: the server refused the save. [current] is the server's
 * version, `null` when the note was deleted.
 */
data class NoteConflict(
    val current: Note?,
)

/** Finds items to link inline (`[[type:id]]`), so the editor can be tested without a server. */
fun interface NoteLinkSearch {
    suspend fun search(query: String): SearchResult
}

/** The tags already in use, suggested when a tag is added (so the editor can be tested without a server). */
fun interface NoteTagSource {
    suspend fun tags(): NotesResult<List<NoteTag>>
}

/** Why link suggestions could not be found. */
enum class LinkSearchError { OFFLINE, FAILED }

/** Why the earlier versions could not be loaded. */
enum class RevisionsError { OFFLINE, FAILED }

/** The open list of a note's earlier versions. */
data class NoteRevisionsState(
    /** Newest first; `null` while they load (or when that failed, see [error]). */
    val revisions: List<NoteRevision>? = null,
    /** The number of the revision shown (and restored by Restore). */
    val selected: Int? = null,
    val error: RevisionsError? = null,
) {
    val selectedRevision: NoteRevision? get() = revisions?.firstOrNull { it.number == selected }
}

/** The suggestions for the inline link `[[query` being typed. */
data class LinkSuggestions(
    val query: String,
    val hits: List<SearchHit> = emptyList(),
    /** A search for [query] is running (or waits for typing to pause). */
    val searching: Boolean = false,
    val error: LinkSearchError? = null,
)

data class NoteEditorUiState(
    /** The note as last loaded or saved; `null` for a new note that was not saved yet. */
    val note: Note? = null,
    val title: String = "",
    val body: String = "",
    /** The note's tags as edited here, saved with the text. */
    val tags: List<String> = emptyList(),
    /** Every tag in use (most used first), for the suggestions; empty until loaded or when that failed. */
    val knownTags: List<String> = emptyList(),
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
    /** The note was deleted elsewhere and the user chose the server's side, or it was deleted here: the editor closes. */
    val closed: Boolean = false,
    /** The note moved to the Trash from this editor (the list offers to undo it). */
    val deleted: Note? = null,
    /** A pin, archive or delete is running. */
    val changing: Boolean = false,
    /** An inline link `[[` is being typed: items it may link to; `null` otherwise. */
    val links: LinkSuggestions? = null,
    /** The earlier versions are shown; `null` otherwise. */
    val revisions: NoteRevisionsState? = null,
) {
    val draft: NoteDraft get() = NoteDraft(title, body, tags)

    /** The known tags not on the note yet. */
    val tagSuggestions: List<String> get() = knownTags.filter { known -> tags.none { it.equals(known, ignoreCase = true) } }

    /** Editing is possible: the note is loaded and no conflict waits for a decision. */
    val editable: Boolean get() = !loading && !unloaded && conflict == null && !closed
}

/**
 * One note: the rendered Markdown or the plain editor, saved automatically [AUTOSAVE_DELAY_MS] after the last change.
 * Saves run one at a time, each over the version the previous one returned. A save the server refuses because the
 * note changed elsewhere stops the autosave until the user keeps their text or takes the server's.
 *
 * Saves run in [saves], a scope that outlives this view model: leaving the editor, or the whole Notes screen (also
 * through the shell's back arrow), neither cancels a running save nor drops text that waits for its autosave (see
 * [onCleared]). [NoteEditors] creates and clears these view models.
 */
class NoteEditorViewModel
    constructor(
        private val notes: NotesRepository,
        images: NoteImages,
        private val saves: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        private val linkSearch: NoteLinkSearch = NoteLinkSearch { SearchResult.Ok(emptyList()) },
        private val tagSource: NoteTagSource = NoteTagSource { NotesResult.Ok(emptyList()) },
    ) : ViewModel() {
        val imageLoader = images.loader

        private val state = MutableStateFlow(NoteEditorUiState())
        val uiState: StateFlow<NoteEditorUiState> = state.asStateFlow()

        private val saving = Mutex()
        private var autosave: Job? = null
        private var linkSearchJob: Job? = null
        private var knownTagsJob: Job? = null
        private var revisionsJob: Job? = null
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
                loadKnownTags()
                if (id != null && !pending()) {
                    pendingImage = null
                    state.value = NoteEditorUiState(loading = true, unloaded = true)
                    viewModelScope.launch { load(id) }
                }
                return
            }
            opened = true
            this.opening = opening
            loadKnownTags()
            if (id == null) {
                state.update { it.copy(editing = true) }
                return
            }
            state.update { it.copy(loading = true, unloaded = true) }
            viewModelScope.launch { load(id) }
        }

        /**
         * Nothing here is waiting: the text is saved (or there is none), and no save, upload, load or conflict is under
         * way. The editor can then be dropped without losing anything.
         */
        val settled: Boolean get() = !pending()

        /** Something here is not on the server yet, or waits for the user: the shown note must not be replaced. */
        private fun pending(): Boolean {
            val s = state.value
            if (s.closed) return false
            return s.loading || s.uploading || s.conflict != null || s.status != SaveStatus.SAVED || saving.isLocked
        }

        /**
         * Loads the tags in use for the suggestions; a failure leaves the ones known (tags can still be typed). A load
         * still running is cancelled, so an older answer never replaces a newer one.
         */
        private fun loadKnownTags() {
            knownTagsJob?.cancel()
            knownTagsJob =
                viewModelScope.launch {
                    val result = tagSource.tags()
                    if (result is NotesResult.Ok) {
                        state.update { s -> s.copy(knownTags = result.value.sortedByDescending { it.count }.map { it.tag }) }
                    }
                }
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
                            tags = result.value.tags,
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

        /**
         * Adds [tag] (trimmed, without a leading `#`, as the server stores it), saved like an edit. A tag already on the
         * note (in any case), an empty one, one longer than [MAX_TAG_LENGTH], one over [MAX_TAGS] or one containing a
         * comma or a line break (which separate tags when typed, as on the web) is not added; one that differs from a
         * tag in use only in case takes that tag's spelling. Returns whether it was added.
         */
        fun addTag(tag: String): Boolean {
            val clean = normalizeTag(tag)
            val s = state.value
            if (!s.editable || clean.isEmpty() || clean.length > MAX_TAG_LENGTH || s.tags.size >= MAX_TAGS) return false
            if (clean.any { it == ',' || it == '\n' }) return false
            if (s.tags.any { it.equals(clean, ignoreCase = true) }) return false
            val spelled = s.knownTags.firstOrNull { it.equals(clean, ignoreCase = true) } ?: clean
            change { it.copy(tags = it.tags + spelled) }
            return true
        }

        /** Removes [tag] from the note, saved like an edit. */
        fun removeTag(tag: String) {
            if (tag !in state.value.tags) return
            change { it.copy(tags = it.tags - tag) }
        }

        /** Checks or unchecks the checklist item on [line] of the body (tapped in the rendered note): saved like an edit. */
        fun toggleChecklist(line: Int) {
            if (Markdown.toggleChecklist(state.value.body, line) == state.value.body) return
            change { it.copy(body = Markdown.toggleChecklist(it.body, line)) }
        }

        /**
         * The inline link `[[query` being typed changed to [query] (`null`: none is typed any more): searches items to
         * link once typing pauses for [LINK_SEARCH_DELAY_MS]. A search for an older query never overwrites newer results.
         */
        fun searchLinks(query: String?) {
            if (query != null && query == state.value.links?.query) return
            linkSearchJob?.cancel()
            linkSearchJob = null
            val q = query?.trim()
            when {
                query == null -> state.update { it.copy(links = null) }
                q.isNullOrEmpty() -> state.update { it.copy(links = LinkSuggestions(query)) }
                else -> {
                    state.update { it.copy(links = LinkSuggestions(query, hits = it.links?.hits.orEmpty(), searching = true)) }
                    linkSearchJob =
                        viewModelScope.launch {
                            delay(LINK_SEARCH_DELAY_MS)
                            val links =
                                when (val result = linkSearch.search(q)) {
                                    is SearchResult.Ok -> LinkSuggestions(query, result.groups.flatMap { it.hits })
                                    SearchResult.Offline -> LinkSuggestions(query, error = LinkSearchError.OFFLINE)
                                    is SearchResult.Failed -> LinkSuggestions(query, error = LinkSearchError.FAILED)
                                }
                            state.update { if (it.links?.query == query) it.copy(links = links) else it }
                        }
                }
            }
        }

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
            return saves.launch { save() }
        }

        /**
         * Saves after [delayMs], unless another change comes first. Only the wait is cancelled: a save that started
         * runs to its end, in [saves] (cancelling it could leave the note saved without its new version, so the next
         * save would conflict with itself). Text typed meanwhile is saved by its own autosave, over the new version.
         */
        private fun saveSoon(delayMs: Long) {
            autosave?.cancel()
            autosave =
                viewModelScope.launch {
                    delay(delayMs)
                    saves.launch { save() }
                }
        }

        /**
         * The editor is gone for good (closed and dropped, or the Notes screen was left, e.g. by the shell's back
         * arrow): text still waiting for its autosave is saved now, in [saves], as this view model's scope is cancelled.
         * Nothing is saved during a conflict (that waits for the user) or before the note was loaded.
         */
        override fun onCleared() {
            autosave?.cancel()
            autosave = null
            saves.launch { save() }
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
                            val saved = it.copy(note = result.note).adoptSavedTags(before.tags)
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
                        tags = current.tags,
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
         * Inserts the image uploaded while the conflict came up: [atCursor] (keep my version) at the cursor it was added
         * at, clamped to the text, even if that text changed meanwhile; else (use the other version) at the end of the
         * text. Returns whether there was one.
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
                // The create is a save: it runs in [saves], so leaving the editor meanwhile does not cancel it (a
                // cancelled create could still reach the server, and the next save would create the note again).
                val note = state.value.note ?: saves.async { create() }.await()
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
                val draft = state.value.draft
                val result = notes.save(null, draft) as? SaveResult.Saved ?: return null
                val sent = draft.tags
                state.update {
                    it.copy(note = result.note).adoptSavedTags(sent).let { s ->
                        s.copy(status = if (s.isSaved()) SaveStatus.SAVED else SaveStatus.UNSAVED)
                    }
                }
                result.note
            }

        fun dismissMessage() = state.update { it.copy(message = null) }

        /** Pins or unpins the note. */
        fun togglePin() = changeFlags { note -> notes.setFlags(note, pinned = !note.pinned) }

        /** Archives the note or takes it back to the notes. */
        fun toggleArchived() = changeFlags { note -> notes.setFlags(note, archived = !note.archived) }

        /**
         * Runs a pin or archive change once the text is saved (it goes over the saved version). A refusal because the
         * note changed elsewhere shows the server's version when nothing typed here would be lost (the user can pin or
         * archive it again); otherwise, or when the note was deleted elsewhere, it is a conflict, as for a save.
         */
        private fun changeFlags(
            onSaved: (NoteEditorUiState, Note) -> NoteEditorUiState = { s, note -> s.copy(note = note) },
            action: suspend (Note) -> SaveResult,
        ) {
            if (!state.value.editable || state.value.note == null || state.value.changing) return
            state.update { it.copy(changing = true, message = null) }
            saves.launch {
                if (save()) {
                    saving.withLock {
                        val note = state.value.note
                        if (note != null && state.value.editable) {
                            val result = action(note)
                            state.update {
                                when (result) {
                                    is SaveResult.Saved -> onSaved(it, result.note)
                                    is SaveResult.Conflict -> {
                                        val current = result.current
                                        if (current != null && it.isSaved()) {
                                            it.copy(
                                                note = current,
                                                title = current.title.orEmpty(),
                                                body = current.bodyMd,
                                                tags = current.tags,
                                                message = NoteEditorMessage.CHANGE_CONFLICT,
                                            )
                                        } else {
                                            it.copy(conflict = NoteConflict(current), status = SaveStatus.UNSAVED, editing = false)
                                        }
                                    }
                                    SaveResult.Offline -> it.copy(message = NoteEditorMessage.CHANGE_OFFLINE)
                                    is SaveResult.Failed -> it.copy(message = NoteEditorMessage.CHANGE_FAILED)
                                }
                            }
                        }
                    }
                }
                state.update { it.copy(changing = false) }
            }
        }

        /**
         * Shows the note's earlier versions (newest first, the newest selected). A load still running is replaced, so an
         * older answer never shows.
         */
        fun openRevisions() {
            val note = state.value.note
            if (!state.value.editable || note == null) return
            state.update { it.copy(revisions = NoteRevisionsState()) }
            loadRevisions(note.id)
        }

        /** Loads the earlier versions again after a failed load. */
        fun retryRevisions() {
            val note = state.value.note ?: return
            if (state.value.revisions?.error == null) return
            state.update { it.copy(revisions = NoteRevisionsState()) }
            loadRevisions(note.id)
        }

        private fun loadRevisions(id: UUID) {
            revisionsJob?.cancel()
            revisionsJob =
                viewModelScope.launch {
                    val loaded =
                        when (val result = notes.revisions(id)) {
                            is NotesResult.Ok -> NoteRevisionsState(result.value, result.value.firstOrNull()?.number)
                            NotesResult.Offline -> NoteRevisionsState(error = RevisionsError.OFFLINE)
                            is NotesResult.Failed -> NoteRevisionsState(error = RevisionsError.FAILED)
                        }
                    state.update { if (it.revisions == null) it else it.copy(revisions = loaded) }
                }
        }

        /** Shows revision [number] of the open list. */
        fun selectRevision(number: Int) =
            state.update { s ->
                val open = s.revisions
                if (open?.revisions?.any { it.number == number } == true) s.copy(revisions = open.copy(selected = number)) else s
            }

        fun closeRevisions() {
            revisionsJob?.cancel()
            revisionsJob = null
            state.update { it.copy(revisions = null) }
        }

        /**
         * Restores the selected revision: the note's title and body become the revision's (tags, pin and archive stay).
         * The text written here is saved first, so it becomes a revision too and the restore can be undone. The list
         * closes. A refusal because the note changed elsewhere is handled as for a pin (see [changeFlags]).
         */
        fun restoreRevision() {
            val number =
                state.value.revisions
                    ?.selectedRevision
                    ?.number ?: return
            if (!state.value.editable || state.value.note == null || state.value.changing) return
            closeRevisions()
            changeFlags(
                onSaved = { s, note ->
                    s.copy(note = note, title = note.title.orEmpty(), body = note.bodyMd, tags = note.tags, status = SaveStatus.SAVED)
                },
            ) { note -> notes.restoreRevision(note, number) }
        }

        /**
         * Moves the note to the Trash; text not saved yet goes with it (Undo in the list brings back the saved version).
         * The editor then closes. A note gone already (deleted elsewhere) closes too.
         */
        fun delete() {
            if (!state.value.editable || state.value.note == null || state.value.changing) return
            autosave?.cancel()
            autosave = null
            state.update { it.copy(changing = true, message = null) }
            saves.launch {
                saving.withLock {
                    val note = state.value.note ?: return@withLock
                    val result = notes.delete(note)
                    state.update {
                        when {
                            result is NotesResult.Ok || (result as? NotesResult.Failed)?.status == NOT_FOUND ->
                                it.copy(closed = true, deleted = note, status = SaveStatus.SAVED)
                            result == NotesResult.Offline -> it.copy(message = NoteEditorMessage.CHANGE_OFFLINE)
                            else -> it.copy(message = NoteEditorMessage.CHANGE_FAILED)
                        }
                    }
                }
                state.update { it.copy(changing = false) }
            }
        }

        /**
         * The server stores tags in its own spelling (that of other notes, each once): when the tags were not changed
         * since [sent] was saved, they take the saved note's, so they do not count as unsaved.
         */
        private fun NoteEditorUiState.adoptSavedTags(sent: List<String>): NoteEditorUiState {
            val saved = note ?: return this
            return if (tags == sent) copy(tags = saved.tags) else this
        }

        /** The text equals the saved note (for a new note: there is nothing to save yet). */
        private fun NoteEditorUiState.isSaved(): Boolean {
            val saved = note ?: return title.isBlank() && body.isEmpty() && tags.isEmpty()
            return title.trim() == saved.title.orEmpty() && body == saved.bodyMd && tags == saved.tags
        }

        companion object {
            const val AUTOSAVE_DELAY_MS = 1_000L
            const val LINK_SEARCH_DELAY_MS = 250L
            private const val NOT_FOUND = 404

            /** The server's limits: a longer tag, or more tags, are refused. */
            const val MAX_TAG_LENGTH = 50
            const val MAX_TAGS = 50

            /** [tag] as the server stores it: trimmed, without a leading `#`. */
            fun normalizeTag(tag: String): String = tag.trim().removePrefix("#").trim()

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
