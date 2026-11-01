---
title: Android: Bookmarks screen (list, read later, open, edit)
status: in_progress
owner: dell26/agent-1
deps: [and-bookmarks-share,and-shell]
touches: [android/app/src/main/java/ch/sonensei/cockpit/bookmarks,android/app/src/test/java/ch/sonensei/cockpit/bookmarks,android/app/src/main/res/values/strings_bookmarks.xml,android/app/src/main/res/values-de/strings_bookmarks.xml]
prio: 2
lease_until: 2026-11-01T13:58:36Z
---
## Goal
The Android Bookmarks tab is still a placeholder ("This part of your cockpit is not built yet"); only the share
target exists (and-bookmarks-share). Build the screen per SPEC.md §8.4 and §9.2 item 8, using the existing server API
(`/api/v1/bookmarks`, `/tags`, `/{id}`, `/{id}/refresh-metadata`, `/{id}/snapshot`); server unchanged. The web
bookmarks feature (`web/src/app/features/bookmarks`) is the reference for behavior.

## Acceptance criteria
- [x] The Bookmarks tab lists bookmarks with title, site/domain, favicon or preview image where the API provides one, and tags; views "Read later" (the queue, default) and "All", plus a tag filter and a search field
  - verified (2026-11-01 14:41+0100 by dell26/agent-1): cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.bookmarks.*' -> 67 passed (BookmarksScreenTest rows show title/site/favicon-or-initial/tags, view switch Read later default/All, tag filter, search; BookmarksViewModelTest queue/all/tag/search; RepositoryTest query params)
- [x] Tapping a bookmark opens its URL in the browser (Custom Tab or default browser); actions per bookmark: mark read/unread, edit title/tags/notes, delete (to Trash, with undo snackbar where the app has that pattern)
  - verified (2026-11-01 14:41+0100 by dell26/agent-1): same run -> pass: BookmarksRouteTest tap starts ACTION_VIEW with URL + no-browser snackbar; ScreenTest/ViewModelTest mark read/unread, edit title/tags/notes (draft kept on failure), delete to Trash with undo snackbar (restore endpoint, MockWebServer RepositoryTest)
- [x] Pull to refresh; loading, empty and error states follow the app's existing list screens (e.g. Notes, Files)
  - verified (2026-11-01 14:41+0100 by dell26/agent-1): same run -> pass: ScreenTest pull-to-refresh swipe calls refresh, loading bar, empty states per view/tag, error banner with retry, offline texts (EN/DE)
- [x] A bookmark saved via the share target appears in the list without restarting the app
  - verified (2026-11-01 14:41+0100 by dell26/agent-1): same run -> pass: BookmarksRouteTest reloads on lifecycle RESUMED showing a bookmark shared meanwhile; ViewModelTest reloads when CaptureQueue pendingCount drops
- [x] All new UI strings exist in English and German (`res/values`, `res/values-de`)
  - verified (2026-11-01 14:41+0100 by dell26/agent-1): values/ and values-de/strings_bookmarks.xml both 32 keys; ScreenTest 'translated to German' passes; ./gradlew lintDebug (MissingTranslation enforced) -> 0 errors 0 warnings
- [x] Unit tests (ViewModel, plus Compose UI tests where the project has them for similar screens) cover the criteria above; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes
  - verified (2026-11-01 14:41+0100 by dell26/agent-1): cd android && ./gradlew ktlintCheck createDebugUnitTestCoverageReport lintDebug (runs full testDebugUnitTest) -> exit 0; 2313 tests 0 failures; lint 0 errors 0 warnings; bookmarks 67 tests (ViewModel, Screen/Route Robolectric Compose, Repository, Models)

## Notes
Requested by the owner (2026-11-01) after trying the Android app on the phone. Out of scope: snapshot (offline copy) viewer if the API only serves HTML for the web; open the snapshot via the web deep link instead and record that.
- ASSUMPTION (2026-11-01 14:08+0100 by dell26/agent-1): Search is client-side over the loaded bookmarks of the current view/tag (title, site, URL, notes, tags; every word must match): GET /bookmarks has no text query param and the server is unchanged by the task.
- ASSUMPTION (2026-11-01 14:08+0100 by dell26/agent-1): Notes in 'edit title/tags/notes' = the bookmark's description field (the API has no separate notes field); blank title/notes clear them (null).
- ASSUMPTION (2026-11-01 14:08+0100 by dell26/agent-1): Site icons: Android loads the server-provided https favicon directly from the site (separate Coil loader without the Cockpit token, memory cache only), falling back to the site's initial; the web deliberately shows no remote favicon (CSP/IP). The API has no preview image, so only favicons are shown.
- ASSUMPTION (2026-11-01 14:08+0100 by dell26/agent-1): Opening uses ACTION_VIEW (default browser), not Custom Tabs: androidx.browser is not a dependency and the criterion allows either. Snapshot viewer is out of scope; no snapshot action on Android.
- ASSUMPTION (2026-11-01 14:08+0100 by dell26/agent-1): Share target appears without restart: the list reloads on every resume (LifecycleResumeEffect) and whenever CaptureQueue.status pendingCount drops (a queued share was sent). Undo after delete uses POST /trash/BOOKMARK/{id}/restore via the screen's own Retrofit interface (no TrashApi binding, to avoid clashing with and-trash). The tag filter applies in both views.
