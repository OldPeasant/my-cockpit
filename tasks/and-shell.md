---
title: Android app shell: theme, navigation, placeholders
status: todo
owner:
deps: [and-deps]
touches: [android/app/src/main/java/ch/sonensei/cockpit,android/app/src/test/java/ch/sonensei/cockpit,android/app/src/main/res]
prio: 1
---
## Goal
App skeleton every feature plugs into, with one placeholder screen per feature package so feature tasks only touch their package.

## Acceptance criteria
- [ ] `CockpitApp` (`@HiltAndroidApp`), Material 3 theme (dynamic color + dark), navigation (bottom bar: Today, Inbox, Calendar, Mail, More; More lists Next, Projects, Waiting, Someday, Notes, Files, Contacts, Bookmarks, Journal, Habits, Review, Settings)
- [ ] One package per feature (`gtd/inbox`, `calendar`, `mail`, `notes`, …) with a placeholder `<Feature>Screen`; listed in `android/README.md`
- [ ] All strings in `res/values/strings.xml`
- [ ] `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.*'` passes, covering navigation between bottom-bar destinations (Robolectric/Compose UI tests for screens)

## Notes
