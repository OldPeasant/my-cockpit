package ch.sonensei.cockpit.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Why the shown list may be outdated. */
enum class NotesListMessage { OFFLINE, FAILED }

/** Why a change of one note did not happen. */
enum class NoteChangeFailure { OFFLINE, CONFLICT, FAILED }

/** A short note at the bottom of the screen. */
sealed interface NotesSnackbar {
    /** Tells two snackbars with the same content apart (the view model numbers them), so each one is shown. */
    val id: Long

    /** [note] went to the Trash; Undo takes it out again. */
    data class Trashed(
        val note: Note,
        override val id: Long = 0,
    ) : NotesSnackbar

    /** [note] was archived ([Note.archived]) or taken back to the notes. */
    data class Archived(
        val note: Note,
        override val id: Long = 0,
    ) : NotesSnackbar

    data class Failed(
        val failure: NoteChangeFailure,
        override val id: Long = 0,
    ) : NotesSnackbar
}

data class NotesListUiState(
    /** The notes of the view and tag, pinned first, then the most recently changed (before the search). */
    val notes: List<Note> = emptyList(),
    val tags: List<NoteTag> = emptyList(),
    /** The tag filter; `null` shows every note. */
    val tag: String? = null,
    /** The Archived view instead of the notes. */
    val archived: Boolean = false,
    /** The search text. */
    val query: String = "",
    /** The ids the server's search found for [query]; `null` while there is no search (or it has no answer yet). */
    val hits: Set<UUID>? = null,
    /** A load or search is running. */
    val loading: Boolean = false,
    /** Nothing was loaded yet. */
    val loaded: Boolean = false,
    val message: NotesListMessage? = null,
    /** Notes whose pin, archive or delete is running. */
    val busy: Set<UUID> = emptySet(),
    val snackbar: NotesSnackbar? = null,
) {
    /** A search text is entered. */
    val searching: Boolean get() = query.isNotBlank()

    /** What the list shows: [notes], only those the search found while there is a search text. */
    val shown: List<Note>
        get() =
            when {
                !searching -> notes
                hits == null -> emptyList()
                else -> notes.filter { it.id in hits }
            }
}

/** The notes list (SPEC.md §8.1 "Views"): pinned first, filtered by one tag, archived or not, and searched. */
@HiltViewModel
class NotesListViewModel
    @Inject
    constructor(
        private val notes: NotesRepository,
        images: NoteImages,
    ) : ViewModel() {
        val imageLoader = images.loader

        private val state = MutableStateFlow(NotesListUiState())
        val uiState: StateFlow<NotesListUiState> = state.asStateFlow()

        private var loadJob: Job? = null
        private var searchJob: Job? = null
        private var snackbars = 0L

        /** A list load is running ([loadJob]); [NotesListUiState.loading] is true while it or [searchRunning] is. */
        private var listLoading = false

        /** A search request is running ([searchJob], after its delay). */
        private var searchRunning = false

        /** Why the last list load failed; only a list load changes it. */
        private var listMessage: NotesListMessage? = null

        /** Why the last search failed; only a search (or clearing it) changes it, so it never removes [listMessage]. */
        private var searchMessage: NotesListMessage? = null

        /** The shown message: a failed load's first (its Retry loads the list and searches again), else a failed search's. */
        private fun shownMessage() = listMessage ?: searchMessage

        /** Loads the list for the current filter (and searches again); a load for an earlier filter is dropped. */
        fun refresh() {
            loadJob?.cancel()
            val tag = state.value.tag
            val archived = state.value.archived
            listLoading = true
            state.update { it.copy(loading = true) }
            loadJob =
                viewModelScope.launch {
                    val tags = async { notes.tags() }
                    val list = notes.list(tag, archived)
                    val tagList = tags.await()
                    listLoading = false
                    listMessage = message(list)
                    state.update {
                        it.copy(
                            notes = (list as? NotesResult.Ok)?.value ?: it.notes,
                            tags = (tagList as? NotesResult.Ok)?.value ?: it.tags,
                            loading = searchRunning,
                            loaded = it.loaded || list is NotesResult.Ok,
                            message = shownMessage(),
                        )
                    }
                }
            if (state.value.searching) search(0)
        }

        /** Shows only the notes with [tag] (`null`: all); choosing the active tag again clears the filter. */
        fun selectTag(tag: String?) {
            val next = tag?.takeUnless { it.equals(state.value.tag, ignoreCase = true) }
            if (next == state.value.tag) return
            // The notes of the old filter would be wrong for the new one: they are cleared until the new list arrives.
            state.update { it.copy(tag = next, notes = emptyList(), loaded = false) }
            refresh()
        }

        /** Shows the archived notes ([archived]) or the others. */
        fun showArchived(archived: Boolean) {
            if (archived == state.value.archived) return
            state.update { it.copy(archived = archived, notes = emptyList(), loaded = false) }
            refresh()
        }

        /**
         * Searches the notes for [query] on the server, once the typing paused. The hits of the previous text are
         * dropped at once: they do not belong to the new one.
         */
        fun setQuery(query: String) {
            val before = state.value.query
            state.update { it.copy(query = query) }
            if (query.trim() == before.trim()) return
            state.update { it.copy(hits = null) }
            if (query.isBlank()) {
                cancelSearch()
                // The message of a failed search is about a search that is gone.
                searchMessage = null
                state.update { it.copy(message = shownMessage()) }
            } else {
                search(SEARCH_DELAY_MS)
            }
        }

        /** Stops a running search; its loading ends with it. */
        private fun cancelSearch() {
            searchJob?.cancel()
            searchJob = null
            searchRunning = false
            state.update { it.copy(loading = listLoading) }
        }

        private fun search(delayMs: Long) {
            cancelSearch()
            searchJob =
                viewModelScope.launch {
                    delay(delayMs)
                    val query = state.value.query
                    searchRunning = true
                    state.update { it.copy(loading = true) }
                    val result = notes.search(query)
                    searchRunning = false
                    searchMessage = message(result)
                    state.update {
                        it.copy(
                            // A failed search for the same text (e.g. on a reload) keeps the hits it found before.
                            hits = (result as? NotesResult.Ok)?.value?.toSet() ?: it.hits,
                            loading = listLoading,
                            message = shownMessage(),
                        )
                    }
                }
        }

        fun togglePin(note: Note) =
            change(note) { current ->
                when (val result = notes.setFlags(current, pinned = !current.pinned)) {
                    is SaveResult.Saved -> replace(result.note)
                    else -> failed(result)
                }
            }

        /** Archives [note] or takes it back to the notes: it leaves the view it was in. */
        fun toggleArchived(note: Note) =
            change(note) { current ->
                when (val result = notes.setFlags(current, archived = !current.archived)) {
                    is SaveResult.Saved -> {
                        remove(current.id)
                        show(NotesSnackbar.Archived(result.note, ++snackbars))
                    }
                    else -> failed(result)
                }
            }

        /** Moves [note] to the Trash; the snackbar offers to undo it. */
        fun delete(note: Note) =
            change(note) { current ->
                when (val result = notes.delete(current)) {
                    is NotesResult.Ok -> {
                        remove(current.id)
                        trashed(current)
                        reloadTags()
                    }
                    NotesResult.Offline -> show(NotesSnackbar.Failed(NoteChangeFailure.OFFLINE, ++snackbars))
                    is NotesResult.Failed -> failed(if (result.status in CONFLICTS) CONFLICT else SaveResult.Failed(result.status))
                }
            }

        /** [note] was moved to the Trash (here or in its editor): the snackbar offers to undo it. */
        fun trashed(note: Note) = show(NotesSnackbar.Trashed(note, ++snackbars))

        /** Takes the note of [trashed] out of the Trash again and loads the list with it. */
        fun undo(trashed: NotesSnackbar.Trashed) {
            dismiss(trashed)
            viewModelScope.launch {
                when (val result = notes.restore(trashed.note.id)) {
                    is NotesResult.Ok -> refresh()
                    NotesResult.Offline -> show(NotesSnackbar.Failed(NoteChangeFailure.OFFLINE, ++snackbars))
                    is NotesResult.Failed -> show(NotesSnackbar.Failed(NoteChangeFailure.FAILED, ++snackbars))
                }
            }
        }

        /** The snackbar was shown and is gone; a newer one stays. */
        fun dismiss(snackbar: NotesSnackbar) = state.update { if (it.snackbar == snackbar) it.copy(snackbar = null) else it }

        /** Runs [action] on the latest shown version of [note], one change per note at a time. */
        private fun change(
            note: Note,
            action: suspend (Note) -> Unit,
        ) {
            if (note.id in state.value.busy) return
            val current = state.value.notes.firstOrNull { it.id == note.id } ?: note
            state.update { it.copy(busy = it.busy + note.id) }
            viewModelScope.launch {
                try {
                    action(current)
                } finally {
                    state.update { it.copy(busy = it.busy - note.id) }
                }
            }
        }

        private fun replace(note: Note) {
            state.update { s -> s.copy(notes = (s.notes.filter { it.id != note.id } + note).sortedWith(noteOrder)) }
            reloadIfLoading()
        }

        private fun remove(id: UUID) {
            state.update { s -> s.copy(notes = s.notes.filter { it.id != id }) }
            reloadIfLoading()
        }

        /**
         * A load that started before a change would bring back the old note (or its old pin) when it answers after
         * the change: it is started again, so the list it shows includes the change.
         */
        private fun reloadIfLoading() {
            if (loadJob?.isActive == true) refresh()
        }

        /** A refused change: the note changed elsewhere (the list is loaded again), or the change did not get through. */
        private fun failed(result: SaveResult) {
            val failure =
                when (result) {
                    is SaveResult.Conflict -> NoteChangeFailure.CONFLICT
                    SaveResult.Offline -> NoteChangeFailure.OFFLINE
                    else -> NoteChangeFailure.FAILED
                }
            show(NotesSnackbar.Failed(failure, ++snackbars))
            if (failure == NoteChangeFailure.CONFLICT) refresh()
        }

        private fun reloadTags() {
            viewModelScope.launch {
                (notes.tags() as? NotesResult.Ok)?.let { tags -> state.update { it.copy(tags = tags.value) } }
            }
        }

        private fun show(snackbar: NotesSnackbar) = state.update { it.copy(snackbar = snackbar) }

        private fun message(result: NotesResult<*>) =
            when (result) {
                is NotesResult.Ok -> null
                NotesResult.Offline -> NotesListMessage.OFFLINE
                is NotesResult.Failed -> NotesListMessage.FAILED
            }

        companion object {
            /** How long the typing must pause before the search runs. */
            const val SEARCH_DELAY_MS = 300L
            private val CONFLICTS = setOf(404, 409, 412)
            private val CONFLICT = SaveResult.Conflict(null)
        }
    }
