---
title: Android: Bookmarks screen (list, read later, open, edit)
status: in_progress
owner: dell26/agent-1
deps: [and-bookmarks-share,and-shell]
touches: [android/app/src/main/java/ch/sonensei/cockpit/bookmarks,android/app/src/test/java/ch/sonensei/cockpit/bookmarks,android/app/src/main/res/values/strings_bookmarks.xml,android/app/src/main/res/values-de/strings_bookmarks.xml]
prio: 2
lease_until: 2026-11-01T13:28:18Z
---
## Goal
The Android Bookmarks tab is still a placeholder ("This part of your cockpit is not built yet"); only the share
target exists (and-bookmarks-share). Build the screen per SPEC.md §8.4 and §9.2 item 8, using the existing server API
(`/api/v1/bookmarks`, `/tags`, `/{id}`, `/{id}/refresh-metadata`, `/{id}/snapshot`); server unchanged. The web
bookmarks feature (`web/src/app/features/bookmarks`) is the reference for behavior.

## Acceptance criteria
- [ ] The Bookmarks tab lists bookmarks with title, site/domain, favicon or preview image where the API provides one, and tags; views "Read later" (the queue, default) and "All", plus a tag filter and a search field
- [ ] Tapping a bookmark opens its URL in the browser (Custom Tab or default browser); actions per bookmark: mark read/unread, edit title/tags/notes, delete (to Trash, with undo snackbar where the app has that pattern)
- [ ] Pull to refresh; loading, empty and error states follow the app's existing list screens (e.g. Notes, Files)
- [ ] A bookmark saved via the share target appears in the list without restarting the app
- [ ] All new UI strings exist in English and German (`res/values`, `res/values-de`)
- [ ] Unit tests (ViewModel, plus Compose UI tests where the project has them for similar screens) cover the criteria above; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
Requested by the owner (2026-11-01) after trying the Android app on the phone. Out of scope: snapshot (offline copy) viewer if the API only serves HTML for the web; open the snapshot via the web deep link instead and record that.
- ASSUMPTION (2026-11-01 14:08+0100 by dell26/agent-1): Search is client-side over the loaded bookmarks of the current view/tag (title, site, URL, notes, tags; every word must match): GET /bookmarks has no text query param and the server is unchanged by the task.
- ASSUMPTION (2026-11-01 14:08+0100 by dell26/agent-1): Notes in 'edit title/tags/notes' = the bookmark's description field (the API has no separate notes field); blank title/notes clear them (null).
