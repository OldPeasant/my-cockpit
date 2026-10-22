# Test Report

Date: 2026-10-22

Task: and-review - Android weekly review (read-only)

Scope: `git diff d5187b8b46641c89b574a8bb6441e5d48bfeb932..e8802ded8be7b24c2555ef76b528ecc3485c8577` (one task commit `e8802ded8`, branch `main`, HEAD at measurement `e8802ded8be7b24c2555ef76b528ecc3485c8577`), plus the uncommitted test files listed below. No uncommitted production changes.

Requirements:
- `tasks/and-review.md` acceptance criteria and its recorded assumption (read-only: last-review age, all 8 steps marked done/current/ahead, only stalled projects; never starts or moves a review)
- `SPEC.md` §8.6 / §9.2 (weekly review, read-only on the phone), as referenced by the task
- `docs/ARCHITECTURE.md` "Coverage" (Android: `./gradlew createDebugUnitTestCoverageReport`, JaCoCo XML)
- `AGENTS.md` project notes (fictitious test data, lint is part of the contract)

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewRepositoryTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewViewModelTest.kt` (extended)
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewRepositoryCallsTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewRouteInAppTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/review/ReviewScreenTest.kt` (unchanged, from the task commit)

## Tests added or updated

Count: 11 added (existing 17 kept unchanged; review package now has 28 tests)

- ReviewRepositoryTest (+5): `a 4xx just below a server error is a failure, 500 itself counts as offline`; `a refused projects call fails the whole overview`; `a successful answer without a body is a failure with its status`; `a body that is not JSON is a failure without a status`; `an unreadable start date is a failure without a status`.
- ReviewRepositoryCallsTest (+2, mocked `WeeklyReviewApi`): `an overview only reads the status and the projects, it never starts, moves or completes a review` (`confirmVerified`); `a cancelled load is cancelled, not reported as offline or failed`.
- ReviewViewModelTest (+2, deferred answers, no real delays): `while a load runs the state says loading and keeps the message until the answer arrives`; `a newer refresh drops the running load, so an older answer arriving last never overwrites it`.
- ReviewRouteInAppTest (+2, Hilt + Robolectric with `MainActivity`, fake `ReviewRepository` via `@BindValue`): `the Review screen is the real progress view, not the placeholder` (More -> Review, step 5 current, stalled project listed, one load); `after a failed first load, retry loads again and shows the review`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: shows current review steps (8 steps, done/current/ahead) | ReviewScreenTest `all eight steps show in order, the ones before the current step done`, `without an open review no step is done or current`; ReviewRouteInAppTest `the Review screen is the real progress view, not the placeholder` | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*'` | Pass |
| AC1: shows stalled projects only | ReviewScreenTest `only stalled projects are listed`, `without stalled projects it says every project has a next step`; ReviewRepositoryTest `the overview has the open review's step, ...`; ReviewRouteInAppTest (stalled project row) | same | Pass |
| AC1: last-review age (never / today / 1 day / n days) | ReviewScreenTest `the last review reads as today or one day ago`, `one day ago is singular`, `all eight steps ...`, `without an open review ...` | same | Pass |
| AC1: read-only (no control changes the review) | ReviewScreenTest `the screen is read only, nothing on it can be tapped`; ReviewRepositoryCallsTest `an overview only reads the status and the projects, it never starts, moves or completes a review` | same | Pass |
| AC1: the real screen is reachable in the app (More -> Review, not the placeholder) | ReviewRouteInAppTest (2 tests) | same | Pass |
| Error paths: offline / refused / unreadable answers, retry, kept content | ReviewRepositoryTest (refusal, 499/500 boundary, projects refusal, 204 no body, non-JSON, bad date, bad enum, unreachable); ReviewViewModelTest `a failed load keeps what was shown ...`, `a first load that fails ...`; ReviewScreenTest `a failed load shows the message with retry ...`, `retry is disabled while loading`; ReviewRouteInAppTest `after a failed first load, retry ...` | same | Pass |
| Ordering: an older answer arriving last does not overwrite a newer load; loading state while pending; cancellation not reported as failure | ReviewViewModelTest `a newer refresh drops the running load ...`, `while a load runs ...`; ReviewRepositoryCallsTest `a cancelled load is cancelled ...` | same | Pass |
| AC2: `./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*'` passes, covering step rendering with Robolectric/Compose UI tests | all of the above (ReviewScreenTest and ReviewRouteInAppTest are Robolectric Compose UI tests) | same | Pass: 28 tests (ReviewRepositoryTest 10, ReviewRepositoryCallsTest 2, ReviewViewModelTest 5, ReviewScreenTest 9, ReviewRouteInAppTest 2), 0 failures, 0 errors, 0 skipped |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.review.*'`

Result:

Pass (BUILD SUCCESSFUL; JUnit XML in `android/app/build/test-results/testDebugUnitTest/TEST-ch.sonensei.cockpit.review.*.xml`, timestamps 2026-10-22T12:48Z: 28 tests, 0 failures/errors/skipped; ktlint clean)

## Full test execution

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport` (runs the full Android debug unit suite with coverage)

Result:

Pass (BUILD SUCCESSFUL; 179 result files, 1784 tests, 0 failures, 0 errors, 0 skipped)

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (tfp gate tests, server ktlint + tests, web eslint + tests + build, android ktlint + unit tests + lint) -> "== all checks passed", run 2026-10-22 ~12:50-12:53Z on the snapshot below. The web build prints a pre-existing initial-bundle budget warning, unrelated to this task.

## Bugs found

None. No test exposed behavior that violates the acceptance criteria or the recorded assumption.

## Coverage

Status: verified (fresh run, exit status 0, on the snapshot below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file line coverage here (245/250). Method: `ReviewRepository.kt` and `ReviewViewModel.kt` are new files (every line added); in `ReviewScreen.kt` the unchanged lines 1-52 are imports, package and KDoc, which JaCoCo reports no executable lines for, and every executable line JaCoCo reports (53-277) falls in the added hunk `@@ -11 +53,225 @@`. Changed-branch coverage was not measured separately.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-review`; base `d5187b8b46641c89b574a8bb6441e5d48bfeb932` (from parent); HEAD `e8802ded8be7b24c2555ef76b528ecc3485c8577`; single task commit `e8802ded8` (`git diff --name-status d5187b8b4..e8802ded8`, matches the parent's list and the task's `touches`).
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD `e8802ded8` (no uncommitted production changes). Test files sha256: ReviewRepositoryTest.kt `5cb1daec29958b7159df5cfd3b8008a930df5ff7d0820ded912f353dc512d18f`, ReviewRepositoryCallsTest.kt `82c7d5d8c9a2dd3acbf022ab85b088e56f10b801d36a13f56114d8f5a709df4f`, ReviewViewModelTest.kt `abed57b7ee8eb89e79d20576bdf474b16f9954aabd2b64e0e4b09f3781901f6d`, ReviewScreenTest.kt `5a4f211b774edfc4da16b1dc66b9d8b4a1a0d170a74beaa87077ae486696ce49`, ReviewRouteInAppTest.kt `c1d369bf46eb7c0dd796fba401b1f87046970f5987b2c3aa300931be2bf9357b`.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewRepository.kt`, `.../review/ReviewViewModel.kt`, `.../review/ReviewScreen.kt`. Excluded: `android/app/src/main/res/values/strings_review.xml` (resource, not instrumentable; its strings are asserted by the Compose tests); Hilt/Dagger generated sources in the same JaCoCo package (`ReviewViewModel_HiltModules*.java`, `*_Factory.java`, generated code). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (project config, unchanged); `./gradlew createDebugUnitTestCoverageReport` in `android/`; full debug unit suite.
- Run start/completion time and exit status: started 2026-10-22T12:48:27Z, report written 2026-10-22T12:50:20Z, exit status 0 (BUILD SUCCESSFUL).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 `18a8be3739c2317d09a4cd1266063f621d98f1e2c5cfe28a75ba92943aa567b2`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtime is after the run start, it contains the new test classes' effects (ReviewScreen Hilt branch now covered), and test files were hashed before the run and not edited afterwards.
- Final run after latest test additions and production fixes: yes (after all test additions; no production fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" are JaCoCo bytecode instructions (JaCoCo has no statement counter).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewRepository.kt` | 393/418 (94.0%) | 8/8 (100.0%) | 27/31 (87.1%) | 57/60 (95.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewViewModel.kt` | 182/182 (100.0%) | 12/12 (100.0%) | 10/10 (100.0%) | 27/27 (100.0%) |
| `android/app/src/main/java/ch/sonensei/cockpit/review/ReviewScreen.kt` | 1781/1806 (98.6%) | 58/63 (92.1%) | 27/27 (100.0%) | 161/163 (98.8%) |
| **Aggregate (summed counters, 3 files; strings_review.xml excluded, not instrumentable)** | 2356/2406 (97.9%) | 78/83 (94.0%) | 64/68 (94.1%) | 245/250 (98.0%) |

For comparison, the parent's run on the task commit before these tests: ReviewScreen.kt lines 154/163, branches 55/63; ReviewRepository.kt lines 54/60, branches 7/8.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ReviewScreen.kt`: Hilt branch of `ReviewScreen` (l.54-55) and `ReviewRoute` (l.62-67) | Real screen reachable in the app, loads on show, retry wired to the view model | Covered now by `ReviewRouteInAppTest` (2 tests) |
| `ReviewScreen.kt:53, 62` (1 branch each) | none: Compose compiler's generated `$changed`/skip branches | Not meaningful; left |
| `ReviewScreen.kt:181/185, 260/263` (1 branch + a few instructions each) | none: synthetic default branch of an exhaustive `when` over an enum | Unreachable; left |
| `ReviewScreen.kt:193` (1 branch) | none: generated branch in the `semantics` lambda; the content description it builds is asserted for done, current and ahead steps | Left |
| `ReviewRepository.kt:27` `ReviewModule.weeklyReviewApi` | Hilt provider wiring of the generated Retrofit API | Not executed: the in-app test replaces `ReviewRepository` with `@BindValue`, so the provider is never called. One-line delegation to `Retrofit.create`; the same API interface is created this way in `ReviewRepositoryTest`. Justified gap |
| `ReviewRepository.kt:115-116` `catch (IllegalArgumentException)` | Unreadable answers reported as `Failed(null)` | Defensive: `SerializationException` is a subclass and is caught first, and `ReviewStep.valueOf` cannot fail while the enums match the API. Bad enum, non-JSON and bad date answers are tested and return `Failed(null)`. Left |
| `ReviewRepository.kt:48, 50, 83` (data class property getters `OpenReview.id/startedAt`, `Failed.status`) | none beyond the values, which are asserted via data class `equals` | Not meaningful; left |
| `ReviewRepository.kt:107-108` `catch (CancellationException)` | A dropped load must not show as offline/failed | Now covered by `a cancelled load is cancelled, not reported as offline or failed` |

## Unable to test

- Nothing required by the acceptance criteria. On-device (instrumented) runs aren't needed: AC2 names Robolectric/Compose unit tests, and those ran.
- The real HTTP stack inside the Hilt app (the `ReviewModule` provider plus auth interceptors against a server) was not exercised end to end. It is out of this task's AC and would need a running server. No follow-up needed for this task.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
