---
title: Android: Trash (list, restore, delete forever)
status: in_progress
owner: felix/agent-1
deps: [and-shell,p0-trash-core,and-search]
touches: [android/app/src/main/java/ch/sonensei/cockpit/trash,android/app/src/main/java/ch/sonensei/cockpit/ui,android/app/src/test/java/ch/sonensei/cockpit/trash,android/app/src/main/res/values/strings_trash.xml,android/app/src/main/res/values-de/strings_trash.xml]
prio: 2
lease_until: 2026-11-01T17:29:48Z
extended: 
releases: 1
---
## Goal
The Trash (SPEC.md §4.x: deleted items, purged after 30 days) exists on the web only. Use `/api/v1/trash`,
`/trash/{type}/{id}` and `/trash/{type}/{id}/restore`; server unchanged.

## Acceptance criteria
- [x] Trash is reachable from the More screen and lists deleted items of all types with type icon, title, deletion date and days until purge
  - verified (2026-11-01 18:10+0100 by felix/agent-1): android/gradlew -p android testDebugUnitTest (Robolectric Compose): CockpitShellTest.trashOpensFromTheMoreListAndBackReturnsToIt + TrashScreenTest 'rows show type, title, deletion date and days until purge', TrashRepositoryTest (all types listed, paged) -> pass
- [ ] Restore and delete forever per item (delete forever asks for confirmation); after restore the item is back in its list
- [ ] Loading, empty and error states follow the app's existing list screens
- [ ] All new UI strings exist in English and German (`res/values`, `res/values-de`)
- [ ] Unit tests (ViewModel, plus Compose UI tests where the project has them for similar screens) cover the criteria above; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes

## Notes
Requested by the owner (2026-11-01) after trying the Android app on the phone.
- ASSUMPTION (2026-11-01 16:20+0100 by dell26/agent-2): Trash sits in the More list between Review and Settings with the Delete icon; nav_trash lives in strings_trash.xml (strings.xml is outside the task's touches).
- ASSUMPTION (2026-11-01 16:20+0100 by dell26/agent-2): After a restore of a TASK or PROJECT the app runs GtdSync.refreshAll() so the Room-cached GTD lists show it at once; other types' screens load from the server when opened, so they need no reload.
- ASSUMPTION (2026-11-01 16:20+0100 by dell26/agent-2): The Trash list loads all pages up front (200 per page, at most 50 pages) like Bookmarks, instead of the web's 'Show more' button; restore and delete-forever are icon buttons per row, results/failures shown as snackbars (no undo).
- extended by dell26/agent-2: Implementation, CR-1/CR-2 fixes (fixes.md) and post-fix code review (review.md, 0 findings) are committed. Left: (1) run test-writer once more on the final code so it writes reviews/and-trash/tests.md with coverage (it was interrupted; its last test, the background reload outliving the screen, is committed in 2c980289e); (2) run 'cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug' with no other build running (the reviewer's run hit a KSP classpath error during a concurrent build); (3) tasks.py verify for criteria 1-5, write quality.json, run .taskcheck, then done. Note: .taskcheck failed in web vitest with 15 'Timeout waiting for worker' errors (3117/3117 tests passed) because the host was overloaded; this task does not touch web/.
- release by dell26/agent-2: Implementation, CR-1/CR-2 fixes (fixes.md) and post-fix review (review.md, 0 findings) committed. Last commit 1fb0dbce5 adds an UNVERIFIED test-writer test in TrashRouteTest ('the Trash loads again when the app comes back to it', lifecycle RESUMED->CREATED->RESUMED expects a 2nd repo.list()); run it first, and if TrashRoute does not reload on resume, drop the test (not a criterion). Left: (1) run test-writer on the final code so it writes reviews/and-trash/tests.md incl. task-scoped coverage; if it changes production code, re-run code-reviewer; (2) cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug with no concurrent build (earlier KSP classpath error came from a parallel build); (3) tasks.py verify and-trash 1-5, write+commit reviews/and-trash/quality.json, .taskcheck, done. Earlier .taskcheck failure was web vitest worker timeouts from host load (all 3117 tests passed); this task does not touch web/.
