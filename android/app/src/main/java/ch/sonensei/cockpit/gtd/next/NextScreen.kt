package ch.sonensei.cockpit.gtd.next

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.core.settings.userCreativityLabel
import ch.sonensei.cockpit.core.settings.userEnergyLabel
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.ui.LocalFabClearance
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
    val retry: () -> Unit = {},
    val toggleDue: (DueWindow) -> Unit = {},
    val clearDue: () -> Unit = {},
    val chooseArea: (UUID?) -> Unit = {},
    val chooseProject: (UUID?) -> Unit = {},
    val applyPreset: (NextPreset) -> Unit = {},
    val removePreset: (NextPreset) -> Unit = {},
    val openSavePreset: () -> Unit = {},
    val editPresetName: (String) -> Unit = {},
    val savePreset: () -> Unit = {},
    val cancelSavePreset: () -> Unit = {},
) {
    companion object {
        fun of(vm: NextViewModel) =
            NextCallbacks(
                toggleContext = vm::toggleContext,
                toggleEnergy = vm::toggleEnergy,
                toggleCreativity = vm::toggleCreativity,
                toggleMaxMinutes = vm::toggleMaxMinutes,
                clearFilter = vm::clearFilter,
                complete = vm::complete,
                undo = vm::undo,
                dismiss = vm::dismiss,
                retry = vm::retry,
                toggleDue = vm::toggleDue,
                clearDue = vm::clearDue,
                chooseArea = vm::chooseArea,
                chooseProject = vm::chooseProject,
                applyPreset = vm::applyPreset,
                removePreset = vm::removePreset,
                openSavePreset = vm::openSavePreset,
                editPresetName = vm::editPresetName,
                savePreset = vm::savePreset,
                cancelSavePreset = vm::cancelSavePreset,
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

    const val DUE_ANY = "next-filter-due-any"

    fun due(due: DueWindow) = "next-filter-due-${due.wire}"

    const val AREA = "next-filter-area"
    const val PROJECT = "next-filter-project"
    const val ANY_AREA = "next-filter-area-any"
    const val ANY_PROJECT = "next-filter-project-any"

    fun area(id: UUID) = "next-filter-area-$id"

    fun project(id: UUID) = "next-filter-project-$id"

    const val PRESETS = "next-presets"
    const val PRESET_SAVE = "next-preset-save"
    const val PRESET_MESSAGE = "next-preset-message"
    const val PRESET_DIALOG = "next-preset-dialog"
    const val PRESET_NAME = "next-preset-name"
    const val PRESET_CONFIRM = "next-preset-confirm"
    const val PRESET_CANCEL = "next-preset-cancel"
    const val PRESET_DIALOG_MESSAGE = "next-preset-dialog-message"

    fun preset(index: Int) = "next-preset-$index"

    fun presetDelete(index: Int) = "next-preset-delete-$index"
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
    val contexts = remember(state.contexts) { state.contexts.associateBy { it.id } }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.offline) OfflineBanner(state.refreshedAt)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        state.loading -> stringResource(R.string.tasks_loading)
                        state.firstLoadFailed -> stringResource(R.string.nav_next)
                        state.filter.activeCount > 0 ->
                            pluralStringResource(R.plurals.next_count_filtered, state.total, state.tasks.size, state.total)
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
            Presets(state, callbacks)
            if (filtersOpen) Filters(state, callbacks)
            HorizontalDivider()
            when {
                state.tasks.isNotEmpty() ->
                    LazyColumn(
                        Modifier.fillMaxSize().testTag(NextTags.LIST),
                        contentPadding = PaddingValues(bottom = LocalFabClearance.current),
                    ) {
                        items(state.tasks, key = { it.id }) { task ->
                            TaskRow(task, contexts, state.today, state.offline, callbacks.complete)
                        }
                    }
                state.firstLoadFailed -> LoadFailed(callbacks.retry, Modifier.testTag(TaskListTags.ERROR))
                !state.loading -> Empty(state, callbacks)
            }
        }
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalFabClearance.current))
    }
}

@Composable
private fun Filters(
    state: NextUiState,
    callbacks: NextCallbacks,
) {
    val filter = state.filter
    // At most half the screen, scrolling, so the list stays in view on small or landscape screens.
    val maxHeight = (LocalConfiguration.current.screenHeightDp / 2).dp
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .testTag(NextTags.FILTERS),
    ) {
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
                    label = { Text(userEnergyLabel(energy.name, energyLabel(energy))) },
                    modifier = Modifier.testTag(NextTags.energy(energy)),
                )
            }
        }
        FilterGroup(stringResource(R.string.next_filter_creativity)) {
            Creativity.entries.forEach { creativity ->
                FilterChip(
                    selected = filter.creativity == creativity,
                    onClick = { callbacks.toggleCreativity(creativity) },
                    label = { Text(userCreativityLabel(creativity.name, creativityLabel(creativity))) },
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
        FilterGroup(stringResource(R.string.next_filter_due)) {
            FilterChip(
                selected = filter.due == null,
                onClick = callbacks.clearDue,
                label = { Text(stringResource(R.string.next_due_any)) },
                modifier = Modifier.testTag(NextTags.DUE_ANY),
            )
            DueWindow.entries.forEach { due ->
                FilterChip(
                    selected = filter.due == due,
                    onClick = { callbacks.toggleDue(due) },
                    label = { Text(stringResource(dueLabel(due))) },
                    modifier = Modifier.testTag(NextTags.due(due)),
                )
            }
        }
        FilterGroup(stringResource(R.string.next_filter_where)) {
            ChoiceMenu(
                title = stringResource(R.string.next_filter_area),
                anyLabel = stringResource(R.string.next_filter_any_area),
                choices = state.areas.map { it.id to it.title },
                selected = filter.areaId,
                tag = NextTags.AREA,
                anyTag = NextTags.ANY_AREA,
                choiceTag = NextTags::area,
                onChoose = callbacks.chooseArea,
            )
            ChoiceMenu(
                title = stringResource(R.string.next_filter_project),
                anyLabel = stringResource(R.string.next_filter_any_project),
                choices = state.projects.map { it.id to it.title },
                selected = filter.projectId,
                tag = NextTags.PROJECT,
                anyTag = NextTags.ANY_PROJECT,
                choiceTag = NextTags::project,
                onChoose = callbacks.chooseProject,
            )
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

/**
 * A button showing the chosen one of [choices] (id to title) that opens a menu to choose another or [anyLabel]. A chosen
 * id that is not among the choices (not loaded, or of another area) shows only [title].
 */
@Composable
private fun ChoiceMenu(
    title: String,
    anyLabel: String,
    choices: List<Pair<UUID, String>>,
    selected: UUID?,
    tag: String,
    anyTag: String,
    choiceTag: (UUID) -> String,
    onChoose: (UUID?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        val shown =
            when (selected) {
                null -> anyLabel
                else -> choices.firstOrNull { it.first == selected }?.second ?: title
            }
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = { Text(shown) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.testTag(tag).semantics { contentDescription = "$title: $shown" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(anyLabel) },
                onClick = {
                    open = false
                    onChoose(null)
                },
                modifier = Modifier.testTag(anyTag),
            )
            choices.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        onChoose(id)
                    },
                    modifier = Modifier.testTag(choiceTag(id)),
                )
            }
        }
    }
}

private fun dueLabel(due: DueWindow) =
    when (due) {
        DueWindow.OVERDUE -> R.string.next_due_overdue
        DueWindow.TODAY -> R.string.next_due_today
        DueWindow.WEEK -> R.string.next_due_week
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
