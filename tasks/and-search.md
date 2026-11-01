---
title: Android: global search
status: in_progress
owner: dell26/agent-2
deps: [and-shell,p0-search-core,and-capture-button]
touches: [android/app/src/main/java/ch/sonensei/cockpit/search,android/app/src/main/java/ch/sonensei/cockpit/ui,android/app/src/test/java/ch/sonensei/cockpit/search,android/app/src/test/java/ch/sonensei/cockpit/ui,android/app/src/main/res/values/strings_search.xml,android/app/src/main/res/values-de/strings_search.xml]
prio: 2
lease_until: 2026-11-01T14:49:49Z
---
## Goal
Global search (SPEC.md §4.4, web Ctrl+K) is missing on Android. Use the existing `GET /api/v1/search?q=&types=`;
server unchanged.

## Acceptance criteria
- [ ] A search action in the app bar of every top-level screen opens a search screen with a field that searches as you type (debounced) via `/api/v1/search`; optional type filter chips (tasks, projects, notes, events, emails, contacts, files, bookmarks, journal)
- [ ] Results show type icon, title and subtitle; tapping one opens the item in the matching Android screen where one exists; items without an Android detail screen open their web deep link in the browser
- [ ] Loading, empty ("Nothing found") and error states; works with the on-screen keyboard (IME search action)
- [ ] All new UI strings exist in English and German (`res/values`, `res/values-de`)
- [ ] Unit tests (ViewModel, plus Compose UI tests where the project has them for similar screens) cover the criteria above; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
Requested by the owner (2026-11-01) after trying the Android app on the phone. If and-capture-button is in progress at the same time, both add app-bar actions in the shell: keep both.
