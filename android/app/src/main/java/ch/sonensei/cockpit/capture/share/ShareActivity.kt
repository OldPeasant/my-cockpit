package ch.sonensei.cockpit.capture.share

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
import ch.sonensei.cockpit.core.settings.UserSettingsRepository
import ch.sonensei.cockpit.ui.theme.CockpitSettingsTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Share target (SPEC.md §4.5): a compact capture sheet over the sending app. It closes only after the capture is
 * queued, so the shared content URIs are copied into the queue's storage while our read grant is still valid.
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    private val viewModel: ShareViewModel by viewModels()

    @Inject lateinit var userSettings: UserSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = ShareIntentParser.parse(intent)
        if (content == null) {
            Toast.makeText(this, R.string.share_nothing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        viewModel.load(content)
        enableEdgeToEdge()
        setContent {
            CockpitSettingsTheme(userSettings.settings) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(state.saved) {
                    if (state.saved) {
                        val saved = if (state.readLater) R.string.share_saved_read_later else R.string.share_saved
                        Toast.makeText(this@ShareActivity, saved, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                CaptureSheet(
                    state = state,
                    onTextChange = viewModel::onTextChange,
                    onNotesChange = viewModel::onNotesChange,
                    onSave = viewModel::save,
                    onCancel = ::finish,
                    onDestinationChange = viewModel::onDestinationChange,
                )
            }
        }
    }
}
