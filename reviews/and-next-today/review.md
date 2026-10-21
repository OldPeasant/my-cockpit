# Code Review

Date: 2026-10-21

Task: and-next-today: Android Next and Today

Scope: `git diff 44de27f6a4147ff3ccabc0ef3acc6c2cd968b92f..efaa0a513c809eadca3dbdea96250cb6bbdb7b81` (task commits 47a1bca1, 6a264214, 6f721d1f, efaa0a51), committed changes only. The working tree was clean.

Requirements:
- `tasks/and-next-today.md`: acceptance criteria and the 4 recorded assumptions
- `SPEC.md` §5.3 (Next Actions filters, Today view), §9.3 (offline read-only)
- `AGENTS.md` project notes (lint is part of the contract, `.taskcheck`)
- `reviews/and-next-today/tests.md` (test-writer report, findings TW-1 and TW-2)
- Existing code in `android/.../gtd/data` (`TaskRepository`, `GtdConnectivity`, `Cached`)

Git range:
- Branch: main
- HEAD: efaa0a513c809eadca3dbdea96250cb6bbdb7b81
- Base/start: 44de27f6a4147ff3ccabc0ef3acc6c2cd968b92f
- Commits reviewed: 4

Summary: 0 P0, 2 P1, 2 P2, 2 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Snackbar reads resources through LocalContext; `lintDebug` fails `.taskcheck`**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/TaskListUi.kt:148`
    - Category: Quality
    - Problem: `CompletionSnackbarEffect` uses `LocalContext.current.resources`. Android lint reports the error `LocalContextResourcesRead` here. I confirmed it in `android/app/build/reports/lint-results-debug.txt` (generated 2026-10-21 10:49, after the last production change). This is the same issue as TW-1.
    - Why it matters: `scripts/check.sh` (`.taskcheck`) runs `lintDebug`, so the check fails and `tasks.py done` cannot pass. AGENTS.md says lint warnings must be fixed, not suppressed. The resources read this way can also be stale after a configuration change, such as a locale change.
    - Suggested fix: use `LocalResources.current` (lint's suggestion), or resolve the strings with `stringResource` outside the `LaunchedEffect`. Do not suppress the check.

- [ ] **CR-2 | Filtered count string should be a plural; `lintDebug` fails `.taskcheck`**
    - File: `android/app/src/main/res/values/strings_next_today.xml:27` (used at `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextScreen.kt:127`)
    - Category: Quality
    - Problem: `next_count_filtered` ("%1$d of %2$d next actions") is a plain `<string>`. Lint reports the error `PluralsCandidate`, which I confirmed in the same lint report. This is the same issue as TW-2.
    - Why it matters: it blocks `.taskcheck` and `done`. The text is also wrong for a total of 1 ("1 of 1 next actions") and cannot be translated correctly.
    - Suggested fix: change it to a `<plurals>` resource with the total as the quantity, and read it with `pluralStringResource(R.plurals.next_count_filtered, state.total, state.tasks.size, state.total)`. The existing screen tests should still pass with the same English text.

## P2 Medium

- [ ] **CR-3 | Completed tasks stay hidden for the whole life of the view model, even after they are reopened elsewhere**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/TaskCompletion.kt:118` (ids are removed only at `:124` and `:135`), applied at `NextViewModel.kt:86` and `TodayViewModel.kt:73`
    - Category: Correctness
    - Problem: when a completion succeeds, its id stays in `CompletionState.hidden` permanently. Only a refused completion or a successful undo on this device removes it. Suppose the task is later reopened somewhere else (for example on the web, or through the logbook). The change stream reloads the Next and Today caches and the task is back in `next.data`, but both view models keep filtering it out. The same happens when an undo here fails with CONFLICT: the snackbar says "The list now shows its current state", but the task stays hidden even if it is open on the server.
    - Why it matters: an open next action silently disappears from Next and Today as long as the tab's view model lives. Next and Today are long-lived tabs, so this can last a long time. The cached list no longer matches the server.
    - Suggested fix: hide an id only until the cache catches up. For example, drop it from `hidden` once a refreshed list (a newer `refreshedAt`) no longer contains it, or once the undo offer is gone and the reload after the completion has run. A failed undo should un-hide the task as well, because the list is then reloaded from the server. Add a test: complete, then the cache shows the task open again, then the task is visible.

- [ ] **CR-4 | A failed first load leaves Next and Today on "Loading…" forever, with no error and no retry**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextViewModel.kt:63`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/today/TodayViewModel.kt:42`
    - Category: Correctness
    - Problem: `loading` is `refreshedAt == null && total == 0 && !offline`. If the first refresh ends in `RefreshResult.Failed`, nothing changes: no cache meta is written and the offline flag is left as it is. `Failed` covers a non-5xx error status, an unreadable body, and `SQLiteException` such as a full device. The screen then shows "Loading…" indefinitely (`NextScreen.kt:124`, `TodayScreen.kt:96`), with no message and no way to retry.
    - Why it matters: a failure path never ends loading and does not leave a recoverable screen. A full disk or a malformed server answer would make both tabs look stuck.
    - Suggested fix: expose the outcome of the initial refresh to the view model, or a "load failed" state. One way is for the view model to call `refresh` itself and keep the result. Then show an error with a retry action instead of "Loading…". If this needs a change in `gtd/data`, which is outside this task's touches, record an assumption or a follow-up task instead.

## P3 Low

- [ ] **CR-5 | Context map rebuilt for every Next row**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextScreen.kt:154`
    - Category: Quality
    - Problem: `state.contexts.associateBy { it.id }` runs inside the `items` lambda, so it is rebuilt once per row on every composition. `TodayContent` builds it once (`TodayScreen.kt:86`).
    - Why it matters: unnecessary work while scrolling, and the two screens are inconsistent.
    - Suggested fix: build the map once before the `LazyColumn`, as `TodayContent` does, or with `remember(state.contexts)`.

- [ ] **CR-6 | `toggleCreativity` is hard to read**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextViewModel.kt:104-116`
    - Category: Quality
    - Problem: the formatter split a one-line `if` across 12 lines. The sibling toggles are one-liners with the same logic.
    - Why it matters: it is harder to read and to compare with the other toggles.
    - Suggested fix: write it like `toggleEnergy`, splitting the expression body so it fits within the line limit (for example with a local `val`), and re-run `ktlintFormat`.

## Review conclusion

Material issues found; fix before completing the task.

Both acceptance criteria are otherwise implemented and well tested. The Next filters (context, energy, creativity, max estimate) match the recorded assumptions. Today shows the overdue, due-today and scheduled sections. Completion hides the task at once and the undo snackbar calls `/uncomplete` with the returned version. The 95 focused tests passed according to the test report. CR-1 and CR-2 block `.taskcheck`. CR-3 and CR-4 are correctness and resilience gaps that should be fixed, or explicitly deferred by the parent worker with a recorded assumption.
