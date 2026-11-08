package ch.sonensei.cockpit.core.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.stringResource

/** The synced settings for the screens; provided at the root of each activity, `null` while unknown. */
val LocalUserSettings = compositionLocalOf<UserSettings?> { null }

/** The user's label of an energy level (`LOW`, `MEDIUM`, `HIGH`), else the app's translation [fallback]. */
@Composable
fun userEnergyLabel(
    level: String,
    @StringRes fallback: Int,
): String = LocalUserSettings.current?.energyLabels?.get(level) ?: stringResource(fallback)

/** The user's label of a creativity level (`ROUTINE`, `MIXED`, `CREATIVE`), else the app's translation [fallback]. */
@Composable
fun userCreativityLabel(
    level: String,
    @StringRes fallback: Int,
): String = LocalUserSettings.current?.creativityLabels?.get(level) ?: stringResource(fallback)
