package ch.sonensei.cockpit.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why the shown list may be outdated. */
enum class NotesListMessage { OFFLINE, FAILED }

data class NotesListUiState(
    /** Pinned first, then the most recently changed. */
    val notes: List<Note> = emptyList(),
    val tags: List<NoteTag> = emptyList(),
    /** The tag filter; `null` shows every note. */
    val tag: String? = null,
    /** A load is running. */
    val loading: Boolean = false,
    /** Nothing was loaded yet. */
    val loaded: Boolean = false,
    val message: NotesListMessage? = null,
)

/** The notes list (SPEC.md §8.1 "Views"): pinned first, filtered by one tag. */
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

        /** Loads the list for the current filter; a load still running for an earlier filter is dropped. */
        fun refresh() {
            loadJob?.cancel()
            val tag = state.value.tag
            state.update { it.copy(loading = true) }
            loadJob =
                viewModelScope.launch {
                    val tags = async { notes.tags() }
                    val list = notes.list(tag)
                    val tagList = tags.await()
                    state.update {
                        it.copy(
                            notes = (list as? NotesResult.Ok)?.value ?: it.notes,
                            tags = (tagList as? NotesResult.Ok)?.value ?: it.tags,
                            loading = false,
                            loaded = it.loaded || list is NotesResult.Ok,
                            message =
                                when (list) {
                                    is NotesResult.Ok -> null
                                    NotesResult.Offline -> NotesListMessage.OFFLINE
                                    is NotesResult.Failed -> NotesListMessage.FAILED
                                },
                        )
                    }
                }
        }

        /** Shows only the notes with [tag] (`null`: all); choosing the active tag again clears the filter. */
        fun selectTag(tag: String?) {
            val next = tag?.takeUnless { it.equals(state.value.tag, ignoreCase = true) }
            if (next == state.value.tag) return
            // The notes of the old filter would be wrong for the new one: they are cleared until the new list arrives.
            state.update { it.copy(tag = next, notes = emptyList(), loaded = false) }
            refresh()
        }
    }
