package ch.sonensei.cockpit.capture.entry

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.capture.share.CaptureSheet
import ch.sonensei.cockpit.capture.share.SavedAndClearedToast
import ch.sonensei.cockpit.capture.share.ShareViewModel
import ch.sonensei.cockpit.core.settings.UserSettingsRepository
import ch.sonensei.cockpit.ui.theme.CockpitSettingsTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The empty capture sheet behind the widget, the quick-settings tile and the app shortcut (SPEC.md §4.5). It is the
 * share target's sheet without shared content: type a title, save, and the capture is queued (offline first).
 */
@AndroidEntryPoint
class CaptureActivity : ComponentActivity() {
    private val viewModel: ShareViewModel by viewModels()

    @Inject lateinit var userSettings: UserSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CockpitSettingsTheme(userSettings.settings) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(state.saved) {
                    if (state.saved) {
                        Toast.makeText(this@CaptureActivity, R.string.share_saved, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                SavedAndClearedToast(state)
                CaptureSheet(
                    state = state,
                    onTextChange = viewModel::onTextChange,
                    onNotesChange = viewModel::onNotesChange,
                    onSave = { viewModel.save() },
                    onCancel = ::finish,
                    autoFocus = true,
                    onSaveAndAddAnother = { viewModel.save(addAnother = true) },
                    onAddFiles = viewModel::addFiles,
                    onRemoveFile = viewModel::removeFile,
                )
            }
        }
    }
}
