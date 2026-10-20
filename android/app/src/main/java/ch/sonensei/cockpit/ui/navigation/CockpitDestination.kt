package ch.sonensei.cockpit.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.ui.graphics.vector.ImageVector
import ch.sonensei.cockpit.R

/** Where a destination is reached from: the bottom bar, or the list on the More screen. */
enum class NavSection { BottomBar, More }

/** Every screen of the shell. Order within a section is the order shown in the bottom bar or the More list. */
enum class CockpitDestination(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val section: NavSection,
) {
    Today("today", R.string.nav_today, Icons.Outlined.Today, NavSection.BottomBar),
    Inbox("inbox", R.string.nav_inbox, Icons.Outlined.Inbox, NavSection.BottomBar),
    Calendar("calendar", R.string.nav_calendar, Icons.Outlined.CalendarMonth, NavSection.BottomBar),
    Mail("mail", R.string.nav_mail, Icons.Outlined.Mail, NavSection.BottomBar),
    More("more", R.string.nav_more, Icons.Outlined.MoreHoriz, NavSection.BottomBar),
    Next("next", R.string.nav_next, Icons.Outlined.Checklist, NavSection.More),
    Projects("projects", R.string.nav_projects, Icons.Outlined.AccountTree, NavSection.More),
    Waiting("waiting", R.string.nav_waiting, Icons.Outlined.HourglassEmpty, NavSection.More),
    Someday("someday", R.string.nav_someday, Icons.Outlined.Lightbulb, NavSection.More),
    Notes("notes", R.string.nav_notes, Icons.Outlined.Description, NavSection.More),
    Files("files", R.string.nav_files, Icons.Outlined.Folder, NavSection.More),
    Contacts("contacts", R.string.nav_contacts, Icons.Outlined.Contacts, NavSection.More),
    Bookmarks("bookmarks", R.string.nav_bookmarks, Icons.Outlined.Bookmarks, NavSection.More),
    Journal("journal", R.string.nav_journal, Icons.AutoMirrored.Outlined.MenuBook, NavSection.More),
    Habits("habits", R.string.nav_habits, Icons.Outlined.Repeat, NavSection.More),
    Review("review", R.string.nav_review, Icons.Outlined.EventRepeat, NavSection.More),
    Settings("settings", R.string.nav_settings, Icons.Outlined.Settings, NavSection.More),
    ;

    companion object {
        val start = Today
        val bottomBar: List<CockpitDestination> = entries.filter { it.section == NavSection.BottomBar }
        val moreItems: List<CockpitDestination> = entries.filter { it.section == NavSection.More }

        fun fromRoute(route: String?): CockpitDestination? = entries.firstOrNull { it.route == route }

        /** The bottom-bar item to highlight: More stays selected while one of its screens is open. */
        fun bottomBarItemFor(destination: CockpitDestination?): CockpitDestination? =
            when (destination?.section) {
                NavSection.BottomBar -> destination
                NavSection.More -> More
                null -> null
            }
    }
}
