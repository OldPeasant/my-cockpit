---
title: Android app shell: theme, navigation, placeholders
status: in_progress
owner: dell26/agent-1
deps: [and-deps]
touches: [android/app/src/main/java/ch/sonensei/cockpit,android/app/src/test/java/ch/sonensei/cockpit,android/app/src/main/res]
prio: 1
lease_until: 2026-10-20T09:46:58Z
---
## Goal
App skeleton every feature plugs into, with one placeholder screen per feature package so feature tasks only touch their package.

## Acceptance criteria
- [x] `CockpitApp` (`@HiltAndroidApp`), Material 3 theme (dynamic color + dark), navigation (bottom bar: Today, Inbox, Calendar, Mail, More; More lists Next, Projects, Waiting, Someday, Notes, Files, Contacts, Bookmarks, Journal, Habits, Review, Settings)
  - verified (2026-10-20 11:27+0200 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.*' --rerun-tasks -> 43 passed: MainActivityLaunchTest (real MainActivity in Hilt CockpitApp from manifest), CockpitThemeTest (Robolectric sdk 30 fallback light/dark, sdk 31/34 dynamic), CockpitDestinationTest (bottom bar Today/Inbox/Calendar/Mail/More, More list Next..Settings in order), CockpitShellTest/NavigationTest (every More item opens its screen)
- [x] One package per feature (`gtd/inbox`, `calendar`, `mail`, `notes`, …) with a placeholder `<Feature>Screen`; listed in `android/README.md`
  - verified (2026-10-20 11:27+0200 by dell26/agent-1): 16 packages (gtd/today, gtd/inbox, calendar/agenda, mail/list, gtd/next, gtd/projects, gtd/lists, notes, files, contacts, bookmarks, journal, habits, review, settings) each with a placeholder <Feature>Screen, listed in the android/README.md table (read); CockpitShellNavigationTest opens every one through the shell -> pass
- [x] All strings in `res/values/strings.xml`
  - verified (2026-10-20 11:27+0200 by dell26/agent-1): grep -rnE 'Text\("|contentDescription = "|label = "|title = "' android/app/src/main/java -> no matches; all 20 UI strings in res/values/strings.xml; ./gradlew lintDebug (warningsAsErrors, includes HardcodedText) -> BUILD SUCCESSFUL
- [x] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.*'` passes, covering navigation between bottom-bar destinations (Robolectric/Compose UI tests for screens)
  - verified (2026-10-20 11:27+0200 by dell26/agent-1): cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.*' --rerun-tasks -> BUILD SUCCESSFUL, 43 tests 0 failures 0 skipped (Robolectric Compose UI: bottom-bar switching Today/Inbox/Calendar/Mail/More with selection, More items, back, tab state kept across More)

## Notes
- ASSUMPTION (2026-10-20 11:08+0200 by dell26/agent-1): Placeholder screens live in the subpackage the owning feature task touches (Calendar in calendar/agenda, Mail in mail/list, Waiting+Someday in gtd/lists, Bookmarks in a new bookmarks package), so feature tasks replace them without editing the shell; signature <Feature>Screen(modifier).
- ASSUMPTION (2026-10-20 11:08+0200 by dell26/agent-1): Navigation uses string routes from a CockpitDestination enum (not type-safe @Serializable routes) because no destination takes arguments yet; reselecting More from a More sub-screen returns to the More list instead of restoring it.
- ASSUMPTION (2026-10-20 11:08+0200 by dell26/agent-1): AndroidManifest.xml (outside touches) changed by one line, android:name=.CockpitApp, required for @HiltAndroidApp; android/README.md created because the criterion requires it.
