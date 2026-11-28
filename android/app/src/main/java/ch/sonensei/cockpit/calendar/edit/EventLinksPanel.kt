package ch.sonensei.cockpit.calendar.edit

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import ch.sonensei.cockpit.core.links.ItemRef
import ch.sonensei.cockpit.core.links.LinkedItemsSection
import ch.sonensei.cockpit.core.links.LinkedItemsViewModel
import ch.sonensei.cockpit.core.links.linksViewModelKey
import ch.sonensei.cockpit.search.SearchTarget
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.util.UUID

/** The type of events in the link system. */
internal const val EVENT_ITEM_TYPE = "EVENT"

/**
 * The shared linked-items panel of the event [eventId] (add, remove, open), as on the web. A higher [refresh] (a task
 * or note was created from the event) reloads it, so the created item shows.
 */
@Composable
fun EventLinksPanel(
    eventId: UUID,
    refresh: Int,
    open: (SearchTarget) -> Unit,
) {
    // Without Hilt (screen tests with fake view models) there is no view model to show the panel with.
    if (LocalActivity.current !is GeneratedComponentManagerHolder) return
    val item = ItemRef(EVENT_ITEM_TYPE, eventId)
    val viewModel: LinkedItemsViewModel = hiltViewModel(key = linksViewModelKey(item))
    LaunchedEffect(viewModel, refresh) { if (refresh > 0) viewModel.reload() }
    LinkedItemsSection(item, open = open, viewModel = viewModel)
}
