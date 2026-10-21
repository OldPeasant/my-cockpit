package ch.sonensei.cockpit.gtd.next

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.util.UUID

/**
 * The Next tab. Its view model comes from Hilt, so outside a Hilt activity (the shell's navigation tests, previews)
 * it shows the placeholder instead of failing.
 */
@Composable
fun NextScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        NextRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_next, Icons.Outlined.Checklist, modifier)
    }
}

@Composable
fun NextRoute(
    modifier: Modifier = Modifier,
    viewModel: NextViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    NextContent(state, NextCallbacks.of(viewModel), modifier)
}

/** What the Next screen can ask for, so the screen can be tested without a view model. */
data class NextCallbacks(
    val toggleContext: (UUID) -> Unit = {},
    val toggleEnergy: (Energy) -> Unit = {},
    val toggleCreativity: (Creativity) -> Unit = {},
    val toggleMaxMinutes: (Int) -> Unit = {},
    val clearFilter: () -> Unit = {},
    val complete: (GtdTask) -> Unit = {},
    val undo: (CompletionSnackbar.Undo) -> Unit = {},
    val dismiss: (CompletionSnackbar) -> Unit = {},
) {
    companion object {
        fun of(vm: NextViewModel) =
            NextCallbacks(
                vm::toggleContext,
                vm::toggleEnergy,
                vm::toggleCreativity,
                vm::toggleMaxMinutes,
                vm::clearFilter,
                vm::complete,
                vm::undo,
                vm::dismiss,
            )
    }
}

object NextTags {
    const val LIST = "next-list"
    const val EMPTY = "next-empty"
    const val FILTER_TOGGLE = "next-filter-toggle"
    const val FILTERS = "next-filters"
    const val CLEAR = "next-filter-clear"

    fun context(id: UUID) = "next-filter-context-$id"

    fun energy(energy: Energy) = "next-filter-energy-${energy.name.lowercase()}"

    fun creativity(creativity: Creativity) = "next-filter-creativity-${creativity.name.lowercase()}"

    fun estimate(minutes: Int) = "next-filter-estimate-$minutes"
}

@Composable
fun NextContent(
    state: NextUiState,
    callbacks: NextCallbacks,
    modifier: Modifier = Modifier,
) {
    val snackbarHost = remember { SnackbarHostState() }
    CompletionSnackbarEffect(state.snackbar, snackbarHost, callbacks.undo, callbacks.dismiss)
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.offline) OfflineBanner(state.refreshedAt)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        state.loading -> stringResource(R.string.tasks_loading)
                        state.filter.activeCount > 0 -> stringResource(R.string.next_count_filtered, state.tasks.size, state.total)
                        else -> pluralStringResource(R.plurals.next_count, state.total, state.total)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { filtersOpen = !filtersOpen }, modifier = Modifier.testTag(NextTags.FILTER_TOGGLE)) {
                    Icon(Icons.Outlined.FilterList, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        if (state.filter.activeCount > 0) {
                            stringResource(R.string.next_filters_active, state.filter.activeCount)
                        } else {
                            stringResource(R.string.next_filters)
                        },
                    )
                }
            }
            if (filtersOpen) Filters(state, callbacks)
            HorizontalDivider()
            when {
                state.tasks.isNotEmpty() ->
                    LazyColumn(Modifier.fillMaxSize().testTag(NextTags.LIST)) {
                        items(state.tasks, key = { it.id }) { task ->
                            TaskRow(task, state.contexts.associateBy { it.id }, state.today, state.offline, callbacks.complete)
                        }
                    }
                !state.loading -> Empty(state, callbacks)
            }
        }
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Filters(
    state: NextUiState,
    callbacks: NextCallbacks,
) {
    val filter = state.filter
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(NextTags.FILTERS)) {
        if (state.contexts.isNotEmpty()) {
            FilterGroup(stringResource(R.string.next_filter_context)) {
                state.contexts.forEach { context ->
                    FilterChip(
                        selected = filter.contextId == context.id,
                        onClick = { callbacks.toggleContext(context.id) },
                        label = { Text(context.name) },
                        modifier = Modifier.testTag(NextTags.context(context.id)),
                    )
                }
            }
        }
        FilterGroup(stringResource(R.string.next_filter_energy)) {
            Energy.entries.forEach { energy ->
                FilterChip(
                    selected = filter.energy == energy,
                    onClick = { callbacks.toggleEnergy(energy) },
                    label = { Text(stringResource(energyLabel(energy))) },
                    modifier = Modifier.testTag(NextTags.energy(energy)),
                )
            }
        }
        FilterGroup(stringResource(R.string.next_filter_creativity)) {
            Creativity.entries.forEach { creativity ->
                FilterChip(
                    selected = filter.creativity == creativity,
                    onClick = { callbacks.toggleCreativity(creativity) },
                    label = { Text(stringResource(creativityLabel(creativity))) },
                    modifier = Modifier.testTag(NextTags.creativity(creativity)),
                )
            }
        }
        FilterGroup(stringResource(R.string.next_filter_estimate)) {
            NextFilter.ESTIMATES.forEach { minutes ->
                FilterChip(
                    selected = filter.maxMinutes == minutes,
                    onClick = { callbacks.toggleMaxMinutes(minutes) },
                    label = { Text(stringResource(R.string.next_filter_at_most, minutes)) },
                    modifier = Modifier.testTag(NextTags.estimate(minutes)),
                )
            }
        }
        if (filter.activeCount > 0) {
            TextButton(onClick = callbacks.clearFilter, modifier = Modifier.testTag(NextTags.CLEAR)) {
                Text(stringResource(R.string.next_filter_clear))
            }
        }
    }
}

@Composable
private fun FilterGroup(
    title: String,
    chips: @Composable () -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
}

@Composable
private fun Empty(
    state: NextUiState,
    callbacks: NextCallbacks,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp).testTag(NextTags.EMPTY),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.filter.activeCount > 0 && state.total > 0) {
            Text(stringResource(R.string.next_empty_filtered), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = callbacks.clearFilter) { Text(stringResource(R.string.next_filter_clear)) }
        } else {
            Text(stringResource(R.string.next_empty), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun energyLabel(energy: Energy) =
    when (energy) {
        Energy.LOW -> R.string.next_energy_low
        Energy.MEDIUM -> R.string.next_energy_medium
        Energy.HIGH -> R.string.next_energy_high
    }

private fun creativityLabel(creativity: Creativity) =
    when (creativity) {
        Creativity.ROUTINE -> R.string.next_creativity_routine
        Creativity.MIXED -> R.string.next_creativity_mixed
        Creativity.CREATIVE -> R.string.next_creativity_creative
    }
