# Test Report

Date: 2026-11-01

Task: and-mail-read-spam - Android mail read view: Spam button

Scope: task base b846f408b7aeac5aa1bcd071d2697f5cb002b841, implementation commit cff798fd8 (rebased as HEAD 6d90f103a0c4aaa78f5ffe7281fb1112b481e32c; `git diff cff798fd8 HEAD -- android/` is empty, so the measured production code is the implementation). Diff `git diff b846f408b..cff798fd8 -- android/`. Uncommitted inputs: the test files listed below (new tests from this run).

Requirements:
- `tasks/and-mail-read-spam.md` acceptance criteria and its two recorded assumptions (localized NO_ROLE_FOLDER, "Server error: CODE" for other codes; icon Report, label "Move to spam" / "Als Spam verschieben", tag `mail-read-action:SPAM`, placed after Delete)
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage"
- Existing test conventions in `android/app/src/test/java/ch/sonensei/cockpit/mail/read/`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadScreenTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/read/MailReadSpamGermanTest.kt` (new)

## Tests added or updated

Count: 11 added (the implementation commit already contained 6 spam tests and updated mocks; they were reviewed and kept unchanged).

Added:
- `MailReadScreenTest`: `a failed spam without a server code says only that it failed`, `a missing role folder names the folder of the action`, `a delete without a Trash folder names the Trash folder`, `the spam button comes after archive and delete`, `the spam button is disabled while an action runs`
- `MailReadViewModelTest`: `a running spam keeps the view busy, ignores a second tap, and offline it fails without a server code`
- `MailReadRepositoryTest`: `only the result for the mail acted on counts, and a success carries no error code`
- `MailReadSpamGermanTest` (Robolectric `@Config(qualifiers = "de")`): `the spam button has a German TalkBack label`, `a missing Spam folder is explained in German`, `another server code is shown with the German prefix`

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `MailListAction.SPAM("SPAM")`; Spam button after Archive and Delete with icon, TalkBack label, test tag | `MailReadScreenTest`: `the toolbar asks for back, the GTD actions, archive, delete and spam`, `the spam button has a TalkBack label`, `the spam button comes after archive and delete`; `MailReadRepositoryTest`: `spam posts the action and keeps the server's error code of a refusal` (wire value "SPAM") | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' createDebugUnitTestCoverageReport` | Pass |
| AC1: disabled while an action runs | `MailReadScreenTest`: `the actions wait while one runs, back still works` (includes the SPAM tag), `the spam button is disabled while an action runs`; `MailReadViewModelTest`: `a running spam keeps the view busy, ignores a second tap, ...` | same | Pass |
| AC2: success closes the view as after Archive/Delete | `MailReadViewModelTest`: `archived or deleted on the server, the mail is removed and the view closes` (iterates all entries incl. SPAM), `spam refused by the server stays open with its error code, then succeeds`; `MailReadScreenTest`: `the route moves the mail to spam and closes, or stays open with the server's error` | same | Pass |
| AC2: failure stays open and shows the server's error (NO_ROLE_FOLDER) | `MailReadViewModelTest`: `spam refused by the server stays open ...`, `a running spam ... offline it fails without a server code`; `MailReadScreenTest`: `a failed spam without a Spam folder shows the server's reason`, `another server error code is shown as it came`, `a failed spam without a server code says only that it failed`, `a missing role folder names the folder of the action`, `a delete without a Trash folder names the Trash folder`, route test above; `MailReadRepositoryTest`: both act tests | same | Pass |
| AC3: new strings in all existing locales (values, values-de) | English strings via all screen tests above; German via `MailReadSpamGermanTest` (3 tests) | same | Pass (lint MissingTranslation not run by me; parent ran `lintDebug` -> passed) |
| AC4: ViewModel unit test and Compose UI test for button, success and error | listed above | same | Pass |
| AC4: `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` passes | whole Android suite | parent ran `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' lintDebug` -> passed (before my additions) | Not run by me in full: the unfiltered `testDebugUnitTest` and `lintDebug` must be run by the parent on the final tree |

## Focused test execution

Command:

`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.read.*'` (first pass), then `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' createDebugUnitTestCoverageReport` (final, 2026-11-01 06:42:57 to 06:43:34 +01:00)

Result:

Pass. Final run: BUILD SUCCESSFUL; 280 tests in the `mail.*` selection, 0 failures, 0 errors, 0 skipped (summed from `app/build/test-results/testDebugUnitTest/*.xml`). Mail read classes: `MailReadScreenTest` 26, `MailReadViewModelTest` 19, `MailReadRepositoryTest` 7, `MailReadSpamGermanTest` 3. ktlintCheck passed on the new tests.

## Full test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`

Result:

Not run (unfiltered). Only the `mail.*` selection was run; the parent should run the full Android suite and lint on the final tree.

## .taskcheck

Result:

Not run (`sh scripts/check.sh` covers all parts and is slow; the parent runs it before `done`).

## Bugs found

None.

## Coverage

Status: verified (whole-file, `mail.*` test selection).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured with tooling. By inspecting the JaCoCo line data against the diff, every added executable line in the four files has hits; the only added lines with missed instructions are `MailReadScreen.kt:223` and `:227`, the compiler-generated default branch of an exhaustive `when` over `MailListAction` (unreachable).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-mail-read-spam; base b846f408b7aeac5aa1bcd071d2697f5cb002b841; HEAD 6d90f103a0c4aaa78f5ffe7281fb1112b481e32c (same Android content as cff798fd8, given by the parent); scope from `git diff --name-only b846f408b..cff798fd8 -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 6d90f103a plus sha256 `MailReadScreenTest.kt` d1846b78..., `MailReadViewModelTest.kt` 52e7e1b2..., `MailReadRepositoryTest.kt` 1203e707..., `MailReadSpamGermanTest.kt` 0eaf69eb...; no production changes in the working tree.
- Exact included files; renamed/deleted/excluded files and reasons: included `mail/read/MailReadRepository.kt`, `mail/read/MailReadScreen.kt`, `mail/read/MailReadViewModel.kt`, `mail/list/MailModels.kt`. Excluded: `res/values/strings_mail.xml`, `res/values-de/strings_mail.xml` (resources, not instrumented; exercised by English and German UI tests). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (AGP 9.4.1); `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' createDebugUnitTestCoverageReport` in `android/`; selection `ch.sonensei.cockpit.mail.*`.
- Run start/completion time and exit status: 2026-11-01T06:42:57+01:00 to 06:43:34+01:00, exit 0 (BUILD SUCCESSFUL).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 f8a4e6c0813ae4b9d9941c466a205b530e9b3263be618f8ed12675f294260f0a.
- Evidence outputs are fresh and inputs stayed unchanged: yes; the test task re-ran because a test file changed just before, and the report was regenerated by this run (previous run's report hash d5c619f9... differs).
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); JaCoCo instructions are reported as statements.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `mail/read/MailReadRepository.kt` | 572/578 (99.0%) | 52/52 (100%) | 29/31 (93.5%) | 57/57 (100%) |
| `mail/read/MailReadScreen.kt` | 2630/2703 (97.3%) | 124/140 (88.6%) | 44/51 (86.3%) | 201/205 (98.0%) |
| `mail/read/MailReadViewModel.kt` | 668/668 (100%) | 38/38 (100%) | 39/39 (100%) | 78/78 (100%) |
| `mail/list/MailModels.kt` | 619/624 (99.2%) | 61/62 (98.4%) | 45/46 (97.8%) | 70/70 (100%) |
| Aggregate (4 files, summed counters; string resources not instrumented) | 4489/4573 (98.2%) | 275/292 (94.2%) | 157/167 (94.0%) | 406/410 (99.0%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailReadScreen.kt:243` (DELETE -> Trash folder name), before this run | NO_ROLE_FOLDER on delete names the wrong folder | Added `a delete without a Trash folder names the Trash folder`; now covered |
| `MailReadScreen.kt:208, 223, 227` (1 branch each / missed instructions) | none: synthetic default branch of exhaustive `when` on a sealed type / enum | Unreachable, left uncovered |
| `MailReadScreen.kt:61-67, 101-105, 133, 139, 197-209, 325-332` | not task-changed (route defaults, callback defaults, Compose skip branches, pre-existing code) | Outside the task's changed behavior; no test added |
| `MailReadRepository.kt:20, 22` | not task-changed (pre-existing data class members) | Outside scope |
| `MailModels.kt:37, 97` | not task-changed (the task's line 86 `SPAM("SPAM")` is covered) | Outside scope |

## Unable to test

- Visual check of the Report icon on a device: the icon itself is not asserted (Compose semantics expose only the content description); the label and tag are tested. No follow-up needed.
- The unfiltered Android suite, `lintDebug` and `.taskcheck` were left to the parent (see above).

## Summary

Tests added and all validation passed (for the `mail.*` selection plus ktlintCheck); no production defects found. The parent should run the full `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `.taskcheck` on the final tree.
