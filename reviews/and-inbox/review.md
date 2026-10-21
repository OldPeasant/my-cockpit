# Code Review

Date: 2026-10-21

Task: and-inbox - Android inbox processing

Scope: `git diff f30fca1b..ec920897 -- android` (final code after the review fixes; `tasks/` and `reviews/` changes ignored). Re-review of the earlier report (CR-1..CR-4), the fixes in `reviews/and-inbox/fixes.md`, and TW-1.

Requirements:
- tasks/and-inbox.md acceptance criteria 1-2 and recorded assumptions
- SPEC.md §5.3 (Inbox processing mode), §9.3 (read cache, offline read-only)
- AGENTS.md project notes (conventions, ETag/If-Match, lint, touches)
- Android data layer for context: android/app/src/main/java/ch/sonensei/cockpit/gtd/data/GtdRefresh.kt, GtdRepositories.kt

Git range:
- Branch: main
- HEAD: ec920897
- Base/start: f30fca1b4a52d8a040d3eb6dfcd2bb4a1b9b18d9
- Commits reviewed: 10 (78109e57, 6f5a2296, 7b5ab0b3, 6a63866c, eeae2d6c, a7db7459, 5b1a7176, 3bfc1eb2, a0c32f7b, ec920897)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## History of earlier findings

- CR-1 (P2, offline state with no retry): fixed in eeae2d6c. `InboxViewModel.retry()` (InboxViewModel.kt:160-170) reloads the Inbox and guards against a second retry while one runs. The offline banner has a Retry button (InboxScreen.kt:172) that is disabled while a retry runs, and the banner text no longer promises automatic recovery. A successful reload clears the shared flag (GtdRefresh.kt:95). Covered by view model, screen and route tests. Verified.
- CR-2 (P2, a double tap acts on the next item): fixed in eeae2d6c. `choose`/`confirmPrompt` take the id of the item the buttons were composed for and drop stale taps (InboxViewModel.kt:183, 211). In addition, a 500 ms guard after each action (`TAP_GUARD_MS`, `justRan()`, InboxViewModel.kt:184, 213, 257) drops taps that land after the buttons have already recomposed for the next item. The time comes from an injectable monotonic clock, and the tests include the exact 500 ms boundary. Verified. Note for the parent: fixes.md says that a time-based guard "was not added". That is out of date, because the same commit eeae2d6c adds the guard. The fixer report describes less protection than the code has.
- CR-3 (P3, Hilt fallback through an internal API): declined by the parent as outside the task's touches. Still present, so it is carried forward below as CR-3.
- CR-4 (P3, created project reused under another title): fixed in eeae2d6c. `CreatedProject(title, id)` is reused only when the trimmed title matches (InboxRepository.kt:147-151). It is dropped when an existing project is chosen (:145) and after any successful process (:99-102). Covered by three repository tests. Verified.
- TW-1 (an unreadable date in the project answer after the move fails processing): fixed in ec920897 with `catch (_: DateTimeException)` in `makeNextStepIfStalled` (InboxRepository.kt:179). The catch set now matches `GtdConnectivity.refreshing`. 3bfc1eb2 added the serialization and argument catches before it. Verified.

Notes on verification:
- Criterion 1: the Inbox list, one-at-a-time processing, "x items left", and the actions Do it / Next / To project (existing or new) / Waiting / Schedule / Tickler / Someday / Trash all match SPEC.md §5.3. Reference and calendar are left out, as the task and a recorded assumption allow. Energy, creativity, estimate and contexts are set inline. The clarification is sent as a minimal PATCH (absent = unchanged, null = clear), followed by the action with the new version as If-Match.
- Criterion 2: I ran `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.inbox.*' --offline` in android/ and it finished without errors. The result XMLs (timestamps 2026-10-21T08:54Z, after ec920897) show 101 tests with 0 failures: InboxRepositoryTest 35, InboxViewModelTest 28, InboxScreenTest 21, InboxActionsTest 11, InboxRouteTest 4, InboxGraphTest 2. The tests check that every action reaches the repository (view model) and makes the right HTTP call (repository against MockWebServer). They also check that the offline state disables processing, every action and a prompt's confirmation, in both the view model and the Robolectric Compose tests.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-3 | Hilt fallback relies on an internal Hilt API and hides wiring errors in production** (carried over; the parent declined it as outside the task's touches)
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxScreen.kt:58`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxScreen.kt:73-77`
    - Category: Architecture
    - Problem: `InboxScreen` checks `LocalActivity.current is dagger.hilt.internal.GeneratedComponentManagerHolder`. That type is in Hilt's `internal` package. The check exists only so the shell's navigation tests can host the tab in a plain `ComponentActivity`. A recorded assumption proposes reusing this pattern in other screens.
    - Why it matters: In production, a missing `@AndroidEntryPoint` would show the "coming soon" placeholder instead of failing. The check can also break when Hilt is upgraded. If other screens copy the pattern, the problem spreads.
    - Suggested fix: Add a follow-up task (it touches the shell tests) that hosts the shell tests in a Hilt test activity, or injects the tab content into `CockpitShell`. Until then, reject or narrow the assumption "Other real screens can reuse this pattern".

- [ ] **CR-5 | A processed item can come back as the current item when the reload after the action fails**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxRepository.kt:99-104`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt:229-243`
    - Category: Correctness
    - Problem: After a successful action, `process()` returns `Processed` whatever its `tasks.refresh(TaskView.INBOX)` returns. The view model then removes the item from `busy` at once. If that reload fails (the connection drops between the action and the reload, a 5xx, or a cache write error), the cached Inbox still contains the item (GtdRepositories.kt:69-80 only replaces the cache on success). The item therefore reappears as the current item and is counted again in "x items left", although the server has already completed, moved or trashed it. If the reload was `Offline`, actions stay disabled until the retry. After a `Failed` reload they stay enabled, and acting on the item again ends in a 404/412 "changed elsewhere" message.
    - Why it matters: This is rare, because it needs a failure between two consecutive requests. When it happens, the user sees an item they just processed come back with no explanation, and the progress count is wrong.
    - Suggested fix: In the view model, hide the ids of processed items (for example in a `processedIds` set filtered like `busy`) until a later Inbox load no longer contains them. Another option is to clear them when `refreshedAt` advances. Add a view model test in which `process` returns `Processed` while the observed Inbox still lists the item.

## Review conclusion

No material issues found. All earlier findings except the declined CR-3 are fixed and covered by tests, both acceptance criteria are met, and the inbox test suite passes. CR-3 and CR-5 are low-priority. The parent can accept them or plan them as follow-ups. The parent should also correct the outdated CR-2 "Limitation" statement in fixes.md (via the fixer) or note the difference in quality.json.
