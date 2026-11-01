---
title: Android: Trash (list, restore, delete forever)
status: in_progress
owner: dell26/agent-2
deps: [and-shell,p0-trash-core,and-search]
touches: [android/app/src/main/java/ch/sonensei/cockpit/trash,android/app/src/main/java/ch/sonensei/cockpit/ui,android/app/src/test/java/ch/sonensei/cockpit/trash,android/app/src/main/res/values/strings_trash.xml,android/app/src/main/res/values-de/strings_trash.xml]
prio: 2
lease_until: 2026-11-01T15:39:53Z
---
## Goal
The Trash (SPEC.md §4.x: deleted items, purged after 30 days) exists on the web only. Use `/api/v1/trash`,
`/trash/{type}/{id}` and `/trash/{type}/{id}/restore`; server unchanged.

## Acceptance criteria
- [ ] Trash is reachable from the More screen and lists deleted items of all types with type icon, title, deletion date and days until purge
- [ ] Restore and delete forever per item (delete forever asks for confirmation); after restore the item is back in its list
- [ ] Loading, empty and error states follow the app's existing list screens
- [ ] All new UI strings exist in English and German (`res/values`, `res/values-de`)
- [ ] Unit tests (ViewModel, plus Compose UI tests where the project has them for similar screens) cover the criteria above; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
Requested by the owner (2026-11-01) after trying the Android app on the phone.
- ASSUMPTION (2026-11-01 16:20+0100 by dell26/agent-2): Trash sits in the More list between Review and Settings with the Delete icon; nav_trash lives in strings_trash.xml (strings.xml is outside the task's touches).
- ASSUMPTION (2026-11-01 16:20+0100 by dell26/agent-2): After a restore of a TASK or PROJECT the app runs GtdSync.refreshAll() so the Room-cached GTD lists show it at once; other types' screens load from the server when opened, so they need no reload.
- ASSUMPTION (2026-11-01 16:20+0100 by dell26/agent-2): The Trash list loads all pages up front (200 per page, at most 50 pages) like Bookmarks, instead of the web's 'Show more' button; restore and delete-forever are icon buttons per row, results/failures shown as snackbars (no undo).
