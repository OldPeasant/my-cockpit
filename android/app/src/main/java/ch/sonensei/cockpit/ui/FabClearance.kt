package ch.sonensei.cockpit.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Space at the bottom of a screen's content taken by the shell's capture FAB: the FAB's height plus its 16 dp margin
 * above the content's bottom edge (Material 3 Scaffold). Screens that show their own snackbars pad them by it, so the
 * FAB never covers a snackbar action such as Undo. 0 dp on screens without the shell FAB (and outside the shell).
 */
val LocalFabClearance = compositionLocalOf { 0.dp }

/** The clearance the shell provides while its capture FAB is shown: 56 dp FAB plus the Scaffold's 16 dp spacing. */
val ShellFabClearance: Dp = 72.dp
