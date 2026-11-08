# Test Report

Date: 2026-11-08

Task: and-mail-chip-a11y - Android compose: 48dp remove target and accessibility action for recipient chips

Scope: `3d7682042cd179cd8bc578ae269b340757ed8845..be05d532bd487f75f63db4af6fc9caa795ab66d5` (base and final revision supplied by the parent worker). The task commits are 0d1aaa7ff (implementation) and be05d532b (CR-1 fix: the disabled chip is dimmed once, plus the test-writer's TalkBack label test and the first reports). Production file in scope: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/RecipientInput.kt`. I made no uncommitted changes in this session.

Requirements:
- `tasks/and-mail-chip-a11y.md` acceptance criteria
- `AGENTS.md` project notes, `docs/ARCHITECTURE.md` "Coverage"
- `reviews/and-mail-chip-a11y/review.md` CR-1 and `reviews/and-mail-chip-a11y/fixes.md`

Provenance: the permission system again denied Bash in this session, so I could not run any command myself. Every execution result below was **received from the parent worker** (or from the fixer, where noted) for revision be05d532. My own contribution in this session is a static review of the final `RecipientInput.kt` (lines 188-251) and of the CR-1 change.

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeChipA11yTest.kt` (added by the task. The earlier test-writer run added one test to it, now committed in be05d532b. No change in this session.)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeRecipientsFilesReviewFixesScreenTest.kt` (assertion updated by the task commit. No change in this session.)

## Tests added or updated

Count: 0 in this session. Earlier test-writer run: 1 (`TalkBack reads the chip label on the chip node, which is not announced as a button`). `MailComposeChipA11yTest` has 7 tests in total.

CR-1 assessment (static, done by me): the fix removes `Modifier.alpha(0.38)` from the chip `Surface`. In its place, the label text color and the border color are multiplied by `DISABLED_ALPHA` when `enabled == false`, and the disabled `IconButton` dims the icon itself. This is a purely visual change. The semantics block (`disabled()` vs. the `Remove` custom action), the 48 dp `IconButton`, and the click wiring did not change. The existing test `while sending the chip is disabled and offers no Remove action` already runs the `enabled = false` path through the new `dim` code, and the enabled tests run `dim = 1f`. Effective alpha could only be checked with pixel or screenshot assertions, and the project has no convention for those in Robolectric unit tests. Such a test would also pin an implementation detail rather than an acceptance criterion. No new test is needed.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: remove control has at least a 48 dp touch target | `MailComposeChipA11yTest.the remove control has a 48 dp touch target` | `cd android && ./gradlew ktlintCheck testDebugUnitTest createDebugUnitTestCoverageReport` (parent, at be05d532) | Pass (received from parent) |
| AC2: chip body is not announced as an action that does nothing | `the chip body offers no click action`, `TalkBack reads the chip label on the chip node, which is not announced as a button`, `MailComposeRecipientsFilesReviewFixesScreenTest` (chip tap does not remove, no `OnClick`) | same | Pass (received from parent) |
| AC2: "Remove" accessibility action, English and German | `the chip offers a Remove accessibility action that removes only its recipient`, `an invalid recipient chip also offers the Remove action`, `the Remove action is German in a German locale`, `while sending the chip is disabled and offers no Remove action` | same | Pass (received from parent) |
| AC3: Compose tests cover it; ktlintCheck, testDebugUnitTest, lintDebug, `sh scripts/check.sh` pass | all of the above | ktlintCheck + testDebugUnitTest: parent command above. lintDebug and `sh scripts/check.sh`: not reported to me | ktlint Pass; mail.compose tests Pass; full testDebugUnitTest had 1 unrelated failure (see below), which the parent re-ran separately; lintDebug and check.sh: Not run by me, no result received. The parent must record them |
| CR-1: disabled chip dimmed once | no dedicated test (visual only, see assessment above); the disabled path is run by `while sending the chip is disabled and offers no Remove action` | parent command above; fixer: `./gradlew -q ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'` | Pass (received from parent and fixer). Not testable at the alpha level without screenshot infrastructure |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'`

Result:

Pass. Received from the parent worker (earlier focused run) and from the fixer after CR-1 (`-q ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.*'`). Not run by me: Bash was denied.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest createDebugUnitTestCoverageReport` (parent, at be05d532)

Result:

Fail, with one failure unrelated to this task: `ch.sonensei.cockpit.auth.AppStartRoutingTest > logging out from settings clears the cached files` (ComposeTimeoutException after 5000 ms in `waitUntil`). The parent reports it as a known timing flake under full-suite load and re-ran it separately. The test is outside the task's `touches`, and the task did not change it or the auth code. Every mail.compose test passed, including the 7 tests in `MailComposeChipA11yTest`, and ktlint passed. Received from the parent, not run by me. The parent should record the separate re-run result as evidence.

## .taskcheck

Result:

Not run by me (Bash denied). No result received. The parent worker must run `sh scripts/check.sh`.

## Bugs found

None. The static review of the final `RecipientInput.kt` matches all acceptance criteria, and the tests the parent ran all pass.

## Coverage

Status: partial. These are JaCoCo counters received from the parent's run at be05d532. I did not run coverage myself and could not hash or inspect the report.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-chip-a11y; base 3d7682042cd179cd8bc578ae269b340757ed8845; measured HEAD be05d532bd487f75f63db4af6fc9caa795ab66d5; task commits 0d1aaa7ff and be05d532b and the file scope were supplied by the parent worker and match the task `touches`.
- Source/test snapshot identifier (including uncommitted inputs): commit be05d532b. The git status was clean at the start of this session, and I made no source or test changes.
- Exact included files; renamed/deleted/excluded files and reasons: included `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/RecipientInput.kt`; no renames or deletions; test files and reports excluded.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `createDebugUnitTestCoverageReport` (version not reported to me); `cd android && ./gradlew ktlintCheck testDebugUnitTest createDebugUnitTestCoverageReport`; full debug unit test suite.
- Run start/completion time and exit status: not reported to me. The run contained one unrelated test failure (see "Full test execution"). The parent still reported RecipientInput.kt counters, so the report was generated despite that failure. Treat the numbers as diagnostic until the parent confirms a clean exit.
- Generated report paths and report hashes/run identifier: expected `android/app/build/reports/coverage/test/debug/report.xml`; no hash (I could not access a shell).
- Evidence outputs are fresh and inputs stayed unchanged: the parent states the run was at be05d532, the final revision. I could not verify this independently.
- Final run after latest test additions and production fixes: yes, per the parent (be05d532 includes the CR-1 fix and the added test).

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/RecipientInput.kt` | 1311/1332 (98.4%) | 59/62 (95.2%) | 27/28 (96.4%) | 131/131 (100.0%) |

Aggregate is the same as the single file. No scoped files are missing or excluded.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `RecipientInput.kt`: 21 instructions, 3 branches, 1 method (exact locations not reported to me) | Every line is covered. Compose-generated synthetic code (default-argument masks, recomposition skip branches, lambda bodies such as Preview or unused default parameters) typically leaves this kind of residue in JaCoCo. Every acceptance-criteria path (48 dp button, no click on the body, Remove action enabled/disabled, invalid chip, German label) has a test | Left uncovered. 100% line coverage and per-criterion tests make it unlikely that the misses are task behavior. If the parent wants certainty, the `<sourcefile name="RecipientInput.kt">` entries in report.xml with `mi>0` / `mb>0` show the exact spots |

## Unable to test

- I could not execute anything in this session because the permission system denied Bash/Gradle. All results above come from the parent or the fixer. Follow-up: the parent must record `lintDebug` and `sh scripts/check.sh`, plus the separate re-run of `AppStartRoutingTest`.
- Effective alpha of the disabled chip (CR-1): this needs screenshot or pixel tests, and the project has none. No follow-up needed, because it is a visual detail outside the acceptance criteria.
- Real TalkBack on a device: the Robolectric semantics tests stand in for it. This is acceptable for AC3 ("Compose tests cover it").

## Summary

Tests and task-scoped coverage are sufficient, and no production defects were found. The CR-1 change needs no new test. The execution results and coverage numbers come from the parent's run at be05d532 and the fixer's focused run; I did not run them myself. `lintDebug` and `sh scripts/check.sh` are still for the parent to run and record.
