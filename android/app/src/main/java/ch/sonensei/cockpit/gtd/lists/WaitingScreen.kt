package ch.sonensei.cockpit.gtd.lists

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.PlaceholderScreen

@Composable
fun WaitingScreen(modifier: Modifier = Modifier) = PlaceholderScreen(R.string.nav_waiting, Icons.Outlined.HourglassEmpty, modifier)
