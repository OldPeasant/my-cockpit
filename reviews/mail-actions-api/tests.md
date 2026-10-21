# Test Report

Date: 2026-10-21

Task: mail-actions-api (Mail actions)

Scope: `git diff bff56756ab7c7f00a421f70daf0de7f85dfcb155..ea2a06e000ee9bd3f842d3ddc50cb9c6a27240ee` (task commits 7a53377f, f68bef8e, 31f7a5d1, 5b2c1780, ce836284, ea2a06e0; base and commit list supplied by the parent worker), plus the uncommitted test additions of this run in `MailActionServiceTests.kt`. Branch `main`, HEAD `ea2a06e000ee9bd3f842d3ddc50cb9c6a27240ee`.

Requirements:
- `tasks/mail-actions-api.md` acceptance criteria and recorded assumptions (200 with per-id results in request order; NO_ROLE_FOLDER; MOVE/COPY/UIDPLUS behaviour; at most 1000 ids, no If-Match)
- SPEC.md §7.3 (triage actions), §4.2 (Problem Details)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md
- Existing test conventions: JUnit 5 + kotest matchers + MockK; MockMvc standalone; `@SpringBootTest` + `PostgresTestBase`; `@ExternalTest` GreenMail tests in `integrationTest`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionServiceTests.kt`

An earlier, interrupted test-writer run added the tests in commits 31f7a5d1 and ea2a06e0. This run reviewed them; they are part of the measured snapshot.

## Tests added or updated

Count: 8 (added in this run to `MailActionServiceTests`; no existing test was changed)

- `messages gone from the server fail with NOT_FOUND while the others of the folder are acted on`
- `when no requested message is on the server any more nothing is sent and the cache is left to the next sync`
- `a source folder deleted on the server fails the ids with NOT_FOUND`
- `a message whose account row is gone is NOT_FOUND without contacting the server`
- `a command answered with BAD fails the ids with REJECTED`
- `a row a sync relocated between lookup and cache update is not overwritten`
- `a COPYUID is trusted when the target folder has no cached UIDVALIDITY yet`
- `a failing close of the source folder does not fail an action the server applied`

Why: these partial-failure and consistency paths were covered only by GreenMail tests (not run by `.taskcheck`) or not at all. They now also run in the fast `test` suite.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `POST /api/v1/mail/messages/actions {ids, action, targetFolderId?}`: request shape, validation (empty / >1000 / null ids, missing or unknown action, malformed UUID/JSON, 415), Problem Details 400/404, auth + CSRF | `MailActionControllerTests` (11), `MailActionWebTests`: `request validation errors are 400 Problem Details`, `targetFolderId is required for MOVE and refused for every other action`, `MOVE to an unknown folder is a 404`, `MOVE to a folder that cannot hold messages is a 400`, `a POST without CSRF token is forbidden`, `actions need a login` | `cd server && ./gradlew ktlintCheck test --tests '*MailAction*' jacocoTestReport` | Pass |
| AC1: READ, UNREAD, FLAG, UNFLAG on IMAP and cache | GreenMail `read, unread, flag and unflag change the server flags and the cache`; service `flags change on the server and in the cache`, `unread and unflag clear the flags on the server and in the cache` | `./gradlew integrationTest --tests '*MailActionGreenMailTests'`; focused `test` command above | Pass |
| AC1: MOVE, ARCHIVE, SPAM, DELETE (to Trash; permanent in Trash) on IMAP and cache | GreenMail `move relocates the cached row to the new UID and keeps its id`, `archive and spam move to the role folders`, `delete moves to Trash, and deleting in Trash removes the message for good`, `a linked message deleted for good from Trash stays as a detached row`; service tests for no-MOVE / no-UIDPLUS / COPYUID handling, `delete in Trash without UIDPLUS ...`, `a COPYUID is trusted when the target folder has no cached UIDVALIDITY yet` | both commands above | Pass |
| AC1: partial failures reported per id | GreenMail `partial failures are reported per id`, `a move moves the messages still on the server and reports the others as NOT_FOUND`, `messages of several folders are acted on per folder in one request`; web `ids of several folders and accounts keep their request order and their own outcome`, `unknown ids fail with NOT_FOUND and duplicates count once, in request order`, `actions needing a missing or non-selectable role folder fail with NO_ROLE_FOLDER`, `MOVE to a folder of another account fails with OTHER_ACCOUNT`; service `a server failure in one source folder fails only that folder's ids, in request order`, `messages gone from the server fail with NOT_FOUND while the others of the folder are acted on`, REJECTED / CONNECTION_FAILED / NOT_FOUND tests | both commands above | Pass |
| AC1: cache stays consistent (conflicts, concurrent sync) | service `a version conflict ... is retried`, `a conflict that persists leaves the cache to the next sync ...`, `a row a sync cached at the new UID meanwhile ...`, `a row a sync relocated between lookup and cache update is not overwritten` | focused `test` command above | Pass |
| AC2: `external` GreenMail tests reflect every action | `MailActionGreenMailTests` (16 tests) | `cd server && ./gradlew integrationTest --tests '*MailActionGreenMailTests'` | Pass (16 tests, 0 failures) |
| AC3: API documented, `OpenApiContractTest` passes without the flag | `OpenApiContractTest` (run as part of `./gradlew check` in `.taskcheck`) | `sh scripts/check.sh` | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests '*MailAction*' jacocoTestReport` (also `./gradlew ktlintTestSourceSetCheck --rerun`, because ktlint reported UP-TO-DATE after the edit; it ran and passed)

Result:

Pass: 55 tests, 0 failures, 0 errors, 0 skipped (`MailActionControllerTests` 11, `MailActionServiceTests` 29, `MailActionWebTests` 15).

`cd server && ./gradlew integrationTest --tests '*MailActionGreenMailTests'`: Pass, 16 tests, 0 failures (GreenMail, `@ExternalTest`).

## Full test execution

Command:

`sh scripts/check.sh` (includes `cd server && ./gradlew --quiet check`)

Result:

Pass: every step passed ("all checks passed"). The server `test` task ran 2702 tests with 0 failures, 0 errors and 0 skipped.

## .taskcheck

Result:

Pass (`sh scripts/check.sh`, 2026-10-21 04:24:38 to 04:34:14 +02:00, exit 0). Note: it took about 9.5 min on this machine. That is under the 15 min `done` timeout but above the 2 min warning. My test additions are not the cause: they are plain MockK unit tests and run in a few seconds.

## Bugs found

None. No test failed against the production code.

## Coverage

Status: verified (for the JaCoCo `test` task; GreenMail `integrationTest` hits are not part of the configured report, see below)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Both files are new in this task (every line was added), so whole-file coverage equals changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-actions-api`; base `bff56756ab7c7f00a421f70daf0de7f85dfcb155`; HEAD `ea2a06e000ee9bd3f842d3ddc50cb9c6a27240ee`; commits 7a53377f, f68bef8e, 31f7a5d1, 5b2c1780, ce836284, ea2a06e0 as supplied by the parent; `git diff --name-only bff56756..HEAD -- server/src/main` lists exactly the two files below.
- Source/test snapshot identifier (including uncommitted inputs): HEAD ea2a06e0 plus these sha256 file hashes: `MailActionGreenMailTests.kt` 30a0cd5e60be40a0961ae0cf950f4cb6cf13261a11a46628591c9f8a85734207, `MailActionServiceTests.kt` (uncommitted, this run) 1d8ecc28014f91145a70ad326a5a27a51536188dc5b87cfbdcad6db5398b7df6, `MailActionWebTests.kt` a6f33df30712bee43902edddc6e7483efceda0b13b08433f1cd1d19cfb730d25, `MailActionController.kt` d776d7870253979c339d2d19345412beefc6fdc0bb3f0d15a267a2af8528f7e4, `MailActionService.kt` c2d7ab483edee8c15d028ff9c2af6d555b06c4e26a60c27a2a428bd7697084c3, `MailActionControllerTests.kt` 450ef63d3fa3277c73aff83867800427ff5ce4a6cf6763d9bb9a394f4cb332e7.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionController.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt`. Excluded: `api/openapi.yaml` and `web/src/app/api/generated/**` (generated), `reviews/**` (docs), test files. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo agent 0.8.15 (Gradle `jacoco` plugin); `./gradlew ktlintCheck test --tests '*MailAction*' jacocoTestReport` in `server/`; selection `*MailAction*` within the `test` task (excludes the `external` tag).
- Run start/completion time and exit status: 2026-10-21 04:22:25 to 04:23:25 +02:00, exit 0, BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (sha256 f7aeaade8ec717d67c16a5232324a8c8d563dcb863fce8ada79186910f74cfc4, written 04:23:25); `server/build/jacoco/test.exec` (sha256 f5cda04f29fc2c439968a11e999d2d92c4b51cd7533adb22b3a2e0ead76d87cf, written 04:23:21).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The exec and XML timestamps fall inside the run window, and the source/test hashes taken before and after the run are identical. The counters differ from an earlier run in this session (before the 8 additions: service 193/195 lines, 117/142 branches), which shows the report was regenerated.
- Final run after latest test additions and production fixes: yes. No production code changed during this run.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements (JaCoCo instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionController.kt` | 133/151 (88.1%) | 6/8 (75.0%) | 14/14 (100.0%) | 27/27 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt` | 1458/1520 (95.9%) | 128/142 (90.1%) | 42/42 (100.0%) | 195/195 (100.0%) |
| Aggregate (summed counters, both files measured, none missing) | 1591/1671 (95.2%) | 134/150 (89.3%) | 56/56 (100.0%) | 222/222 (100.0%) |

Coverage from the GreenMail `integrationTest` task (16 tests, also passing) is not in these numbers. The configured `jacocoTestReport` reads only `test.exec`, and I did not change the coverage configuration. The real-IMAP paths are verified by those tests' assertions, not by these counters.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailActionController.kt:55,57` (null branch of `checkNotNull(request.ids)` / `checkNotNull(request.action)`) | Defensive only: Bean Validation (`@NotEmpty`, `@NotNull`) rejects these before the method runs, as the controller tests show | Not reachable through HTTP; left uncovered |
| `MailActionService.kt:140,177` (`checkNotNull(folderId)` / `checkNotNull(uid)` null branches) | Defensive: `MailMessageLocator.current` returns only located rows | Not reachable; left uncovered |
| `MailActionService.kt:218` (`checkNotNull(target)` for MOVE) | Defensive: `target()` throws before this when MOVE has no target | Not reachable; left uncovered |
| `MailActionService.kt:247` (source folder without cached UIDVALIDITY) | Low risk: the action goes ahead without the UIDVALIDITY check on a folder that was never synced | Not tested; minor |
| `MailActionService.kt:278` (`isOpen == false`, `runCatching` failure bookkeeping) | A failing close must not fail the action | Covered by the new `a failing close of the source folder ...`; the remaining branches are Kotlin `runCatching` / `isOpen` bytecode variants with no further behaviour |
| `MailActionService.kt:355` (cached row with `uid == null` filtered out) | A row detached by a concurrent writer is not touched | Not tested separately; the related folder-mismatch filter is covered by `a row a sync relocated between lookup and cache update is not overwritten` |
| `MailActionService.kt:394-398` (`appended.uid <= 0`, destination folder row deleted meanwhile) | The row is removed and the next sync restores it | Rare server/race edge cases; the same fallback is covered by the UIDVALIDITY-mismatch and taken-UID tests; left uncovered |

## Unable to test

- Line/branch coverage of the GreenMail `integrationTest` run. The project's `jacocoTestReport` reads only `test.exec`, and adding an integration report task would change build configuration, which this role may not do. Follow-up is optional: the parent may decide whether a `jacocoIntegrationTestReport` task is wanted (outside this task's `touches`).
- Real IMAP servers without MOVE or UIDPLUS (GreenMail supports both). These paths are covered by the scripted-IMAP unit tests in `MailActionServiceTests`. No follow-up needed.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient, and testing found no production defects.
