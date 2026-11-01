package ch.sonensei.cockpit.search

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.util.UUID

/** The shell's navigation routes of search: the search screen, and an item opened from it. */
object SearchRoutes {
    const val SEARCH = "search"
    const val ITEM = "search/item/{type}/{id}"
    const val ARG_TYPE = "type"
    const val ARG_ID = "id"

    fun item(
        type: String,
        id: UUID,
    ): String = "search/item/$type/$id"

    fun isSearch(route: String?): Boolean = route == SEARCH || route == ITEM
}

/**
 * Global search: [openItem] shows an item in its Android detail view, [openScreen] a screen of the shell; items without
 * either open in the browser.
 */
@Composable
fun SearchScreen(
    openItem: (type: String, id: UUID) -> Unit,
    openScreen: (CockpitDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        SearchRoute(openItem, openScreen, modifier)
    } else {
        // Without Hilt (shell tests): the empty screen, so the frame around it can be tested.
        SearchContent(SearchUiState(), SearchCallbacks(), modifier)
    }
}

@Composable
private fun SearchRoute(
    openItem: (type: String, id: UUID) -> Unit,
    openScreen: (CockpitDestination) -> Unit,
    modifier: Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val noBrowser = stringResource(R.string.search_no_browser)
    SearchContent(
        state = state,
        callbacks =
            SearchCallbacks(
                query = viewModel::setQuery,
                toggle = viewModel::toggle,
                submit = viewModel::submit,
                retry = viewModel::retry,
                open = { hit ->
                    when (val target = searchTarget(hit, viewModel.server.value)) {
                        is SearchTarget.InApp -> openItem(target.type, target.id)
                        is SearchTarget.Screen -> openScreen(target.destination)
                        is SearchTarget.Web ->
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, target.url.toUri()))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, noBrowser, Toast.LENGTH_SHORT).show()
                            }
                    }
                },
            ),
        modifier = modifier,
    )
}

/** What the screen can ask for, so it can be tested without a view model. */
data class SearchCallbacks(
    val query: (String) -> Unit = {},
    val toggle: (SearchFilter) -> Unit = {},
    val submit: () -> Unit = {},
    val retry: () -> Unit = {},
    val open: (SearchHit) -> Unit = {},
)

internal object SearchTags {
    const val FIELD = "search-field"
    const val CLEAR = "search-clear"
    const val LOADING = "search-loading"
    const val HINT = "search-hint"
    const val EMPTY = "search-empty"
    const val MESSAGE = "search-message"
    const val RETRY = "search-retry"
    const val RESULTS = "search-results"

    fun filter(filter: SearchFilter) = "search-filter:${filter.name}"

    fun group(type: String) = "search-group:$type"

    fun hit(id: UUID) = "search-hit:$id"
}

@Composable
fun SearchContent(
    state: SearchUiState,
    callbacks: SearchCallbacks,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    // The field takes the focus when the screen opens, so typing can start right away.
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = callbacks.query,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    .focusRequester(focus)
                    .testTag(SearchTags.FIELD),
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { callbacks.query("") }, modifier = Modifier.testTag(SearchTags.CLEAR)) {
                        Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.search_clear))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions =
                KeyboardActions(onSearch = {
                    callbacks.submit()
                    keyboard?.hide()
                }),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchFilter.entries.forEach { filter ->
                FilterChip(
                    selected = filter in state.filters,
                    onClick = { callbacks.toggle(filter) },
                    label = { Text(stringResource(filter.labelRes)) },
                    modifier = Modifier.testTag(SearchTags.filter(filter)),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (state.loading && !state.idle) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag(SearchTags.LOADING))
            }
        }
        when {
            state.idle ->
                SearchNotice(Icons.Outlined.Search, stringResource(R.string.search_hint), Modifier.testTag(SearchTags.HINT))
            state.message != null ->
                SearchNotice(
                    icon = if (state.message == SearchMessage.OFFLINE) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline,
                    text =
                        stringResource(
                            if (state.message == SearchMessage.OFFLINE) R.string.search_offline else R.string.search_failed,
                        ),
                    modifier = Modifier.testTag(SearchTags.MESSAGE),
                ) {
                    FilledTonalButton(onClick = callbacks.retry, modifier = Modifier.testTag(SearchTags.RETRY)) {
                        Text(stringResource(R.string.search_retry))
                    }
                }
            state.nothingFound ->
                SearchNotice(Icons.Outlined.SearchOff, stringResource(R.string.search_nothing_found), Modifier.testTag(SearchTags.EMPTY))
            else -> SearchResults(state.groups, callbacks.open)
        }
    }
}

@Composable
private fun SearchResults(
    groups: List<SearchGroup>,
    open: (SearchHit) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    LazyColumn(Modifier.fillMaxSize().testTag(SearchTags.RESULTS)) {
        groups.forEach { group ->
            val style = searchTypeStyle(group.type)
            item(key = "group:${group.type}") {
                Text(
                    text = stringResource(style.labelRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                            .semantics { heading() }
                            .testTag(SearchTags.group(group.type)),
                )
            }
            items(group.hits, key = { "hit:${it.type}:${it.id}" }) { hit ->
                ListItem(
                    headlineContent = { Text(hit.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent =
                        hit.subtitle?.let { subtitle ->
                            { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
                    leadingContent = {
                        Icon(searchTypeStyle(hit.type).icon, contentDescription = stringResource(searchTypeStyle(hit.type).labelRes))
                    },
                    modifier =
                        Modifier
                            .testTag(SearchTags.hit(hit.id))
                            .clickable {
                                keyboard?.hide()
                                open(hit)
                            },
                )
            }
        }
    }
}

@Composable
private fun SearchNotice(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}
