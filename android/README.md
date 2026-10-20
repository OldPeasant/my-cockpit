# My Cockpit: Android app

Kotlin + Jetpack Compose (Material 3), Hilt, Navigation Compose. Stack, install and commands are in
[`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).

```bash
./gradlew ktlintCheck testDebugUnitTest lintDebug   # lint + unit tests (Robolectric Compose UI tests included)
./gradlew installDebug                              # needs a device or emulator
```

## Shell

- `CockpitApp`: the `@HiltAndroidApp` application. `MainActivity` (`@AndroidEntryPoint`) shows `CockpitShell`
  inside `CockpitTheme`.
- `ui/theme`: `CockpitTheme`, Material 3 with dynamic color on Android 12+, a fallback palette before that, and
  light or dark following the system.
- `ui/navigation/CockpitDestination`: every destination with its route, label, icon and section. The bottom bar
  holds Today, Inbox, Calendar, Mail and More. The More screen lists the rest.
- `ui/CockpitShell`: scaffold (top bar, bottom bar) and the `NavHost` that maps each destination to its screen.
- `ui/PlaceholderScreen`: shown by every screen that is not built yet.

## Feature packages

Each feature owns one package under `ch.sonensei.cockpit` and replaces its placeholder screen in place. The
signature stays `<Feature>Screen(modifier: Modifier = Modifier)`, so the shell does not change.

| Destination | Section | Package | Screen |
|---|---|---|---|
| Today | bottom bar | `gtd/today` | `TodayScreen` |
| Inbox | bottom bar | `gtd/inbox` | `InboxScreen` |
| Calendar | bottom bar | `calendar/agenda` | `CalendarScreen` |
| Mail | bottom bar | `mail/list` | `MailScreen` |
| Next | More | `gtd/next` | `NextScreen` |
| Projects | More | `gtd/projects` | `ProjectsScreen` |
| Waiting | More | `gtd/lists` | `WaitingScreen` |
| Someday | More | `gtd/lists` | `SomedayScreen` |
| Notes | More | `notes` | `NotesScreen` |
| Files | More | `files` | `FilesScreen` |
| Contacts | More | `contacts` | `ContactsScreen` |
| Bookmarks | More | `bookmarks` | `BookmarksScreen` |
| Journal | More | `journal` | `JournalScreen` |
| Habits | More | `habits` | `HabitsScreen` |
| Review | More | `review` | `ReviewScreen` |
| Settings | More | `settings` | `SettingsScreen` |

Other feature packages have no screen of their own in the shell: `auth` (login, passkeys), `capture/*`
(capture queue, entry points, share target), `calendar/edit`, `mail/read`, `mail/compose`, `gtd/data`,
`reminders`, `core/network`.

All user-facing strings live in `app/src/main/res/values/strings.xml`.
