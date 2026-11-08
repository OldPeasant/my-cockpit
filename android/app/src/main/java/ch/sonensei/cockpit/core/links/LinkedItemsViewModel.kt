package ch.sonensei.cockpit.core.links

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.core.network.ServerUrlRepository
import ch.sonensei.cockpit.search.SearchGroup
import ch.sonensei.cockpit.search.SearchHit
import ch.sonensei.cockpit.search.SearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.util.UUID
import javax.inject.Inject

/** Why a links call failed. */
enum class LinksError { Offline, Failed }

/** Which "create linked…" dialog is open. */
enum class LinkedCreateKind { Task, Note }

/** What the linked-items panel shows. */
data class LinkedItemsUiState(
    val item: ItemRef? = null,
    /** The links; `null` while loading (or when the load failed). */
    val links: List<LinkedItem>? = null,
    val loadError: LinksError? = null,
    /** The last failed action (remove, add, create). */
    val actionError: LinksError? = null,
    /** Links whose removal is running. */
    val removing: Set<UUID> = emptySet(),
    /** The "Add link" search is open. */
    val adding: Boolean = false,
    val query: String = "",
    /** The trimmed query the [results] are for. */
    val resultsQuery: String = "",
    /** Hits without the item itself and the items it is linked to already. */
    val results: List<SearchGroup> = emptyList(),
    val searchError: LinksError? = null,
    /** A link or create request is running. */
    val busy: Boolean = false,
    val creating: LinkedCreateKind? = null,
    /** The server the web deep links are resolved against. */
    val server: HttpUrl? = null,
) {
    /** The search for the current query has not answered yet. */
    val searching: Boolean get() = query.trim().isNotEmpty() && query.trim() != resultsQuery
}

/**
 * The state of one [LinkedItemsPanel]: the links of an item in both directions, adding a link via the global search,
 * removing manual links and creating a linked task or note (SPEC.md §4.3).
 */
@HiltViewModel
class LinkedItemsViewModel internal constructor(
    private val source: LinksSource,
    server: Flow<HttpUrl?>,
    /** Wait after the last keystroke before searching. */
    private val debounceMillis: Long = SEARCH_DEBOUNCE_MS,
) : ViewModel() {
    @Inject
    constructor(source: LinksRepository, serverUrls: ServerUrlRepository) : this(source, serverUrls.serverUrl)

    private val state = MutableStateFlow(LinkedItemsUiState())
    val uiState: StateFlow<LinkedItemsUiState> = state.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    /** Counts item changes: an action that finished for an earlier item is ignored. */
    private var itemSeq = 0

    /** Hits found for the current query, before filtering out the linked ones. */
    private var rawResults: List<SearchGroup> = emptyList()

    init {
        viewModelScope.launch { server.collect { url -> state.update { it.copy(server = url) } } }
    }

    /** Shows the links of [item]; the same item again keeps what is shown. */
    fun open(item: ItemRef) {
        if (state.value.item == item) return
        itemSeq++
        searchJob?.cancel()
        rawResults = emptyList()
        state.update { LinkedItemsUiState(item = item, server = it.server) }
        load()
    }

    /** Loads the links again, e.g. after the item changed elsewhere. */
    fun reload() = load()

    private fun load() {
        val item = state.value.item ?: return
        loadJob?.cancel()
        state.update { it.copy(loadError = null) }
        loadJob =
            viewModelScope.launch {
                when (val result = source.list(item)) {
                    is LinksResult.Ok -> state.update { it.copy(links = result.value).filtered() }
                    else -> state.update { it.copy(loadError = result.error()) }
                }
            }
    }

    fun remove(link: LinkedItem) {
        if (!link.manual || link.linkId in state.value.removing) return
        val seq = itemSeq
        state.update { it.copy(removing = it.removing + link.linkId, actionError = null) }
        viewModelScope.launch {
            val result = source.remove(link.linkId)
            if (seq != itemSeq) return@launch
            state.update { current ->
                val rest = current.copy(removing = current.removing - link.linkId)
                if (result is LinksResult.Ok) {
                    rest.copy(links = current.links?.filterNot { it.linkId == link.linkId }).filtered()
                } else {
                    rest.copy(actionError = result.error())
                }
            }
        }
    }

    fun openSearch() = state.update { it.copy(adding = true, actionError = null) }

    fun closeSearch() {
        searchJob?.cancel()
        rawResults = emptyList()
        state.update { it.copy(adding = false, query = "", resultsQuery = "", results = emptyList(), searchError = null) }
    }

    fun search(query: String) {
        state.update { it.copy(query = query) }
        val q = query.trim()
        // Cancelled first: a search for a query typed meanwhile must not overwrite the results shown again.
        searchJob?.cancel()
        if (q == state.value.resultsQuery && state.value.searchError == null) return
        if (q.isEmpty()) {
            rawResults = emptyList()
            state.update { it.copy(resultsQuery = "", results = emptyList(), searchError = null) }
            return
        }
        searchJob =
            viewModelScope.launch {
                delay(debounceMillis)
                val result = source.search(q)
                rawResults = (result as? SearchResult.Ok)?.groups.orEmpty()
                val error =
                    when (result) {
                        is SearchResult.Ok -> null
                        SearchResult.Offline -> LinksError.Offline
                        is SearchResult.Failed -> LinksError.Failed
                    }
                state.update { it.copy(resultsQuery = q, searchError = error).filtered() }
            }
    }

    /** Links the item to [hit] and closes the search. */
    fun add(hit: SearchHit) {
        val item = state.value.item ?: return
        if (state.value.busy) return
        val seq = itemSeq
        state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            val result = source.link(item, ItemRef(hit.type, hit.id))
            if (seq != itemSeq) return@launch
            state.update { it.copy(busy = false) }
            when (result) {
                is LinksResult.Ok -> {
                    closeSearch()
                    appendOrReload(result.value)
                }
                // Linked meanwhile (another device): show the link that exists.
                LinksResult.Conflict -> {
                    closeSearch()
                    load()
                }
                else -> state.update { it.copy(actionError = result.error()) }
            }
        }
    }

    fun startCreate(kind: LinkedCreateKind) = state.update { it.copy(creating = kind, actionError = null) }

    fun cancelCreate() = state.update { it.copy(creating = null) }

    /** Creates a task or note titled [title] (the open dialog's kind) and links the item to it. */
    fun create(title: String) {
        val item = state.value.item ?: return
        val kind = state.value.creating ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty() || state.value.busy) return
        val seq = itemSeq
        state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            val created =
                when (kind) {
                    LinkedCreateKind.Task -> source.createTask(trimmed)
                    LinkedCreateKind.Note -> source.createNote(trimmed)
                }
            if (seq != itemSeq) return@launch
            if (created !is LinksResult.Ok) {
                state.update { it.copy(busy = false, actionError = created.error()) }
                return@launch
            }
            val type = if (kind == LinkedCreateKind.Task) "TASK" else "NOTE"
            val linked = source.link(item, ItemRef(type, created.value))
            if (seq != itemSeq) return@launch
            state.update { it.copy(busy = false, creating = null) }
            when (linked) {
                is LinksResult.Ok -> appendOrReload(linked.value)
                LinksResult.Conflict -> load()
                // The item exists but is not linked: say so; the user can still link it via "Add link".
                else -> state.update { it.copy(actionError = linked.error()) }
            }
        }
    }

    fun dismissError() = state.update { it.copy(actionError = null) }

    private fun appendOrReload(link: LinkedItem) {
        val links = state.value.links
        // A load still running may predate the new link: load again instead of appending to nothing.
        if (links == null) {
            load()
        } else {
            state.update { it.copy(links = links + link).filtered() }
        }
    }

    /** [results] from the raw hits, without the item and its linked items. */
    private fun LinkedItemsUiState.filtered(): LinkedItemsUiState {
        val linked = links.orEmpty().map { ItemRef(it.type, it.itemId) }.toMutableSet()
        item?.let(linked::add)
        val groups =
            rawResults
                .map { group -> group.copy(hits = group.hits.filterNot { ItemRef(it.type, it.id) in linked }) }
                .filter { it.hits.isNotEmpty() }
        return copy(results = groups)
    }

    internal companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}

private fun LinksResult<*>.error(): LinksError = if (this == LinksResult.Offline) LinksError.Offline else LinksError.Failed
