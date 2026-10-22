# Test Report

Date: 2026-10-22

Task: review-api - Weekly review API

Scope: the task commits on top of base `6c86ac42e673c9616c64dd7128b42c53d3aec152`, as given by the parent worker. Originally `2e90a66c7` (model, step data, endpoints) and `dc5ada32f` (web tests, regenerated `api/openapi.yaml` and web client). After rebasing onto main, plus the edge-case tests and the CR-1/CR-2 fixes, they are `9ddc593ca`, `e032a5573`, `bcaf26b02` and `84eb634fb`. Measured revision: `25e7d874b97bb68c868ecd19b9d5c96b2c2e5bc2`. The current HEAD `77be46f9de1495bf61ca3b8a2994128b72eb6078` (main merged in) has exactly the same content in `server/src/main/kotlin/ch/sonensei/cockpit/review/**`, `server/src/main/resources/db/migration/review/**` and `server/src/test/kotlin/ch/sonensei/cockpit/review/**`. `git diff --stat 25e7d874b HEAD` lists only `mail/backup`, `reviews/backup-contrib-mail`, `api` and `tasks/` files, so the results below also hold for HEAD.

Requirements:
- `tasks/review-api.md` acceptance criteria and its recorded assumptions (stale = open NEXT task with `updatedAt` more than N days ago, default 30; past = the 7 local days before today; upcoming = today plus 13 days; visible calendars only; cancelled occurrences left out; at most one open review; POST starts or resumes it; completing a completed review is 409)
- SPEC.md §8.6 (weekly review wizard), §4.2 (ETag/If-Match, ISO-8601, user time zone)
- docs/ARCHITECTURE.md "Coverage"
- The parent worker's instructions (test ideas, new test file)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/review/WeeklyReviewEdgeCaseTests.kt` (new; 26 tests by the test-writer, plus 1 test added by the fixer for CR-2: `within the stalled and the other projects the order is the one of the Projects view`)

`WeeklyReviewWebTests.kt` (9 tests, from the implementation) is unchanged.

## Tests added or updated

Count: 26 (by the test-writer; 27 in the file including the fixer's test)

## Requirement coverage

All commands below were run by the parent worker at `25e7d874b`. The test-writer is not allowed to run Gradle in this environment.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| `weekly_review` with step progress: start, resume, move to any step, complete, at most one open review | WebTests: `start creates a review at the first step...`, `the step is saved with If-Match and can go back`, `completing ends the review...`; EdgeCase: `concurrent starts create exactly one open review and all return it`, `the database rejects a second open review but allows many completed ones`, `the database rejects an unknown step`, `starting resumes an open review stored earlier, at its saved step`, `a started review has a Location to itself and its version as ETag` | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.review.*' jacocoTestReport` | Pass |
| Optimistic locking (SPEC §4.2) on move/complete | WebTests: `the step is saved with If-Match...`; EdgeCase: `If-Match star moves and completes the open review`, `If-Match accepts a list containing the current tag and rejects weak or blank tags`, `moving or completing an unknown review is a 404, also without If-Match`, `an explicit null step is rejected` | same | Pass |
| Complete review; completed review is 409; status "Last weekly review: N days ago" | WebTests: `completing ends the review...`, `no open review is a 404...`; EdgeCase: `the status picks the most recently completed review, not the last started or inserted one`, `a review completed today is 0 days ago`, `days since the last review count local days in the user's time zone` | same | Pass |
| Stale next actions (> 30 days, `olderThanDays`) | WebTests: `stale next actions are open Next tasks...`; EdgeCase: `a next action is stale only after more than the given days without a change`, `olderThanDays must be a number`, `tasks in a tickler are left out...`, `tasks and projects in the Trash or done are left out of every step`, `waiting for and stale next actions collect every page and sort across pages` | same | Pass |
| Waiting-for | WebTests: `waiting for lists open Waiting For tasks, waiting longest first`; EdgeCase: tickler, Trash/done and paging tests above | same | Pass |
| Projects (stalled first) | WebTests: `projects are the active ones, stalled first, each by title`; EdgeCase: `a project whose next step was completed is stalled and listed first`, `projects and someday collect every page`, Trash test, `within the stalled and the other projects the order is the one of the Projects view` (fixer, CR-2) | same | Pass |
| Someday | WebTests: `someday lists Someday tasks and SOMEDAY projects`; EdgeCase: tickler, Trash and paging tests | same | Pass |
| Past/upcoming events | WebTests: `past events are the 7 days before today...`; EdgeCase: `without calendars the calendar steps are empty but keep their range`, `events of hidden calendars are left out`, `the occurrences of a recurring event are listed in both windows, without cancelled or deleted ones`, `events spanning a window boundary are listed in each window they overlap, ordered by start`, `the windows follow the user's time zone when it is not Zurich`, `the upcoming window ends at local midnight across a daylight saving change`, `the Zurich windows follow the change to summer time` | same | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.review.*'` passes, Postgres via Testcontainers | all 36 review tests (`PostgresTestBase`) | same | Pass (WeeklyReviewEdgeCaseTests 27/27, WeeklyReviewWebTests 9/9; 0 failures, 0 skipped) |
| AC3: `api/openapi.yaml` regenerated, `OpenApiContractTest` passes without the flag | `OpenApiContractTest` (existing) | `UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`, then without the flag (parent) | Pass (reported by the parent; not run by the test-writer) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.review.*' jacocoTestReport` (run by the parent at `25e7d874b`)

Result:

Pass. ktlint clean; 36 tests, 0 failures, 0 skipped. The test-writer's 26 tests compiled and passed on the first run without changes.

## Full test execution

Command:

`cd server && ./gradlew check` / `sh scripts/check.sh`

Result:

Not run by the test-writer (no Gradle access here). The parent has not reported a full-suite result to the test-writer. The parent runs it as the `.taskcheck` before `done`.

## .taskcheck

Result:

Not run by the test-writer (see above; the parent runs it).

## Bugs found

None. No test exposed behavior that violates the acceptance criteria or the recorded assumptions.

The test-writer noted that the projects step re-sorted titles by code point, so it could differ from the Projects view. The spec only says "by title", so this was reported to the parent as a question, not as a failing test. The code reviewer raised it as CR-2, and the fixer fixed it at the measured revision (see `reviews/review-api/fixes.md`).

## Coverage

Status: verified (fresh JaCoCo run by the parent at the final reviewed code; per-file counters as reported by the parent)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. JaCoCo has no changed-line mode here. All review files are new in this task, so whole-file coverage equals changed-file coverage for them.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `review-api`; base `6c86ac42e673c9616c64dd7128b42c53d3aec152`; measured `25e7d874b97bb68c868ecd19b9d5c96b2c2e5bc2`. Ownership comes from the parent's commit list and the task's `touches` (`server/.../cockpit/review`, `server/src/test/.../review`, `db/migration/review`), checked with `git log` and `git diff --name-only 6c86ac42e..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): commit `25e7d874b`. There were no uncommitted server source/test changes; the working tree only has `tasks/review-api.md` modified and the untracked `reviews/review-api/`. The review sources are identical at HEAD `77be46f9d`.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/review/domain/ReviewDataService.kt`, `.../review/domain/WeeklyReviewService.kt`, `.../review/domain/WeeklyReview.kt`, `.../review/api/WeeklyReviewController.kt`, `.../review/api/WeeklyReviewDtos.kt`. Excluded: `.../review/infra/WeeklyReviewRepository.kt` (Spring Data interface, no JaCoCo counters); `server/src/main/resources/db/migration/review/V202610221000__review_weekly_review.sql` (SQL, cannot be instrumented; its unique index and step check are exercised by `the database rejects a second open review...` and `the database rejects an unknown step`); `server/src/main/kotlin/ch/sonensei/cockpit/mail/backup/MailBackupContributor.kt` (shows up in the base..HEAD diff only because main, with task `backup-contrib-mail`, was rebased in; not owned by this task). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (version as configured in `server/build.gradle.kts`; the parent did not report it); `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.review.*' jacocoTestReport`, run from `server/`; selection `ch.sonensei.cockpit.review.*` (WeeklyReviewEdgeCaseTests, WeeklyReviewWebTests).
- Run start/completion time and exit status: 2026-10-22, run by the parent after the fixer's changes; exit status 0 (all tests passed). Exact start and end times were not reported to the test-writer.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` and `.../html/index.html` (Git-ignored). The parent did not report a hash; the run is identified by revision `25e7d874b` and the test selection above.
- Evidence outputs are fresh and inputs stayed unchanged: the run came after the latest test addition (the fixer's CR-2 test) and the latest production change (CR-1/CR-2). The test count (27 + 9) matches the current files. The review sources did not change afterwards.
- Final run after latest test additions and production fixes: yes.

Values below use covered/total (percentage). JaCoCo has no statement counter, so the Statements column shows JaCoCo INSTRUCTION counters. Zero-denominator or unsupported metrics are N/A.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/review/domain/ReviewDataService.kt` | 307/336 (91.4%) | 9/12 (75.0%) | 23/26 (88.5%) | 44/45 (97.8%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/review/domain/WeeklyReviewService.kt` | 256/265 (96.6%) | 11/12 (91.7%) | 21/21 (100%) | 40/40 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/review/domain/WeeklyReview.kt` | 105/105 (100%) | 2/2 (100%) | N/A (not reported) | 18/18 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/review/api/WeeklyReviewController.kt` | 252/252 (100%) | 12/12 (100%) | N/A (not reported) | 31/31 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/review/api/WeeklyReviewDtos.kt` | 235/253 (92.9%) | 2/4 (50.0%) | N/A (not reported) | 48/48 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/review/infra/WeeklyReviewRepository.kt` | N/A (interface, no counters) | N/A | N/A | N/A |

Aggregate over the 5 measured Kotlin files (summed counters): instructions 1155/1211 (95.4%), branches 36/42 (85.7%), lines 181/182 (99.5%). Methods: N/A, because method counters were reported for only 2 of the 5 files (44/47 over those two). Not in the aggregate: `WeeklyReviewRepository.kt` (no counters), the Flyway migration (SQL, tested through the database tests) and `MailBackupContributor.kt` (another task). The project has no coverage threshold (docs/ARCHITECTURE.md "Coverage": not a gate).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ReviewDataService.kt:50` (part of the missed method and instruction counters) | `olderThanDays = STALE_DAYS` default argument: Kotlin's synthetic `staleNextActions$default` method. The controller always passes a value (its `@RequestParam` default is `STALE_DAYS`). | Gap left: unreachable from the API. The default of 30 is tested over HTTP (`stale next actions are open Next tasks...`, `a next action is stale only after more than the given days...`). |
| `ReviewDataService.kt:53` (partial branch) | `checkNotNull(it.updatedAt)` null branch | Gap left: defensive code; a persisted task always has `updatedAt` (BaseEntity, `not null` column). |
| `ReviewDataService.kt:54` (missed lambda/method) | Tie-break `{ it.id }` when two stale tasks have the same `updatedAt` | Gap left, low risk: it only makes the order of equal timestamps deterministic. The main key (`updatedAt`, oldest first) is tested on one page and across pages. Possible small follow-up: two NEXT tasks created at the same clock instant. |
| `ReviewDataService.kt:92` (partial branch / missed lambda) | Sort key of an occurrence: the `startDate?.` null branch (an occurrence with neither `start` nor `startDate`), and the tie-break on `event.id` when two occurrences start at the same time | Gap left: an occurrence with neither start is not possible (calendar timing validation). Equal starts only affect the order of ties. Both timed and all-day keys are tested (`events spanning a window boundary...`, `the upcoming window ends at local midnight...`). |
| `WeeklyReviewService.kt:79` (catch branch of `start()`) | Lost race at the one-open-review unique index: the loser returns the winner's review | Partly tested: `concurrent starts create exactly one open review and all return it` runs 10 rounds of 4 threads and checks the outcome on every run (one review, one `created`, the same id everywhere). Whether the threads actually collide depends on timing; in this run the catch branch did not execute. Covering it every time would need a repository spy or mock (a separate Spring context). The index itself is tested by `the database rejects a second open review...`. See CR-3 in `review.md`/`fixes.md`. |
| `WeeklyReviewDtos.kt:34,35` (partial branches) | `checkNotNull(review.createdAt/updatedAt)` null branches | Gap left: defensive code; a saved review always has both (not-null columns). |

## Unable to test

- Running Gradle (tests, coverage, `.taskcheck`): not allowed for the test-writer in this environment. All executions above were run by the parent and reported to the test-writer. No follow-up needed, as long as the parent runs `.taskcheck` before `done`.
- Ticklers whose date has come (`ticklerDate == today`, before `TaskService.releaseDue` has moved them to the Inbox) are not tested in the review steps. Neither the review nor the GTD list endpoints call `releaseDue` (only `TicklerJob` does), so this is existing GTD behavior and outside this task. No follow-up for this task.
- A concurrent optimistic-lock conflict inside JPA on move/complete (two writers with the same version passing `checkVersion` at the same moment) is not tested. Stale If-Match is tested (412). Low risk, no follow-up proposed.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.

---

# Round 2: re-check of the final revision 83e20d985

Date: 2026-10-22

Task: review-api - Weekly review API

Scope: base `06e12ecb30a0e5bd75b2e4b0aac04f85f4d048e2` (as given by the parent worker after the latest rebase), final HEAD `83e20d9851f54e21151f9c482771bc68e79fe2ca`. The previous round covered reviewed revision `e470880d5859c50a2e12bca656e8893a794ef41a` (the round 1 text above names `25e7d874b`, the same review code before a later rebase). The only code change since then is `83e20d985`: `server/build.gradle.kts`, test `maxHeapSize` `1g` -> `2g`, plus its comment.

Requirements:
- `tasks/review-api.md` acceptance criteria (unchanged since round 1)
- The parent worker's instructions for this re-check

## Scope check

- `git diff --stat e470880d5 HEAD -- server/src/main/kotlin/ch/sonensei/cockpit/review server/src/test/kotlin/ch/sonensei/cockpit/review server/src/main/resources/db/migration/review` -> empty. The review production code, its migration and its tests are byte-identical to the revision tested in round 1.
- `git diff --name-only 06e12ecb3 HEAD -- server` -> only the 6 review production files, the review migration, the 2 review test files and `server/build.gradle.kts`. No other server file belongs to the task.
- `git diff --stat e470880d5 HEAD` also lists `mail/search`, `mail/sync`, `mail/api`, `mail/imap`, `core/search` sources and tests, `api/openapi.yaml` and the generated web client. These are absent from `06e12ecb3..HEAD`, so they came from main (task `mail-search`) during the rebase and are not owned by this task.
- `git show 83e20d985` -> one file, `server/build.gradle.kts`, 3 insertions and 2 deletions in `tasks.test { ... }`: `maxHeapSize = "1g"` became `"2g"`, and the comment explains the change.
- Working tree clean (`git status --short` empty); no uncommitted inputs.

## Test files changed

None in this round.

## Tests added or updated

Count: 0. The heap setting is build configuration with no product behavior. The test is that the suite runs: the full server suite failed with "Java heap space" at 1g and passes at 2g (see below). A unit test that asserts the Gradle property would only copy the configuration and protect nothing.

## Requirement coverage (round 2)

All commands were run by the parent worker at `83e20d985`. The test-writer cannot run Gradle here.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| All round 1 rows (review model, locking, steps, events) | unchanged review tests (27 EdgeCase + 9 Web) | `cd server && ./gradlew test` (full server suite, includes `ch.sonensei.cockpit.review.*`) | Pass (BUILD SUCCESSFUL in 5m58s, reported by the parent) |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.review.*'` passes | same 36 tests | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.review.*' jacocoTestReport` (round 1, identical review code) and the full `./gradlew test` above | Pass |
| Server suite no longer runs out of heap (reason for `83e20d985`) | whole server suite | `cd server && ./gradlew test` with 1g, then with 2g (parent) | 1g: failed with "Java heap space" in Gradle Test Executor; 2g: Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.review.*' jacocoTestReport` (parent, round 1, review code identical to HEAD)

Result:

Pass (36 tests).

## Full test execution

Command:

`cd server && ./gradlew test` (parent, at `83e20d985`, 2g heap)

Result:

Pass (BUILD SUCCESSFUL in 5m58s).

## .taskcheck

Result:

Not run by the test-writer (no Gradle access). The parent runs `sh scripts/check.sh` before `done`.

## Bugs found

None.

## Coverage

Status: verified, carried over from round 1. The review sources and tests are byte-identical to the measured revision (the scope check above). A heap-size change does not change which code the tests execute, so the JaCoCo counters in round 1 still describe this code.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (as in round 1).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `review-api`; base `06e12ecb3`; final HEAD `83e20d985`; the coverage was measured on the same review code (round 1). Ownership: `git diff --name-only 06e12ecb3 HEAD -- server` and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): commit `83e20d985`, clean working tree.
- Exact included files; renamed/deleted/excluded files and reasons: the same 5 Kotlin files as round 1. Excluded as before: `WeeklyReviewRepository.kt` (interface, no counters) and the Flyway migration (SQL). New exclusion: `server/build.gradle.kts` (build configuration, not instrumentable, no product behavior).
- Provider/version, command, working directory, and test selection: no new coverage run this round. Round 1 values are kept, as the parent instructed.
- Run start/completion time and exit status: no new coverage run. The full `./gradlew test` at `83e20d985` exited 0 (parent).
- Generated report paths and report hashes/run identifier: see round 1.
- Evidence outputs are fresh and inputs stayed unchanged: the review inputs did not change since the measured run (empty diff on the review paths).
- Final run after latest test additions and production fixes: yes for the tests (the full suite at `83e20d985`); coverage is from round 1 on identical review code.

The coverage table and the uncovered-gap table in round 1 still apply unchanged: aggregate over the 5 measured files, instructions 1155/1211 (95.4%), branches 36/42 (85.7%), lines 181/182 (99.5%), methods N/A.

## Unable to test

- Gradle runs by the test-writer (see round 1). All results in this round come from the parent.

## Summary

No additional tests were necessary for the reviewed scope. The review module is unchanged since the last tested revision, the only new change (`server/build.gradle.kts` test heap 1g -> 2g) is build configuration, and the parent's full server suite passes with it. No production defects were found.
