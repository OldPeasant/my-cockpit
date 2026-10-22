# Code Review

Date: 2026-10-22

Task: and-review - Android weekly review (read-only)

Scope: `git diff d5187b8b4..e8802ded8` (the single task commit); committed changes only

Requirements:
- tasks/and-review.md acceptance criteria and recorded assumption
- SPEC.md §8.6 (weekly review steps, "Last weekly review: N days ago"), §9.2 (weekly review read-only first on Android)
- AGENTS.md project notes (per-feature folders/strings, lint, no secrets in logs)
- api/openapi.yaml (`GET /api/v1/weekly-reviews/status`, `GET /api/v1/weekly-reviews/steps/projects`, `WeeklyReviewResponse`, `WeeklyReviewStatusResponse`, `ProjectResponse`) and the generated Retrofit client
- Existing conventions of the read-only contacts feature (`android/.../contacts/`)

Git range:
- Branch: main
- HEAD: e8802ded8be7b24c2555ef76b528ecc3485c8577
- Base/start: d5187b8b46641c89b574a8bb6441e5d48bfeb932
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 2 P3

Verification notes:
- Criterion 1 (steps and stalled projects, read-only): `ReviewContent` lists all 8 steps in SPEC §8.6 order with labels matching the spec, marks steps before the open review's step as done, the open step as current, later ones (and all, when no review is open) as ahead; shows only `stalled == true` projects; no write endpoint is called and the screen has no click actions apart from the Retry button of the error banner. The step enum mapping (`ReviewStep.valueOf(step.value)`) matches the contract enum exactly; an unknown value fails deserialization and is reported as `Failed(null)`.
- Criterion 2: ReviewScreenTest (Robolectric/Compose) covers step rendering (done/current/ahead, no open review), last-review text (never/today/1 day/N days), stalled-only filtering, empty stalled list, read-only check, error banner with retry and disabled retry while loading. Repository and view model tests cover contract paths, null fields, 4xx/5xx/unreachable/unreadable answers, and keep-last-shown-on-failure. I could not run Gradle myself (Bash restricted); I rely on the parent's reported run (17 tests, 0 failures) for execution evidence.
- Async/loading: `refresh()` cancels the previous job before launching, so an older load cannot overwrite a newer one; every result path sets `loading = false`. Errors carry only the HTTP status, never response bodies; nothing is logged.
- Architecture matches the contacts feature (Hilt module per feature, repository result type, view model, Hilt-guarded screen with placeholder fallback). `strings_review.xml` lies outside `touches` but follows the per-feature strings file convention and is recorded as an assumption.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Offline banner claims a previously loaded review is shown when nothing was loaded**
    - File: `android/app/src/main/res/values/strings_review.xml:3` (used by `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewScreen.kt:261`)
    - Category: Quality
    - Problem: `review_offline` reads "Can't reach the server. Showing the last loaded review." It is also shown when the very first load fails offline (`overview == null`, see `ReviewContent` returning before the list), so the screen shows only the banner while the text says a review is being shown. The same wording exists in contacts, so this follows an existing pattern, but it is still inaccurate here.
    - Why it matters: Misleading message on a first-open-while-offline, which is a common phone scenario.
    - Suggested fix: Use a neutral text ("Can't reach the server.") or pick a second string when `state.overview == null`.

- [ ] **CR-2 | Test name promises a case it does not check; superseded refresh not tested**
    - File: `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewScreenTest.kt:73`, `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewViewModelTest.kt:39`
    - Category: Tests
    - Problem: `the last review reads as today or one day ago` asserts only the "today" case (the singular case is a separate test), so the name is misleading. Separately, the view model's cancel-previous-load behavior (`ReviewViewModel.kt:38`) has no test: no case calls `refresh()` twice with the first load still suspended and asserts the older result does not land.
    - Why it matters: Misleading test names hide what is covered; the out-of-order guard is regression-prone if someone removes the `cancel()`.
    - Suggested fix: Rename the test to e.g. `the last review reads as today for zero days`; add a view model test where the first `overview()` suspends (e.g. a `CompletableDeferred`) and the second returns, asserting the final state holds the second result.

## Review conclusion

No material issues found.
