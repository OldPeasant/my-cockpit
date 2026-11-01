package ch.sonensei.cockpit.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Why the shown list may be outdated. */
enum class BookmarksMessage { OFFLINE, FAILED }

/** Why a change of one bookmark did not happen. */
enum class BookmarkFailure { OFFLINE, CONFLICT, FAILED }

/** A short note at the bottom of the screen. */
sealed interface BookmarksSnackbar {
    /**
     * Tells two notes with the same content apart (the view model numbers them), so each one is shown and the end of
     * one never clears another.
     */
    val id: Long

    /** [bookmark] went to the Trash; Undo takes it out again. */
    data class Trashed(
        val bookmark: Bookmark,
        override val id: Long = 0,
    ) : BookmarksSnackbar

    data class Failed(
        val failure: BookmarkFailure,
        override val id: Long = 0,
    ) : BookmarksSnackbar

    /** No app on the device opens web addresses. */
    data object NoBrowser : BookmarksSnackbar {
        override val id: Long get() = 0
    }
}

data class BookmarksUiState(
    /** The bookmarks of the view and tag, newest first (before the search). */
    val bookmarks: List<Bookmark> = emptyList(),
    val view: BookmarkView = BookmarkView.QUEUE,
    /** The tag filter; `null` shows every bookmark of the view. */
    val tag: String? = null,
    val tags: List<BookmarkTag> = emptyList(),
    /** The search text; only bookmarks matching every word are shown. */
    val query: String = "",
    /** A load is running. */
    val loading: Boolean = false,
    /** The running load was started by pulling the list down. */
    val refreshing: Boolean = false,
    /** The list of this view and tag was loaded once. */
    val loaded: Boolean = false,
    val message: BookmarksMessage? = null,
    /** Bookmarks with a change running. */
    val busy: Set<UUID> = emptySet(),
    /** The bookmark being edited. */
    val editing: Bookmark? = null,
    /** What the editor starts with instead of [editing]'s values: the unsaved edit of a save that failed. */
    val draft: BookmarkDraft? = null,
    val snackbar: BookmarksSnackbar? = null,
) {
    /** What the list shows: the bookmarks matching the search. */
    val shown: List<Bookmark> get() = if (query.isBlank()) bookmarks else bookmarks.filter { it.matches(query) }
}

/**
 * The bookmarks list (SPEC.md §8.4 "Views"): the read-later queue or all bookmarks, by tag, with a search over the
 * loaded ones. Read-later bookmarks shared to the app appear as soon as the capture queue has sent them.
 */
@HiltViewModel
class BookmarksViewModel internal constructor(
    private val bookmarks: BookmarksRepository,
    /** The number of shares still waiting to be sent (`CaptureQueue.status`). */
    pendingShares: Flow<Int>,
) : ViewModel() {
    @Inject
    constructor(bookmarks: BookmarksRepository, queue: CaptureQueue) : this(bookmarks, queue.status.map { it.pendingCount })

    private val state = MutableStateFlow(BookmarksUiState())
    val uiState: StateFlow<BookmarksUiState> = state.asStateFlow()

    private var loadJob: Job? = null

    /** The number of the last snackbar shown. */
    private var snackbars = 0L

    init {
        viewModelScope.launch {
            var before: Int? = null
            pendingShares.collect { pending ->
                // A share left the queue: it is on the server now (or was given up), so the list may have grown.
                if (before.let { it != null && pending < it } && state.value.loaded) refresh()
                before = pending
            }
        }
    }

    /** Loads the list for the current view and tag; a load still running for an earlier filter is dropped. */
    fun refresh(pulled: Boolean = false) {
        loadJob?.cancel()
        val view = state.value.view
        val tag = state.value.tag
        state.update { it.copy(loading = true, refreshing = pulled) }
        loadJob =
            viewModelScope.launch {
                val tags = async { bookmarks.tags() }
                val list = bookmarks.list(view, tag)
                val tagList = tags.await()
                // A newer load replaced this one meanwhile: its list is outdated.
                ensureActive()
                state.update {
                    it.copy(
                        bookmarks = (list as? BookmarksResult.Ok)?.value ?: it.bookmarks,
                        tags = (tagList as? BookmarksResult.Ok)?.value ?: it.tags,
                        loading = false,
                        refreshing = false,
                        loaded = it.loaded || list is BookmarksResult.Ok,
                        message =
                            when (list) {
                                is BookmarksResult.Ok -> null
                                BookmarksResult.Offline -> BookmarksMessage.OFFLINE
                                is BookmarksResult.Failed -> BookmarksMessage.FAILED
                            },
                    )
                }
            }
    }

    fun selectView(view: BookmarkView) {
        if (view == state.value.view) return
        // The bookmarks of the old view would be wrong for the new one: they are cleared until the new list arrives.
        state.update { it.copy(view = view, bookmarks = emptyList(), loaded = false) }
        refresh()
    }

    /** Shows only the bookmarks with [tag] (`null`: all); choosing the active tag again clears the filter. */
    fun selectTag(tag: String?) {
        val next = tag?.takeUnless { it.equals(state.value.tag, ignoreCase = true) }
        if (next == state.value.tag) return
        state.update { it.copy(tag = next, bookmarks = emptyList(), loaded = false) }
        refresh()
    }

    fun search(query: String) = state.update { it.copy(query = query) }

    /** Marks [bookmark] as read, or as unread again. */
    fun toggleRead(bookmark: Bookmark) = change(bookmark) { bookmarks.setRead(it, read = !it.read) }

    fun edit(bookmark: Bookmark) = state.update { it.copy(editing = bookmark, draft = null) }

    fun cancelEdit() = state.update { it.copy(editing = null, draft = null) }

    /**
     * Saves the edited title, tags and notes; the dialog closes once the change is sent. If the save fails, the
     * dialog opens again with [draft] (after a conflict on the reloaded version), so nothing typed is lost.
     */
    fun save(draft: BookmarkDraft) {
        val bookmark = state.value.editing ?: return
        state.update { it.copy(editing = null, draft = null) }
        change(bookmark, reloadTags = true, onFailure = { latest -> reopen(latest, draft) }) { bookmarks.save(it, draft) }
    }

    /** Moves [bookmark] to the Trash; the snackbar offers to undo it. */
    fun delete(bookmark: Bookmark) =
        run(bookmark) { current ->
            when (val result = bookmarks.delete(current)) {
                is BookmarksResult.Ok -> {
                    remove(current.id)
                    show(BookmarksSnackbar.Trashed(current, nextSnackbar()))
                    reloadTags()
                }
                else -> failed(current, result)
            }
        }

    /** Takes the bookmark of [trashed] out of the Trash again and loads the list with it. */
    fun undo(trashed: BookmarksSnackbar.Trashed) {
        dismiss(trashed)
        viewModelScope.launch {
            when (val result = bookmarks.restore(trashed.bookmark.id)) {
                is BookmarksResult.Ok -> refresh()
                else -> show(BookmarksSnackbar.Failed(failure(result), nextSnackbar()))
            }
        }
    }

    fun noBrowser() = show(BookmarksSnackbar.NoBrowser)

    /** The snackbar was shown and is gone; a newer one stays. */
    fun dismiss(snackbar: BookmarksSnackbar) = state.update { if (it.snackbar == snackbar) it.copy(snackbar = null) else it }

    /** Runs [action] on [bookmark]; [onFailure] gets its latest known version when it failed (`null`: it is gone). */
    private fun change(
        bookmark: Bookmark,
        reloadTags: Boolean = false,
        onFailure: (Bookmark?) -> Unit = {},
        action: suspend (Bookmark) -> BookmarksResult<Bookmark>,
    ) = run(bookmark) { current ->
        when (val result = action(current)) {
            is BookmarksResult.Ok -> {
                put(result.value)
                if (reloadTags) reloadTags()
            }
            else -> onFailure(failed(current, result))
        }
    }

    /** Opens the editor again with the unsaved [draft], unless the bookmark is gone or another one is being edited. */
    private fun reopen(
        latest: Bookmark?,
        draft: BookmarkDraft,
    ) {
        if (latest == null) return
        state.update {
            if (it.editing != null) it else it.copy(editing = latest, draft = draft)
        }
    }

    /** Runs a change of [bookmark] (its latest loaded version); one change per bookmark at a time. */
    private fun run(
        bookmark: Bookmark,
        action: suspend (Bookmark) -> Unit,
    ) {
        if (bookmark.id in state.value.busy) return
        val current = state.value.bookmarks.find { it.id == bookmark.id } ?: bookmark
        state.update { it.copy(busy = it.busy + bookmark.id) }
        viewModelScope.launch {
            try {
                action(current)
            } finally {
                state.update { it.copy(busy = it.busy - bookmark.id) }
            }
        }
    }

    /** Shows the new state of a bookmark: replaced in place, or dropped when it left the view. */
    private fun put(bookmark: Bookmark) {
        state.update {
            val stays = it.belongs(bookmark)
            it.copy(bookmarks = it.bookmarks.mapNotNull { b -> if (b.id != bookmark.id) b else bookmark.takeIf { stays } })
        }
        reloadIfLoading()
    }

    /** Drops a bookmark that is gone from the view. */
    private fun remove(id: UUID) {
        state.update { it.copy(bookmarks = it.bookmarks.filterNot { b -> b.id == id }) }
        reloadIfLoading()
    }

    /**
     * A load that started before a local change would bring back the old state of the changed bookmark (a deleted or
     * read one again): it is replaced by a new load.
     */
    private fun reloadIfLoading() {
        if (loadJob?.isActive == true) refresh(pulled = state.value.refreshing)
    }

    /**
     * A refused change: a bookmark gone meanwhile is dropped, an outdated one is loaded again for the next try.
     * Returns the latest known version of [current], or `null` when it is gone.
     */
    private suspend fun failed(
        current: Bookmark,
        result: BookmarksResult<*>,
    ): Bookmark? {
        val status = (result as? BookmarksResult.Failed)?.status
        if (status == BookmarksRepository.NOT_FOUND) {
            remove(current.id)
            return null
        }
        show(BookmarksSnackbar.Failed(failure(result), nextSnackbar()))
        if (status !in BookmarksRepository.CONFLICTS) return current
        return when (val fresh = bookmarks.get(current.id)) {
            is BookmarksResult.Ok -> fresh.value.also { put(it) }
            is BookmarksResult.Failed -> {
                if (fresh.status != BookmarksRepository.NOT_FOUND) return current
                remove(current.id)
                null
            }
            BookmarksResult.Offline -> current
        }
    }

    private fun failure(result: BookmarksResult<*>): BookmarkFailure =
        when {
            result == BookmarksResult.Offline -> BookmarkFailure.OFFLINE
            (result as? BookmarksResult.Failed)?.status in BookmarksRepository.CONFLICTS -> BookmarkFailure.CONFLICT
            else -> BookmarkFailure.FAILED
        }

    private fun show(snackbar: BookmarksSnackbar) = state.update { it.copy(snackbar = snackbar) }

    private fun nextSnackbar() = ++snackbars

    private fun reloadTags() {
        viewModelScope.launch {
            val tags = bookmarks.tags()
            if (tags is BookmarksResult.Ok) state.update { it.copy(tags = tags.value) }
        }
    }
}

/** Whether [bookmark] is shown in the current view and tag. */
private fun BookmarksUiState.belongs(bookmark: Bookmark): Boolean =
    (view == BookmarkView.ALL || bookmark.inQueue) &&
        (tag == null || bookmark.tags.any { it.equals(tag, ignoreCase = true) })
