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
- [ ] One package per feature (`gtd/inbox`, `calendar`, `mail`, `notes`, …) with a placeholder `<Feature>Screen`; listed in `android/README.md`
- [ ] All strings in `res/values/strings.xml`
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.*'` passes, covering navigation between bottom-bar destinations (Robolectric/Compose UI tests for screens)

## Notes
- ASSUMPTION (2026-10-20 11:08+0200 by dell26/agent-1): Placeholder screens live in the subpackage the owning feature task touches (Calendar in calendar/agenda, Mail in mail/list, Waiting+Someday in gtd/lists, Bookmarks in a new bookmarks package), so feature tasks replace them without editing the shell; signature <Feature>Screen(modifier).
- ASSUMPTION (2026-10-20 11:08+0200 by dell26/agent-1): Navigation uses string routes from a CockpitDestination enum (not type-safe @Serializable routes) because no destination takes arguments yet; reselecting More from a More sub-screen returns to the More list instead of restoring it.
- ASSUMPTION (2026-10-20 11:08+0200 by dell26/agent-1): AndroidManifest.xml (outside touches) changed by one line, android:name=.CockpitApp, required for @HiltAndroidApp; android/README.md created because the criterion requires it.
