# Code Review

Date: 2026-10-22

Task: and-review - Android weekly review (read-only)

Scope: `git diff d5187b8b4..1abe0c84a` (the task's production commit e8802ded8 plus the test-only commit 1abe0c84a); committed changes only. Reviewed revision: 1abe0c84a50923289e8c4d5039bb524ae1b4a7c1

Requirements:
- tasks/and-review.md acceptance criteria and recorded assumption
- SPEC.md §8.6 (weekly review steps, "Last weekly review: N days ago"), §9.2 (weekly review read-only first on Android)
- AGENTS.md project notes (per-feature folders/strings, lint, no secrets in logs, fictitious test data)
- api/openapi.yaml (`GET /api/v1/weekly-reviews/status`, `GET /api/v1/weekly-reviews/steps/projects`, `WeeklyReviewResponse`, `WeeklyReviewStatusResponse`, `ProjectResponse`) and the generated Retrofit client
- Existing conventions of the read-only contacts feature (`android/.../contacts/`, including `ContactsRouteInAppTest`)

Git range:
- Branch: main
- HEAD: 1abe0c84a50923289e8c4d5039bb524ae1b4a7c1
- Base/start: d5187b8b46641c89b574a8bb6441e5d48bfeb932
- Commits reviewed: 2 (e8802ded8 production, 1abe0c84a tests and reports only)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 2 P3 (CR-2 resolved in 1abe0c84a; CR-1 open)

Verification notes:
- Production code is unchanged between e8802ded8 and 1abe0c84a (`git diff e8802ded8..1abe0c84a -- android` touches only `android/app/src/test/`). The production findings of the first pass stand as written.
- Criterion 1 (steps and stalled projects, read-only): `ReviewContent` lists all 8 steps in SPEC §8.6 order, marks steps before the open review's step as done, the open step as current, later ones (and all, when no review is open) as ahead; shows only `stalled == true` projects; no write endpoint is called. `ReviewRepositoryCallsTest` now pins the read-only contract at the API boundary (`confirmVerified(api)` after one call each of `getWeeklyReviewStatus`/`getReviewProjects`).
- Criterion 2: ReviewScreenTest covers rendering; ReviewRouteInAppTest (new) covers the Hilt route in `MainActivity` (real screen instead of placeholder, progress text, current-step semantics, stalled project, retry after a failed first load). It mirrors `ContactsRouteInAppTest` setup exactly (HiltAndroidRule outer to an empty compose rule, `@BindValue` mockk repository, test WorkManager, fictitious tokens, `waitUntil` with a 5 s bound, scenario closed in `@After`).
- New test code checked for correctness and flakiness:
  - `ReviewViewModelTest` "a newer refresh drops the running load ...": the first load suspends on a `CompletableDeferred`, the second returns; the older answer is completed afterwards with a failure. Without `loadJob?.cancel()` the resumed older job would set `message = FAILED` and the equality assertion would fail, so the test does guard the out-of-order case, including an older failure. Deterministic under `StandardTestDispatcher`.
  - "while a load runs ...": asserts `loading = true` with the previous OFFLINE message kept, then the cleared final state. Matches `ReviewViewModel.refresh()`.
  - `ReviewRepositoryCallsTest` "a cancelled load ...": with an `UNDISPATCHED` launch both calls suspend in `awaitCancellation()`; if the repository swallowed `CancellationException` into `Offline`, `result` would be assigned and the test would fail, so it meaningfully checks the rethrow in `load`.
  - `ReviewRepositoryTest` additions: 499/500 boundary matches `SERVER_ERROR = 500`; 204 without a body maps to `Failed(204)` via `body()`; HTML body maps to `Failed(null)` (`daysSinceLastReview` is nullable with a default, so the failure comes from the projects body as named); the `startedAt` replacement string matches `reviewJson` (otherwise the overview would be `Ok` and the test would fail).
  - No wall-clock, sleeps or real network beyond MockWebServer; test data is fictitious.
- Execution evidence: I could not run Gradle myself (Bash restricted to read-only inspection). I rely on the parent's reported runs: `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*'` -> 28 tests, 0 failures; `createDebugUnitTestCoverageReport` -> 1784 tests, 0 failures; `sh scripts/check.sh` passed before the test rename (the parent states it will rerun it on 1abe0c84a).
- Async/loading, error exposure and architecture: unchanged from the first pass (cancel-previous-load, every path ends loading, errors carry only the HTTP status, nothing logged; structure matches the contacts feature).

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
    - Problem: `review_offline` reads "Can't reach the server. Showing the last loaded review." It is also shown when the very first load fails offline (`overview == null`), so the screen shows only the banner while the text says a review is being shown. The same wording exists in contacts, so this follows an existing pattern, but it is still inaccurate here. Still present in 1abe0c84a (no production change).
    - Why it matters: Misleading message on a first-open-while-offline, which is a common phone scenario.
    - Suggested fix: Use a neutral text ("Can't reach the server.") or pick a second string when `state.overview == null`. Accepting it as-is for consistency with contacts is a reasonable disposition for a P3.

- [x] **CR-2 | Test name promises a case it does not check; superseded refresh not tested** (resolved in 1abe0c84a)
    - File: `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewScreenTest.kt:73`, `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewViewModelTest.kt:97`
    - Category: Tests
    - Problem (first pass): the test `the last review reads as today or one day ago` asserted only the "today" case, and the view model's cancel-previous-load behavior had no test.
    - Resolution verified: the test is renamed to `the last review reads as today` (the singular case keeps its own test), and `a newer refresh drops the running load, so an older answer arriving last never overwrites it` fails if the `cancel()` in `ReviewViewModel.refresh()` is removed (see verification notes).

## Review conclusion

No material issues found.
