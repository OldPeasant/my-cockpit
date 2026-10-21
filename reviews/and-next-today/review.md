# Code Review

Date: 2026-10-21

Task: and-next-today: Android Next and Today

Scope: `git diff 6fdf4313172fb7a14eaa7342e8a4bf8f218e669e..dcb758c4c346ef3db0f735c311d4877b04f68567`, the whole final task diff. Reviewed revision: dcb758c4. This is a re-review after the fixer's commit 8b5ce29a and the test-writer's commit dcb758c4. I checked the fixes in `reviews/and-next-today/fixes.md` against the code. Committed changes only; the working tree was clean.

Requirements:
- `tasks/and-next-today.md`: both acceptance criteria and the 5 recorded assumptions, including the CR-4 deferral
- `SPEC.md` §5.3 (Next Actions filters, Today view), §9.3 (offline read-only)
- `docs/ARCHITECTURE.md`, `docs/QUALITY.md` (freshness by patch-id), `AGENTS.md` project notes (lint is part of the contract, touches, assumptions)
- The previous review (findings CR-1 to CR-6), `reviews/and-next-today/fixes.md`, `reviews/and-next-today/tests.md`
- Server `TaskService.complete`/`uncomplete` (`server/.../gtd/domain/TaskService.kt:143-165`), to check the version assumption behind the CR-3 fix

Git range:
- Branch: main
- HEAD: dcb758c4c346ef3db0f735c311d4877b04f68567
- Base/start: 6fdf4313172fb7a14eaa7342e8a4bf8f218e669e
- Commits reviewed: 8 (151c043f, aeeee6e6, 7b457e81, f6cf571f, 5766c60c, 8b5ce29a, daddf775, dcb758c4)

Validation run by the reviewer:
- `cd android && ./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.next.*' --tests 'ch.sonensei.cockpit.gtd.today.*'`: BUILD SUCCESSFUL. JUnit XML for `gtd.next.*` and `gtd.today.*`: 105 tests, 0 failures, 0 errors, 0 skipped. `lint-results-debug.xml` has no issues.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Status of previous findings

- **CR-1 (P1, `LocalContext` resources in the snackbar): fixed.** `TaskListUi.kt:148` now uses `LocalResources.current`, without a suppression. Lint is clean.
- **CR-2 (P1, filtered count not a plural): fixed.** `next_count_filtered` is a `<plurals>` resource (`strings_next_today.xml:27-30`), with the total as the quantity (`NextScreen.kt:129`). The new `NextScreenTest` cases check the singular and the plural text.
- **CR-3 (P2, completed tasks hidden forever): fixed.** `CompletionState` now splits in-flight ids (`pending`) from completed ids mapped to the version the server returned (`completed`). `hides()` (`TaskCompletion.kt:114`) hides a cached copy only while its id is pending, or while its version is at most the completed version. This holds because the server raises the version on every change: `uncomplete` and other edits go through `modify` with a version check, so a task reopened elsewhere always has a higher version. I traced each path:
  - A successful undo clears both `pending` and `completed`.
  - A refused undo clears only `pending`, so the task shows again once the reloaded list has it open at a newer version.
  - A double tap is still ignored, also while an undo is pending.
  - Completing a reopened task again works.
  - In Today, a stale pre-completion copy in one view no longer hides the reopened copy in the other, because `filterNot(hides)` runs before `distinctBy`.

  Regression tests are in `TaskCompleterTest`, `NextViewModelTest` and `TodayViewModelTest`.
- **CR-4 (P2, a failed first load stays on "Loading…"): deferred, not fixed.** The deferral is reasonable: `TaskRepository.observe` in `gtd/data` drops the refresh result, and `gtd/data` is outside this task's touches. It is recorded as an assumption in `tasks/and-next-today.md`. Follow-up task `tasks/and-gtd-load-error.md` depends on this task, touches `gtd/data`, `gtd/next` and `gtd/today`, and has acceptance criteria for an error with retry. Whether to accept the deferral is the parent worker's decision.
- **CR-5 (P3, context map rebuilt per row): fixed.** The map is built with `remember(state.contexts)` before the `LazyColumn` (`NextScreen.kt:120`).
- **CR-6 (P3, `toggleCreativity` hard to read): fixed.** It is now a two-line expression body like the sibling toggles (`NextViewModel.kt:106-107`). ktlint passes.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-7 | `CompletionState.hidden` is now used only by tests and no longer means "hidden"**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/TaskCompletion.kt:111`
    - Category: Quality
    - Problem: after the CR-3 fix, the lists filter with `hides(task)`, which depends on the cached version. The derived `hidden` property (`pending + completed.keys`) is no longer used by any production code: `grep` finds it only in `TaskCompleterTest.kt` (lines 48-236). It also includes ids whose newer copies are shown again. For example, after a refused undo of a task reopened elsewhere, the id is still in `hidden`, but the list shows the task.
    - Why it matters: the name and its KDoc ("the tasks the lists may hide") suggest a visibility rule that the lists do not use. The tests that assert on `hidden` check bookkeeping, not what the user sees, so a future change could keep them green while visibility breaks.
    - Suggested fix: remove `hidden` and assert on `hides(...)`, `pending` or `completed` in `TaskCompleterTest`. If keeping it, rename it (for example `tracked`) and state in its KDoc that it is not the visibility rule.

## Review conclusion

No material issues found.

Both acceptance criteria are implemented and verified by the run above:
- Next has context, energy, creativity and max-estimate filters, applied on the device as the recorded assumption says.
- Today shows overdue, due-today and scheduled sections.
- Completing hides the task at once and offers undo in a snackbar.
- The `gtd.next.*` and `gtd.today.*` Robolectric and Compose tests pass (105 tests).

The fixes for CR-1, CR-2, CR-3, CR-5 and CR-6 are correct and complete. CR-4 is deferred with a recorded assumption and a follow-up task. CR-7 is an optional cleanup.

The hashes in `fixes.md` and `tests.md` (for example 27b65654 and 5c884837) come from before a rebase. Under the patch-id freshness rule in `docs/QUALITY.md` the evidence still counts. The parent worker should set `reviewed_revision` to the current hash.
