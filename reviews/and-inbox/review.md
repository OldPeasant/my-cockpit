# Code Review

Date: 2026-10-21

Task: and-inbox - Android inbox processing

Scope: `git diff 8c33a93d..HEAD -- android` (task commits 5b9bd55a, 8cb718da, 11c9b336; `tasks/` changes ignored)

Requirements:
- tasks/and-inbox.md acceptance criteria 1-2 and recorded assumptions
- SPEC.md §5.2 (rules), §5.3 (Inbox processing mode), §9.2, §9.3 (read cache, offline banner)
- AGENTS.md project notes (conventions, Problem Details/ETag/If-Match)
- Web reference: web/src/app/features/gtd/inbox/inbox-actions.ts, inbox-page.ts
- Android data layer android/app/src/main/java/ch/sonensei/cockpit/gtd/data/ (GtdRefresh.kt, GtdRepositories.kt, GtdSync.kt)
- Server contract server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/TaskDtos.kt (PATCH semantics: absent = unchanged, null = clear)

Git range:
- Branch: main
- HEAD: 11c9b3368b593bfd79af8e0bb05f9254c9826521
- Base/start: 8c33a93d4d1ef7506be9df93bc1ebc317654df63
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 2 P2, 2 P3

Notes on verification:
- Criterion 1: Inbox list, one-at-a-time processing, "x items left", actions Do it / Next / To project (existing or new) / Waiting / Schedule / Tickler / Someday / Trash, inline energy, creativity, estimate and contexts are implemented and match the web flow (clarification PATCH of changed fields only, then the action with the new version as If-Match; trash saves nothing; new project created once per item across retries; stalled project gets the item as next step). The PATCH body is hand-built JsonObject, matching the server contract where `null` clears a field. Reference/calendar are omitted per the task text and a recorded assumption.
- Criterion 2: the reviewer's own Gradle run was not permitted in this session (Bash denied for the Gradle command). Existing results in android/app/build/test-results/testDebugUnitTest (written 2026-10-21 09:55, one minute before the task commits) show InboxRepositoryTest 13/0 failures, InboxViewModelTest 11/0, InboxScreenTest 10/0. The tests cover every action reaching the repository (view model) and the HTTP call it makes (repository against MockWebServer), and offline disabling actions in the view model and in Robolectric Compose tests. The parent should record its own run of the named command as evidence.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A failed action can leave the Inbox read-only with no way to retry**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxRepository.kt:91` (also `InboxScreen.kt:141`, `InboxScreen.kt:152-165`)
    - Category: Correctness
    - Problem: When an action fails with an IOException or any 5xx, `GtdConnectivity.refreshing` sets the shared offline flag (`GtdRefresh.kt:81,96`), and `process()` deliberately skips the Inbox reload on `RefreshResult.Offline`. `InboxUiState.actionsEnabled` is then false for every action and the Process button. Nothing on the screen can clear the flag: the offline banner has no retry, and there is no pull-to-refresh. The flag clears only on the next refresh trigger: a change-stream reconnect, a TASK change event, the 15-minute periodic sync (`GtdSync.kt:76`), or leaving the screen for more than 5 s so the flow restarts. A single 500/503 from the server while the change stream stays connected is enough to cause this. The banner text ("Processing works again once the server is reachable") says the screen recovers on its own, which can take up to 15 minutes.
    - Why it matters: A brief server error makes the screen unusable for an unpredictable time, and the user has no way to retry. The shared flag also makes every other GTD view read-only.
    - Suggested fix: Add a retry action to the offline banner (and/or the OFFLINE message) that calls `tasks.refresh(TaskView.INBOX)` through the view model. Add a view model test showing that a retry clears the offline state.

- [ ] **CR-2 | A double tap on an action button also runs the action on the next item**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxViewModel.kt:186-191`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxScreen.kt:391-405`
    - Category: Correctness
    - Problem: `run()` adds the item to `busy` right away, so the next item becomes `current` within one frame. The action buttons stay in the same place and stay enabled. A second tap that lands after that recomposition (a normal accidental double tap, or a tap during a slow frame) calls `choose(kind)` for the next item. That item is then trashed, completed or moved without the user ever seeing it. The web shares this design but is keyboard-driven (SPEC §5.3). On a touch screen, double submits are the main input hazard.
    - Why it matters: An unintended write lands on an item the user never looked at, such as a trash or a "done" on the wrong item. Trash is recoverable, but the user does not notice that it happened.
    - Suggested fix: Ignore action taps for a short time after the current item changes. One way: keep the id of the item the buttons were rendered for and have `choose`/`confirmPrompt` take the expected item id, so a stale tap is dropped. Add a view model test where two `choose` calls for the same item id only process it once.

## P3 Low

- [ ] **CR-3 | Hilt fallback relies on an internal Hilt API and hides wiring errors in production**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxScreen.kt:58`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxScreen.kt:73-77`
    - Category: Architecture
    - Problem: `InboxScreen` checks `LocalActivity.current is dagger.hilt.internal.GeneratedComponentManagerHolder`, which is from Hilt's `internal` package and has no stability guarantee. In production, a missing `@AndroidEntryPoint` would quietly show the "coming soon" placeholder instead of failing. The only reason for the check is test convenience (the shell tests use a plain `ComponentActivity`). The assumption that records it also proposes reusing this pattern in other screens.
    - Why it matters: A test-only workaround in production code makes a real DI wiring bug look like a placeholder, and it can break when Hilt is upgraded.
    - Suggested fix: Make the shell's navigation tests host a Hilt test activity, or inject the inbox content slot into `CockpitShell` so tests can pass a stub. Then `InboxScreen` can call `InboxRoute` directly.

- [ ] **CR-4 | A project created for an item can be reused under a different title**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxRepository.kt:133-140`
    - Category: Correctness
    - Problem: `createdProjects[id]` is reused for any later `ProjectChoice.New` on the same item, whatever title the user types. If the move fails after "New project: Kitchen" and the user retries with "New project: Bathroom", the item moves into "Kitchen" and no "Bathroom" project is created. If the retry picks an existing project instead, the entry is never removed. The web offers the created project as the preselected choice (inbox-page.ts:175), so the user can see it. The Android prompt does not show it.
    - Why it matters: Rare, but the result contradicts what the user typed and gives no visible sign of it.
    - Suggested fix: Reuse the created project only when the title matches, or preselect it in the prompt as the web does. Drop the entry when the item is processed or the choice changes.

## Review conclusion

Material issues found; fix before completing the task. There are no P0 or P1 findings. CR-1 and CR-2 are resilience and correctness problems in normal phone use and should be fixed or explicitly accepted by the parent worker.
