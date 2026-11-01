package ch.sonensei.cockpit.bookmarks

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.PlaceholderScreen
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.util.Locale
import java.util.UUID

/**
 * The Bookmarks screen. Its view model comes from Hilt, so outside a Hilt activity (the shell's navigation tests,
 * previews) it shows the placeholder instead of failing.
 */
@Composable
fun BookmarksScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        BookmarksRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_bookmarks, Icons.Outlined.Bookmarks, modifier)
    }
}

@Composable
fun BookmarksRoute(
    modifier: Modifier = Modifier,
    viewModel: BookmarksViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Reloaded when the screen is shown and when the app comes back to it, so a link shared meanwhile appears.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    // Site icons come from the sites themselves: a client of its own, without the Cockpit server's address and
    // token, kept in memory only.
    val icons =
        remember(context) {
            ImageLoader
                .Builder(context)
                .diskCache(null)
                .components { add(OkHttpNetworkFetcherFactory()) }
                .build()
        }
    BookmarksContent(
        state = state,
        callbacks =
            BookmarksCallbacks(
                open = { bookmark ->
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, bookmark.url.toUri()))
                    } catch (_: ActivityNotFoundException) {
                        viewModel.noBrowser()
                    }
                },
                selectView = viewModel::selectView,
                selectTag = viewModel::selectTag,
                search = viewModel::search,
                refresh = { viewModel.refresh(pulled = true) },
                retry = { viewModel.refresh() },
                toggleRead = viewModel::toggleRead,
                edit = viewModel::edit,
                delete = viewModel::delete,
                save = viewModel::save,
                cancelEdit = viewModel::cancelEdit,
                undo = viewModel::undo,
                dismiss = viewModel::dismiss,
            ),
        icons = icons,
        modifier = modifier,
    )
}

/** What the screen can ask for, so it can be tested without a view model. */
data class BookmarksCallbacks(
    val open: (Bookmark) -> Unit = {},
    val selectView: (BookmarkView) -> Unit = {},
    /** The tag to filter by; `null` shows all bookmarks of the view. */
    val selectTag: (String?) -> Unit = {},
    val search: (String) -> Unit = {},
    /** The list was pulled down. */
    val refresh: () -> Unit = {},
    val retry: () -> Unit = {},
    val toggleRead: (Bookmark) -> Unit = {},
    val edit: (Bookmark) -> Unit = {},
    val delete: (Bookmark) -> Unit = {},
    val save: (BookmarkDraft) -> Unit = {},
    val cancelEdit: () -> Unit = {},
    val undo: (BookmarksSnackbar.Trashed) -> Unit = {},
    val dismiss: (BookmarksSnackbar) -> Unit = {},
)

internal object BookmarksTags {
    const val LIST = "bookmarks-list"
    const val EMPTY = "bookmarks-empty"
    const val MESSAGE = "bookmarks-message"
    const val RETRY = "bookmarks-retry"
    const val SEARCH = "bookmarks-search"
    const val SEARCH_CLEAR = "bookmarks-search-clear"
    const val ALL_TAGS = "bookmarks-tag-all"
    const val LOADING = "bookmarks-loading"
    const val EDITOR = "bookmarks-editor"
    const val EDIT_TITLE = "bookmarks-edit-title"
    const val EDIT_TAGS = "bookmarks-edit-tags"
    const val EDIT_NOTES = "bookmarks-edit-notes"
    const val EDIT_SAVE = "bookmarks-edit-save"
    const val EDIT_CANCEL = "bookmarks-edit-cancel"
    const val ACTION_READ = "bookmarks-action-read"
    const val ACTION_EDIT = "bookmarks-action-edit"
    const val ACTION_DELETE = "bookmarks-action-delete"

    fun view(view: BookmarkView) = "bookmarks-view:$view"

    fun tag(tag: String) = "bookmarks-tag:$tag"

    fun bookmark(id: UUID) = "bookmarks-bookmark:$id"

    fun menu(id: UUID) = "bookmarks-menu:$id"

    fun icon(id: UUID) = "bookmarks-icon:$id"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksContent(
    state: BookmarksUiState,
    callbacks: BookmarksCallbacks,
    modifier: Modifier = Modifier,
    /** Loads the site icons; `null` shows the site's initial instead (tests, previews). */
    icons: ImageLoader? = null,
) {
    val snackbarHost = remember { SnackbarHostState() }
    BookmarksSnackbarEffect(state.snackbar, snackbarHost, callbacks.undo, callbacks.dismiss)
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ViewSwitch(state.view, callbacks.selectView)
            SearchField(state.query, callbacks.search)
            state.message?.let { ListMessage(it, state.loading, callbacks.retry) }
            if (state.tags.isNotEmpty()) TagFilter(state, callbacks.selectTag)
            if (state.loading && !state.refreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag(BookmarksTags.LOADING))
            } else {
                Spacer(Modifier.padding(top = 4.dp))
            }
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = callbacks.refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                val shown = state.shown
                // Scrollable also when empty, so the list can be pulled down from the empty state too.
                LazyColumn(Modifier.fillMaxSize().testTag(BookmarksTags.LIST), contentPadding = PaddingValues(bottom = 88.dp)) {
                    if (shown.isEmpty() && state.loaded) {
                        item(key = "empty") { EmptyText(state) }
                    }
                    items(shown, key = { it.id }) { bookmark ->
                        BookmarkRow(
                            bookmark = bookmark,
                            showRead = state.view == BookmarkView.ALL,
                            busy = bookmark.id in state.busy,
                            callbacks = callbacks,
                            icons = icons,
                        )
                        HorizontalDivider(Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter))
    }
    state.editing?.let { BookmarkEditor(it, callbacks.save, callbacks.cancelEdit) }
}

@Composable
private fun ViewSwitch(
    selected: BookmarkView,
    onSelect: (BookmarkView) -> Unit,
) {
    val views = BookmarkView.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        views.forEachIndexed { index, view ->
            SegmentedButton(
                selected = selected == view,
                onClick = { onSelect(view) },
                shape = SegmentedButtonDefaults.itemShape(index, views.size),
                icon = {},
                label = {
                    Text(
                        stringResource(
                            when (view) {
                                BookmarkView.QUEUE -> R.string.bookmarks_view_queue
                                BookmarkView.ALL -> R.string.bookmarks_view_all
                            },
                        ),
                        maxLines = 1,
                    )
                },
                modifier = Modifier.testTag(BookmarksTags.view(view)),
            )
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.bookmarks_search)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }, modifier = Modifier.testTag(BookmarksTags.SEARCH_CLEAR)) {
                    Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.bookmarks_search_clear))
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(BookmarksTags.SEARCH),
    )
}

@Composable
private fun EmptyText(state: BookmarksUiState) {
    val text =
        when {
            state.query.isNotBlank() -> stringResource(R.string.bookmarks_empty_search, state.query.trim())
            state.tag != null -> stringResource(R.string.bookmarks_empty_tag, state.tag)
            state.view == BookmarkView.QUEUE -> stringResource(R.string.bookmarks_empty_queue)
            else -> stringResource(R.string.bookmarks_empty_all)
        }
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp).testTag(BookmarksTags.EMPTY),
    )
}

@Composable
private fun ListMessage(
    message: BookmarksMessage,
    loading: Boolean,
    onRetry: () -> Unit,
) {
    val (text, color) =
        when (message) {
            BookmarksMessage.OFFLINE -> R.string.bookmarks_offline to MaterialTheme.colorScheme.secondaryContainer
            BookmarksMessage.FAILED -> R.string.bookmarks_failed to MaterialTheme.colorScheme.errorContainer
        }
    Surface(color = color, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(BookmarksTags.MESSAGE)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            )
            TextButton(onClick = onRetry, enabled = !loading, modifier = Modifier.testTag(BookmarksTags.RETRY)) {
                Text(stringResource(R.string.bookmarks_retry))
            }
        }
    }
}

@Composable
private fun TagFilter(
    state: BookmarksUiState,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = state.tag == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.bookmarks_all_tags)) },
                modifier = Modifier.testTag(BookmarksTags.ALL_TAGS),
            )
        }
        items(state.tags, key = { it.tag }) { tag ->
            FilterChip(
                selected = tag.tag.equals(state.tag, ignoreCase = true),
                onClick = { onSelect(tag.tag) },
                label = { Text("#${tag.tag}") },
                modifier = Modifier.testTag(BookmarksTags.tag(tag.tag)),
            )
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    showRead: Boolean,
    busy: Boolean,
    callbacks: BookmarksCallbacks,
    icons: ImageLoader?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { callbacks.open(bookmark) }
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp)
            .testTag(BookmarksTags.bookmark(bookmark.id)),
        verticalAlignment = Alignment.Top,
    ) {
        SiteIcon(bookmark, icons)
        Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val dimmed = showRead && bookmark.read
            Text(
                bookmark.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (showRead && !bookmark.read) FontWeight.SemiBold else null,
                color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val site = if (showRead && bookmark.read) stringResource(R.string.bookmarks_site_read, bookmark.site) else bookmark.site
            Text(
                site,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            bookmark.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (bookmark.tags.isNotEmpty()) {
                Text(
                    bookmark.tags.joinToString("  ") { "#$it" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        RowMenu(bookmark, busy, callbacks)
    }
}

/** The site's icon when the server found one, else the first letter of the site. */
@Composable
private fun SiteIcon(
    bookmark: Bookmark,
    icons: ImageLoader?,
) {
    val tile = Modifier.size(40.dp).clip(CircleShape).testTag(BookmarksTags.icon(bookmark.id))
    val favicon = bookmark.favicon?.takeIf { it.startsWith("https://", ignoreCase = true) }
    if (icons != null && favicon != null) {
        SubcomposeAsyncImage(
            model = favicon,
            imageLoader = icons,
            contentDescription = null,
            loading = { Initial(bookmark) },
            error = { Initial(bookmark) },
            modifier = tile.padding(8.dp),
        )
    } else {
        Box(tile) { Initial(bookmark) }
    }
}

@Composable
private fun Initial(bookmark: Bookmark) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                bookmark.site
                    .firstOrNull { it.isLetterOrDigit() }
                    ?.toString()
                    ?.uppercase(Locale.ROOT) ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun RowMenu(
    bookmark: Bookmark,
    busy: Boolean,
    callbacks: BookmarksCallbacks,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = !busy, modifier = Modifier.testTag(BookmarksTags.menu(bookmark.id))) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.bookmarks_actions, bookmark.displayTitle))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (bookmark.read) R.string.bookmarks_mark_unread else R.string.bookmarks_mark_read)) },
                onClick = {
                    open = false
                    callbacks.toggleRead(bookmark)
                },
                modifier = Modifier.testTag(BookmarksTags.ACTION_READ),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bookmarks_edit)) },
                onClick = {
                    open = false
                    callbacks.edit(bookmark)
                },
                modifier = Modifier.testTag(BookmarksTags.ACTION_EDIT),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bookmarks_delete)) },
                onClick = {
                    open = false
                    callbacks.delete(bookmark)
                },
                modifier = Modifier.testTag(BookmarksTags.ACTION_DELETE),
            )
        }
    }
}

@Composable
private fun BookmarkEditor(
    bookmark: Bookmark,
    onSave: (BookmarkDraft) -> Unit,
    onCancel: () -> Unit,
) {
    var title by rememberSaveable(bookmark.id) { mutableStateOf(bookmark.title.orEmpty()) }
    var tags by rememberSaveable(bookmark.id) { mutableStateOf(bookmark.tags.joinToString(" ")) }
    var notes by rememberSaveable(bookmark.id) { mutableStateOf(bookmark.description.orEmpty()) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.bookmarks_edit_heading)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(MAX_TITLE) },
                    label = { Text(stringResource(R.string.bookmarks_field_title)) },
                    placeholder = { Text(bookmark.site) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(BookmarksTags.EDIT_TITLE),
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text(stringResource(R.string.bookmarks_field_tags)) },
                    supportingText = { Text(stringResource(R.string.bookmarks_field_tags_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(BookmarksTags.EDIT_TAGS),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(MAX_NOTES) },
                    label = { Text(stringResource(R.string.bookmarks_field_notes)) },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().testTag(BookmarksTags.EDIT_NOTES),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(BookmarkDraft(title, tags, notes)) }, modifier = Modifier.testTag(BookmarksTags.EDIT_SAVE)) {
                Text(stringResource(R.string.bookmarks_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(BookmarksTags.EDIT_CANCEL)) {
                Text(stringResource(R.string.bookmarks_cancel))
            }
        },
        modifier = Modifier.testTag(BookmarksTags.EDITOR),
    )
}

/** The server's limits (`BookmarkPatchRequest`). */
private const val MAX_TITLE = 500
private const val MAX_NOTES = 2000

@Composable
private fun BookmarksSnackbarEffect(
    snackbar: BookmarksSnackbar?,
    host: SnackbarHostState,
    onUndo: (BookmarksSnackbar.Trashed) -> Unit,
    onDismiss: (BookmarksSnackbar) -> Unit,
) {
    val resources = LocalResources.current
    LaunchedEffect(snackbar) {
        if (snackbar == null) return@LaunchedEffect
        val result =
            when (snackbar) {
                is BookmarksSnackbar.Trashed ->
                    host.showSnackbar(
                        message = resources.getString(R.string.bookmarks_trashed, snackbar.bookmark.displayTitle),
                        actionLabel = resources.getString(R.string.bookmarks_undo),
                        duration = SnackbarDuration.Long,
                    )
                is BookmarksSnackbar.Failed ->
                    host.showSnackbar(
                        message =
                            resources.getString(
                                when (snackbar.failure) {
                                    BookmarkFailure.OFFLINE -> R.string.bookmarks_change_offline
                                    BookmarkFailure.CONFLICT -> R.string.bookmarks_change_conflict
                                    BookmarkFailure.FAILED -> R.string.bookmarks_change_failed
                                },
                            ),
                        duration = SnackbarDuration.Short,
                    )
                BookmarksSnackbar.NoBrowser ->
                    host.showSnackbar(message = resources.getString(R.string.bookmarks_no_browser), duration = SnackbarDuration.Short)
            }
        if (result == SnackbarResult.ActionPerformed && snackbar is BookmarksSnackbar.Trashed) onUndo(snackbar) else onDismiss(snackbar)
    }
}
