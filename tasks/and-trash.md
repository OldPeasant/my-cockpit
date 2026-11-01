---
title: Android: Trash (list, restore, delete forever)
status: todo
owner:
deps: [and-shell,p0-trash-core,and-search]
touches: [android/app/src/main/java/ch/sonensei/cockpit/trash,android/app/src/main/java/ch/sonensei/cockpit/ui,android/app/src/test/java/ch/sonensei/cockpit/trash,android/app/src/main/res/values/strings_trash.xml,android/app/src/main/res/values-de/strings_trash.xml]
prio: 2
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
