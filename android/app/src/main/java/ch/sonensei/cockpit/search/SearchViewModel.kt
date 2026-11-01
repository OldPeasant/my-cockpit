package ch.sonensei.cockpit.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.core.network.ServerUrlRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import javax.inject.Inject

/** Why no results are shown for the current search. */
enum class SearchMessage { OFFLINE, FAILED }

/** What a search is for: the trimmed text and the selected type filters. */
data class SearchKey(
    val query: String,
    val filters: Set<SearchFilter>,
)

data class SearchUiState(
    /** The search text as typed. */
    val query: String = "",
    val filters: Set<SearchFilter> = emptySet(),
    /** A search is running. */
    val loading: Boolean = false,
    val groups: List<SearchGroup> = emptyList(),
    /** The search [groups] belong to; `null` before the first result. */
    val shownFor: SearchKey? = null,
    /** The last search for the current text and filters failed. */
    val message: SearchMessage? = null,
) {
    val key: SearchKey get() = SearchKey(query.trim(), filters)

    /** Nothing typed yet: the screen shows a hint, not results. */
    val idle: Boolean get() = key.query.isEmpty()

    /** The search for the current text and filters arrived and found nothing. */
    val nothingFound: Boolean get() = !idle && message == null && shownFor == key && groups.isEmpty()
}

/** Global search (SPEC.md §4.4): searches as the user types, once typing pauses. */
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val repository: SearchRepository,
        serverUrls: ServerUrlRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(SearchUiState())
        val uiState: StateFlow<SearchUiState> = state.asStateFlow()

        /** The configured server, below which web deep links resolve. */
        val server: StateFlow<HttpUrl?> = serverUrls.serverUrl.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private var searchJob: Job? = null

        /** Searches for [query] once typing pauses. */
        fun setQuery(query: String) {
            if (query == state.value.query) return
            state.update { it.copy(query = query, message = null) }
            search(debounce = true)
        }

        /** Selects or clears the type filter [filter]; searches again right away. */
        fun toggle(filter: SearchFilter) {
            state.update { it.copy(filters = if (filter in it.filters) it.filters - filter else it.filters + filter, message = null) }
            search(debounce = false)
        }

        /** The keyboard's search action: searches now instead of waiting for the pause. */
        fun submit() = search(debounce = false)

        fun retry() = search(debounce = false)

        /** Runs the search for the current text and filters; one still running or waiting for an earlier one is dropped. */
        private fun search(debounce: Boolean) {
            searchJob?.cancel()
            val key = state.value.key
            if (key.query.isEmpty()) {
                state.update { it.copy(loading = false, groups = emptyList(), shownFor = null, message = null) }
                return
            }
            searchJob =
                viewModelScope.launch {
                    if (debounce) delay(SEARCH_DELAY_MS)
                    state.update { it.copy(loading = true) }
                    val result = repository.search(key.query, key.filters)
                    state.update {
                        when (result) {
                            is SearchResult.Ok -> it.copy(loading = false, groups = result.groups, shownFor = key, message = null)
                            SearchResult.Offline -> it.copy(loading = false, message = SearchMessage.OFFLINE)
                            is SearchResult.Failed -> it.copy(loading = false, message = SearchMessage.FAILED)
                        }
                    }
                }
        }

        internal companion object {
            const val SEARCH_DELAY_MS = 300L
        }
    }
