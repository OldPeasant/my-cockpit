---
title: Android: global search
status: in_progress
owner: dell26/agent-2
deps: [and-shell,p0-search-core,and-capture-button]
touches: [android/app/src/main/java/ch/sonensei/cockpit/search,android/app/src/main/java/ch/sonensei/cockpit/ui,android/app/src/test/java/ch/sonensei/cockpit/search,android/app/src/test/java/ch/sonensei/cockpit/ui,android/app/src/main/res/values/strings_search.xml,android/app/src/main/res/values-de/strings_search.xml]
prio: 2
lease_until: 2026-11-01T15:14:00Z
---
## Goal
Global search (SPEC.md §4.4, web Ctrl+K) is missing on Android. Use the existing `GET /api/v1/search?q=&types=`;
server unchanged.

## Acceptance criteria
- [x] A search action in the app bar of every top-level screen opens a search screen with a field that searches as you type (debounced) via `/api/v1/search`; optional type filter chips (tasks, projects, notes, events, emails, contacts, files, bookmarks, journal)
  - verified (2026-11-01 15:53+0100 by dell26/agent-2): cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.search.*' --tests 'ch.sonensei.cockpit.ui.CockpitShellSearchTest' -> 72 passed, 0 failed; CockpitShellSearchTest (search action on every destination opens search), SearchViewModelTest debounce (last text only), SearchRepositoryTest filter->types, SearchScreenTest filter chips, SearchRouteInAppTest typing hits /api/v1/search (MockWebServer)
- [x] Results show type icon, title and subtitle; tapping one opens the item in the matching Android screen where one exists; items without an Android detail screen open their web deep link in the browser
  - verified (2026-11-01 15:54+0100 by dell26/agent-2): same focused gradle run -> pass; SearchScreenTest results show headings/titles/subtitles/type icons; SearchRouteInAppTest: contact/email/note open in-app screens, task deep link opens browser below server URL, no-browser fallback; SearchModelsTest routing
- [x] Loading, empty ("Nothing found") and error states; works with the on-screen keyboard (IME search action)
  - verified (2026-11-01 15:54+0100 by dell26/agent-2): same focused gradle run -> pass; SearchViewModelTest/SearchScreenTest loading, 'Nothing found', offline/failed + retry, out-of-order answers; IME search action via performImeAction in SearchScreenTest and SearchRouteInAppTest
- [ ] All new UI strings exist in English and German (`res/values`, `res/values-de`)
- [ ] Unit tests (ViewModel, plus Compose UI tests where the project has them for similar screens) cover the criteria above; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
Requested by the owner (2026-11-01) after trying the Android app on the phone. If and-capture-button is in progress at the same time, both add app-bar actions in the shell: keep both.
- ASSUMPTION (2026-11-01 15:53+0100 by dell26/agent-2): Search hits for notes, emails and contacts open in-app detail screens; tasks, projects, events (and other types) open their web deep link in the browser, because the Android project/event/task screens cannot yet be opened by item id from outside their own feature
