package ch.sonensei.cockpit.contacts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.PlaceholderScreen

@Composable
fun ContactsScreen(modifier: Modifier = Modifier) = PlaceholderScreen(R.string.nav_contacts, Icons.Outlined.Contacts, modifier)
