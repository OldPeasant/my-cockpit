package ch.sonensei.cockpit.core.links

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.search.SearchHit
import ch.sonensei.cockpit.search.SearchTarget
import ch.sonensei.cockpit.search.searchTarget
import ch.sonensei.cockpit.search.searchTypeStyle
import dagger.hilt.internal.GeneratedComponentManagerHolder
import okhttp3.HttpUrl

/** Test tags of the panel. */
object LinkedItemsTags {
    const val PANEL = "links-panel"
    const val EMPTY = "links-empty"
    const val ERROR = "links-error"
    const val ADD = "links-add"
    const val NEW_TASK = "links-new-task"
    const val NEW_NOTE = "links-new-note"
    const val SEARCH_FIELD = "links-search-field"
    const val NO_RESULTS = "links-no-results"
    const val CREATE_FIELD = "links-create-field"
    const val CREATE_CONFIRM = "links-create-confirm"

    fun link(item: LinkedItem) = "links-item:${item.type}:${item.itemId}"

    fun remove(item: LinkedItem) = "links-remove:${item.linkId}"

    fun hit(hit: SearchHit) = "links-hit:${hit.type}:${hit.id}"
}

/** What the panel can ask for, so it can be tested without a view model. */
data class LinkedItemsCallbacks(
    val open: (LinkedItem) -> Unit = {},
    val remove: (LinkedItem) -> Unit = {},
    val retry: () -> Unit = {},
    val openSearch: () -> Unit = {},
    val search: (String) -> Unit = {},
    val add: (SearchHit) -> Unit = {},
    val closeSearch: () -> Unit = {},
    val startCreate: (LinkedCreateKind) -> Unit = {},
    val create: (String) -> Unit = {},
    val cancelCreate: () -> Unit = {},
) {
    companion object {
        fun of(
            vm: LinkedItemsViewModel,
            open: (LinkedItem) -> Unit,
        ) = LinkedItemsCallbacks(
            open = open,
            remove = vm::remove,
            retry = vm::reload,
            openSearch = vm::openSearch,
            search = vm::search,
            add = vm::add,
            closeSearch = vm::closeSearch,
            startCreate = vm::startCreate,
            create = vm::create,
            cancelCreate = vm::cancelCreate,
        )
    }
}

/** Where tapping a linked item leads: its Android screen where there is one (tasks always), else the web app. */
fun linkedItemTarget(
    link: LinkedItem,
    server: HttpUrl?,
): SearchTarget =
    if (link.type == "TASK") {
        SearchTarget.InApp("TASK", link.itemId)
    } else {
        searchTarget(SearchHit(link.itemId, link.type, link.title.orEmpty(), link.subtitle, link.deepLink), server)
    }

/**
 * The "Linked items" panel of [item] with its own view model (one per item). [open] gets the in-app targets
 * ([SearchTarget.InApp], [SearchTarget.Screen]); web targets open in the browser here.
 */
@Composable
fun LinkedItemsSection(
    item: ItemRef,
    open: (SearchTarget) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LinkedItemsViewModel? = null,
) {
    // Without Hilt (screen tests with fake view models) there is no view model to show the panel with.
    if (viewModel == null && LocalActivity.current !is GeneratedComponentManagerHolder) return
    LinkedItemsSectionContent(item, open, modifier, viewModel ?: hiltViewModel(key = linksViewModelKey(item)))
}

/** The key of [item]'s panel view model, so a screen can share it (e.g. for [LinkedItemsUiState.server]). */
fun linksViewModelKey(item: ItemRef) = "links:${item.type}:${item.id}"

@Composable
private fun LinkedItemsSectionContent(
    item: ItemRef,
    open: (SearchTarget) -> Unit,
    modifier: Modifier,
    viewModel: LinkedItemsViewModel,
) {
    LaunchedEffect(item) { viewModel.open(item) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val noBrowser = stringResource(R.string.links_no_browser)
    LinkedItemsPanel(
        state = state,
        callbacks =
            LinkedItemsCallbacks.of(viewModel) { link ->
                when (val target = linkedItemTarget(link, state.server)) {
                    is SearchTarget.Web ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, target.url.toUri()))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, noBrowser, Toast.LENGTH_SHORT).show()
                        }
                    else -> open(target)
                }
            },
        modifier = modifier,
    )
}

/** The links of an item with add, remove and "create linked task/note" (SPEC.md §4.3); stateless. */
@Composable
fun LinkedItemsPanel(
    state: LinkedItemsUiState,
    callbacks: LinkedItemsCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag(LinkedItemsTags.PANEL)) {
        Text(
            stringResource(R.string.links_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
        )
        val links = state.links
        when {
            state.loadError != null ->
                Message(
                    stringResource(if (state.loadError == LinksError.Offline) R.string.links_load_offline else R.string.links_load_failed),
                    retry = callbacks.retry,
                )
            links == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            links.isEmpty() ->
                Text(
                    stringResource(R.string.links_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(LinkedItemsTags.EMPTY),
                )
            else -> links.forEach { link -> LinkRow(link, link.linkId in state.removing, callbacks) }
        }
        state.actionError?.let { error ->
            Message(stringResource(if (error == LinksError.Offline) R.string.links_offline else R.string.links_failed))
        }
        val enabled = !state.busy && state.item != null
        FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Action(R.string.links_add, Icons.Outlined.Add, LinkedItemsTags.ADD, enabled, callbacks.openSearch)
            Action(R.string.links_new_task, Icons.Outlined.TaskAlt, LinkedItemsTags.NEW_TASK, enabled) {
                callbacks.startCreate(LinkedCreateKind.Task)
            }
            Action(R.string.links_new_note, Icons.Outlined.Description, LinkedItemsTags.NEW_NOTE, enabled) {
                callbacks.startCreate(LinkedCreateKind.Note)
            }
        }
    }
    if (state.adding) SearchDialog(state, callbacks)
    state.creating?.let { CreateDialog(it, state.busy, state.actionError, callbacks) }
}

@Composable
private fun LinkRow(
    link: LinkedItem,
    removing: Boolean,
    callbacks: LinkedItemsCallbacks,
) {
    val style = searchTypeStyle(link.type)
    val title = link.title ?: stringResource(R.string.links_untitled)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = link.available, onClickLabel = stringResource(R.string.links_open)) { callbacks.open(link) }
            .padding(start = 16.dp, end = 4.dp)
            .heightIn(min = 48.dp)
            .testTag(LinkedItemsTags.link(link)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(style.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (link.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val subtitle =
                listOfNotNull(
                    stringResource(style.labelRes),
                    link.subtitle,
                    if (link.available) null else stringResource(R.string.links_unavailable),
                ).joinToString(" · ")
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (link.manual) {
            IconButton(
                onClick = { callbacks.remove(link) },
                enabled = !removing,
                modifier = Modifier.testTag(LinkedItemsTags.remove(link)),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.links_remove, title))
            }
        }
    }
}

@Composable
private fun Action(
    label: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Message(
    text: String,
    retry: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(LinkedItemsTags.ERROR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        if (retry != null) TextButton(onClick = retry) { Text(stringResource(R.string.links_retry)) }
    }
}

@Composable
private fun SearchDialog(
    state: LinkedItemsUiState,
    callbacks: LinkedItemsCallbacks,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = callbacks.closeSearch,
        title = { Text(stringResource(R.string.links_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = callbacks.search,
                    label = { Text(stringResource(R.string.links_search_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(LinkedItemsTags.SEARCH_FIELD),
                )
                if (state.searching || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                state.searchError?.let { error ->
                    Text(
                        stringResource(if (error == LinksError.Offline) R.string.links_offline else R.string.links_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                state.actionError?.let { error ->
                    Text(
                        stringResource(if (error == LinksError.Offline) R.string.links_offline else R.string.links_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                val done = !state.searching && state.searchError == null && state.resultsQuery.isNotEmpty()
                if (done && state.results.isEmpty()) {
                    Text(
                        stringResource(R.string.links_no_results),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp).testTag(LinkedItemsTags.NO_RESULTS),
                    )
                }
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    state.results.forEach { group ->
                        Text(
                            stringResource(searchTypeStyle(group.type).labelRes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
                        )
                        group.hits.forEach { hit -> HitRow(hit, enabled = !state.busy) { callbacks.add(hit) } }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = callbacks.closeSearch) { Text(stringResource(R.string.links_close)) } },
    )
}

@Composable
private fun HitRow(
    hit: SearchHit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp)
            .testTag(LinkedItemsTags.hit(hit)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(searchTypeStyle(hit.type).icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(hit.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            hit.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CreateDialog(
    kind: LinkedCreateKind,
    busy: Boolean,
    error: LinksError?,
    callbacks: LinkedItemsCallbacks,
) {
    var title by rememberSaveable(kind) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(kind) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = callbacks.cancelCreate,
        title = { Text(stringResource(if (kind == LinkedCreateKind.Task) R.string.links_new_task else R.string.links_new_note)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.links_create_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(LinkedItemsTags.CREATE_FIELD),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                error?.let {
                    Text(
                        stringResource(if (it == LinksError.Offline) R.string.links_offline else R.string.links_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { callbacks.create(title) },
                enabled = !busy && title.isNotBlank(),
                modifier = Modifier.testTag(LinkedItemsTags.CREATE_CONFIRM),
            ) { Text(stringResource(R.string.links_create)) }
        },
        dismissButton = { TextButton(onClick = callbacks.cancelCreate) { Text(stringResource(R.string.links_cancel)) } },
    )
}
