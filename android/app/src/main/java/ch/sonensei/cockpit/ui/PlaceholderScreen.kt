package ch.sonensei.cockpit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/** Scaffold placeholder; the real screens are built by the board's tasks. */
fun placeholderText(appName: String): String = "$appName: scaffold"

@Composable
fun PlaceholderScreen() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Text(placeholderText("My Cockpit"))
            }
        }
    }
}

@Preview
@Composable
private fun PlaceholderScreenPreview() = PlaceholderScreen()
