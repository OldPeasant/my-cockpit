package ch.sonensei.cockpit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ch.sonensei.cockpit.ui.navigation.CockpitDestination

/** Lists the destinations that do not fit into the bottom bar. */
@Composable
fun MoreScreen(
    onOpen: (CockpitDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(CockpitDestination.moreItems, key = { it.route }) { destination ->
            ListItem(
                headlineContent = { Text(stringResource(destination.labelRes)) },
                leadingContent = { Icon(destination.icon, contentDescription = null) },
                modifier = Modifier.clickable { onOpen(destination) },
            )
        }
    }
}
