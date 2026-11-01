# Code Review

Date: 2026-11-01

Task: and-trash - Android: Trash (list, restore, delete forever)

Scope: `git diff 201b8c1f4..fdfe9b811` (task commits 071efbc18, fdfe9b811). The rebased equivalents on main (440d46b25, 01d91f211) have identical trash/ui/test content; later test-writer commits were not reviewed.

Requirements:
- tasks/and-trash.md acceptance criteria and recorded assumptions (Trash in More between Review and Settings; GtdSync.refreshAll after TASK/PROJECT restore; load all pages up front, icon buttons, snackbars without undo)
- SPEC.md (Trash, §4.2 API conventions)
- docs/ARCHITECTURE.md, AGENTS.md "Project notes"
- Comparable code: android `bookmarks/`, `calendar/agenda/`, `search/SearchModels.kt`; web `web/src/app/features/trash/`

Git range:
- Branch: main
- HEAD: 01d91f211 (reviewed revision fdfe9b811)
- Base/start: 201b8c1f4c091ef1bc6c3d9c60b499566b492239
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A GTD restore is confirmed only after the full GTD cache refresh**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/trash/TrashRepository.kt:71`
    - Category: Quality
    - Problem: `restore()` awaits `lists.reload(item.type)` before returning. For TASK/PROJECT this is `GtdSync.refreshAll()`, which makes sequential network refreshes (contexts, projects, then each `TaskView`: INBOX, NEXT, TODAY). Until all five finish, the row stays busy (buttons disabled), stays in the list and the "Restored" snackbar is not shown, although the server already restored the item.
    - Why it matters: On a slow mobile connection the restore looks slow or stuck. If the screen is left in that time, the view model scope cancels the cache refresh, so the GTD lists only show the item at the next periodic sync.
    - Suggested fix: Return the restore result first and run the reload separately (e.g. return Ok and let the view model start the reload in its own coroutine after `drop`/`show`), or document the trade-off if it is intended.

- [ ] **CR-2 | The production restore-reload binding is not tested**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/trash/TrashRepository.kt:54`
    - Category: Tests
    - Problem: `TrashRepositoryTest` uses the internal constructor with a recording lambda. The `@Inject` constructor's mapping (`if (type in GTD_TYPES) gtd.refreshAll()`), which is what backs "after restore the item is back in its list" for tasks and projects, is not covered: no test checks that TASK/PROJECT trigger `GtdSync.refreshAll()` and other types do not.
    - Why it matters: A regression in that one line (wrong type string, set edited) would leave restored tasks missing from the cached GTD lists without failing a test.
    - Suggested fix: Add a small test that builds `TrashRepository(api, mockk<GtdSync>(relaxed = true))`, restores a TASK and a NOTE, and verifies `refreshAll()` was called exactly once.

## Review conclusion

No material issues found.

Checked and found correct: the More list entry and shell routing (`CockpitDestination.Trash`, `CockpitShell`); row content (type icon via `searchTypeStyle`, singular type label, title, localized deletion date, rounded-up days until purge, urgent colour at <= 3 days as on the web); restore and confirm-gated delete forever; per-item busy guard against double taps; 404 drops the item; stale-load protection (`refresh` cancels the previous job, `drop` restarts a running load); loading/empty/offline/failed states with retry, matching the bookmarks/calendar pattern (resume reload, pull to refresh, Hilt placeholder fallback); error mapping without logging response bodies; EN/DE strings complete and matching (including plurals); no other `TrashApi` binding exists, so the new Hilt provider does not clash.
