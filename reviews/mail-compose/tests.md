# Test Report

Date: 2026-10-21

Task: mail-compose - Drafts and sending (SPEC.md §7.3: compose, reply, forward)

Scope: task base 3d685bd261f1b53e3de3b8e5129a0fa3f369e230 (merge-base with origin/main, supplied by the
parent), task commits cbe6405c..ab7dd97d, merged in 7512426efb95af95c05dc636ae685f0e50591f09 (measured
HEAD, branch main). Paths: `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/`,
`server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/`, `FilesService.open` in
`server/src/main/kotlin/ch/sonensei/cockpit/files/domain/FilesService.kt`, `api/openapi.yaml` (plus the
regenerated `web/src/app/api/generated/` client). Working tree clean at the start and end of all runs
(no uncommitted inputs).

Requirements:
- `tasks/mail-compose.md` acceptance criteria 1-4 and its recorded assumptions (drafts on IMAP Drafts,
  reply/forward endpoints, Sent append only for IMAP_SMTP, 25 MB limit, correspondents definition)
- SPEC.md §7.3, AGENTS.md, docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md

## Test files changed

- None in this run. The task's test files were written by earlier test-writer runs and are committed:
  `ComposeTextTests.kt`, `CorrespondentServiceTests.kt`, `DraftComposeGreenMailTests.kt`,
  `DraftControllerTests.kt`, `DraftMimeTests.kt`, `DraftSendGreenMailTests.kt`, `DraftWebTests.kt`,
  `MailSenderTests.kt`, `ReplyComposerTests.kt` (all under
  `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/`).

## Tests added or updated

Count: 0 in this run (existing task suite: 141 unit tests + 30 `external` GreenMail tests). The existing
suite covers every acceptance criterion (mapping below); the coverage review found no important untested
behavior that would justify new tests within this run's limited budget.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Drafts API create/update/delete saving to the Drafts folder | `DraftComposeGreenMailTests`: "a draft is saved to Drafts, replaced on update and keeps its id", "deleting a draft removes it from the server and the cache", "a message outside Drafts is no draft", "two saves based on the same version do not both win, the loser is 412", "a draft another client deleted on the server is 404 ..."; `DraftControllerTests$Create/$Update`, `DraftWebTests` | `cd server && ./gradlew integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.compose.*'`; `./gradlew test --rerun --tests 'ch.sonensei.cockpit.mail.compose.*'` | Pass |
| AC1 Send via SMTP (XOAUTH2 where needed), HTML + plain alternative, identity | `MailSenderTests` (9, incl. XOAUTH2 and rejected recipients), `DraftMimeTests` (19, multipart/alternative, headers), `DraftComposeGreenMailTests`: "a draft sent as an identity carries its From and Reply-To", "bcc recipients get the message ...", "a draft without recipients cannot be sent"; `DraftControllerTests$Sending` | same two commands | Pass |
| AC1 Attachments from upload or Files | `DraftComposeGreenMailTests`: "an upload into a draft without attachments keeps its body and is sent with the file", "an update keeps attachments chosen by partId ..."; `DraftSendGreenMailTests` size-limit cases (file refs, uploads, 404 unknown file); `DraftControllerTests$Upload` | same two commands | Pass |
| AC1 Append to Sent unless the provider does (Gmail) | `DraftComposeGreenMailTests` "a sent draft is received with its attachments, appended to Sent and removed from Drafts"; `DraftSendGreenMailTests` "a Google account sends without appending to Sent ...", "a Microsoft account sends without appending to Sent", "a Sent folder that is gone ... does not fail a send" | integrationTest command | Pass |
| AC2 Reply/reply-all/forward (quoting, References, forward attachments) | `ReplyComposerTests` (Recipients 12, Body 5, Subject 5, Threading 8), `ComposeTextTests` (26); `DraftComposeGreenMailTests` "a reply quotes the original and threads it, a forward carries its attachments", "a reply keeps its threading headers when edited ...", "a reply to a sent message threads under it ..."; `DraftSendGreenMailTests` reply/forward cases (cid images, limits, identity) | same two commands | Pass |
| AC2 Recent correspondents endpoint | `CorrespondentServiceTests` (11), `DraftControllerTests$Correspondents` (3) | unit command | Pass |
| AC3 `external` GreenMail: received with attachment, appended to Sent, draft replaced on update | `DraftComposeGreenMailTests` (15), `DraftSendGreenMailTests` (15), all tagged `external` | `cd server && ./gradlew integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.compose.*'` | Pass (30/30) |
| AC4 `api/openapi.yaml` regenerated, `OpenApiContractTest` passes without the flag | `ch.sonensei.cockpit.core.web.OpenApiContractTest` | `cd server && ./gradlew test --rerun --tests '*OpenApiContractTest'` (UPDATE_OPENAPI unset) | Pass (1/1) |

## Focused test execution

Command:

`cd server && ./gradlew test --rerun --tests 'ch.sonensei.cockpit.mail.compose.*'` (13:47-13:48 +02:00)

Result:

Pass - BUILD SUCCESSFUL, 141 tests, 0 failures, 0 errors, 0 skipped (ComposeTextTests 26,
CorrespondentServiceTests 11, DraftControllerTests 33, DraftMimeTests 19, DraftWebTests 13,
MailSenderTests 9, ReplyComposerTests 30).

Command:

`cd server && ./gradlew integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.compose.*'` (13:48:11-13:49:46 +02:00)

Result:

Pass - BUILD SUCCESSFUL, 30 tests (DraftComposeGreenMailTests 15, DraftSendGreenMailTests 15), 0 failures.

Command:

`cd server && ./gradlew test --rerun --tests '*OpenApiContractTest'`

Result:

Pass - 1 test, 0 failures.

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport` (13:49:55-13:56:34 +02:00)

Result:

Fail (unrelated to this task) - 3201 tests, 1 failure:
`ch.sonensei.cockpit.core.persistence.ClockConfigurationTests` "the application clock is the system clock
in UTC" (`2026-10-21T11:53:36.557145Z should be >= 2026-10-21T11:53:36.557145754Z`). The application clock
truncates to microseconds while the test compares against a nanosecond `Instant.now()`; it is a known
timing flake in `core/persistence` (also noted in the task file), outside this task's scope. All mail-compose
tests passed in that run. jacocoTestReport did not run because of the failure; coverage was measured in
the separate scoped run below.

Command:

`cd web && npm run check` (vitest 5.0.5, node 24.21.0)

Result:

Pass - 107 test files, 2130 tests passed (the task only regenerated `web/src/app/api/generated/`).

## .taskcheck

Result:

Fail (environmental, not task-related). `sh scripts/check.sh` (14:01:28-14:11:05 +02:00): the TFP gate tests
and `server: ./gradlew --quiet check` passed (the script is `set -eu` and sequential, so the web step only
starts after the server step succeeds); the web step reported 30 failed / 2100 passed tests in 13 files
(trash, calendar, setup, files, gtd, notes, settings) - none touched by this task. Re-running
`cd web && npm run check` alone directly afterwards passed 2130/2130, so the failures are load-related
flakes on a shared machine. The parent must re-run `.taskcheck` before `done`.

## Bugs found

None. No production defect was found by the tests in this run.

## Coverage

Status: verified (scoped run: compose unit tests + compose `external` GreenMail tests, exit 0).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured, except for `FilesService.kt`, where the task's only executable change
(`open`, line 67) was checked in the line-level XML: covered in the combined report, not covered by unit
tests alone.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-compose; base
  3d685bd261f1b53e3de3b8e5129a0fa3f369e230; HEAD 7512426efb95af95c05dc636ae685f0e50591f09; task commits
  cbe6405c..ab7dd97d and scope supplied by the parent, cross-checked with
  `git diff --name-only 3d685bd2..HEAD -- server/src/main` (9 files, all included).
- Source/test snapshot identifier (including uncommitted inputs): commit 7512426e, clean working tree
  before and after the runs (`git status --short` empty).
- Exact included files; renamed/deleted/excluded files and reasons: `files/domain/FilesService.kt`,
  `mail/compose/{ComposeProperties,ComposeText,CorrespondentService,DraftController,DraftMime,DraftService,MailSender,ReplyComposer}.kt`.
  No renames or deletions. Excluded: `api/openapi.yaml` and `web/src/app/api/generated/**` (generated, no
  hand-written executable behavior; covered by OpenApiContractTest), test files. All 9 files present in both reports.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin
  (Gradle 9.8.1, default tool version); working directory `server/`;
  `./gradlew -I ../.tmp/compose-coverage.init.gradle.kts test --rerun --tests 'ch.sonensei.cockpit.mail.compose.*' integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.compose.*' jacocoTestReport taskCoverageReport`.
  The project's `jacocoTestReport` reads only `test.exec`, so the AC3 GreenMail tests (`integrationTest.exec`)
  are not in it. A scratch Gradle init script in the Git-ignored `.tmp/` (no project configuration changed)
  registered `taskCoverageReport`, a JacocoReport over main classes with both `test.exec` and
  `integrationTest.exec`. Both reports are given below.
- Run start/completion time and exit status: about 13:57:00-14:00:08 +02:00 (test.exec 13:57:57,
  integrationTest.exec 14:00:04), BUILD SUCCESSFUL, exit 0.
- Generated report paths and report hashes/run identifier:
  `server/build/reports/jacoco/test/jacocoTestReport.xml` (sha256 4062e5d996a7e51017ce5afc8003ba290f2b33b02838b78c93e425db71125723, 14:00:08),
  `server/build/reports/jacoco/taskCoverage/report.xml` (sha256 672a50420a34d747995c763c2a6b88527e1087487b5fdc7bb020f4098aa3bf4a, 14:00:07).
- Evidence outputs are fresh and inputs stayed unchanged: both test tasks ran with `--rerun` (Gradle's
  jacoco agent replaces the exec file on each run); both report files have timestamps of this run; HEAD and
  the clean tree were unchanged before and after.
- Final run after latest test additions and production fixes: yes. No test or production change after
  ab7dd97d. This run added no tests.

Combined (unit + `external` GreenMail, `taskCoverage/report.xml`). Values below use covered/total
(percentage). Statements = JaCoCo INSTRUCTION, functions = METHOD.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `mail/compose/ComposeProperties.kt` | 29/40 (72.5%) | 3/6 (50.0%) | 3/3 (100.0%) | 7/7 (100.0%) |
| `mail/compose/ComposeText.kt` | 608/608 (100.0%) | 56/58 (96.6%) | 15/15 (100.0%) | 56/56 (100.0%) |
| `mail/compose/CorrespondentService.kt` | 288/288 (100.0%) | 31/32 (96.9%) | 8/8 (100.0%) | 33/33 (100.0%) |
| `mail/compose/DraftController.kt` | 1217/1286 (94.6%) | 68/78 (87.2%) | 61/75 (81.3%) | 176/176 (100.0%) |
| `mail/compose/DraftMime.kt` | 658/662 (99.4%) | 31/34 (91.2%) | 36/36 (100.0%) | 83/83 (100.0%) |
| `mail/compose/DraftService.kt` | 2546/2904 (87.7%) | 185/262 (70.6%) | 87/89 (97.8%) | 337/354 (95.2%) |
| `mail/compose/MailSender.kt` | 123/140 (87.9%) | 4/6 (66.7%) | 4/4 (100.0%) | 21/21 (100.0%) |
| `mail/compose/ReplyComposer.kt` | 634/636 (99.7%) | 64/66 (97.0%) | 19/19 (100.0%) | 69/69 (100.0%) |
| `files/domain/FilesService.kt` (whole file, task added only `open`) | 193/1545 (12.5%) | 7/110 (6.4%) | 10/51 (19.6%) | 31/218 (14.2%) |
| **Aggregate, all 9 files (summed counters)** | 6296/8109 (77.6%) | 449/652 (68.9%) | 243/300 (81.0%) | 813/1017 (79.9%) |
| **Aggregate, 8 `mail/compose` files** | 6103/6564 (93.0%) | 442/542 (81.5%) | 233/249 (93.6%) | 782/799 (97.9%) |

The `FilesService.kt` whole-file numbers are low because almost all of the file is pre-existing files-module
code that this scoped run does not exercise (the files module's own tests cover it). They say nothing about
this task. No files are missing or unmeasured.

Unit tests only (project `jacocoTestReport`, `test.exec` from the same run), for comparison:
DraftService.kt 453/2904 instr (15.6%), 31/262 br (11.8%), 73/354 lines (20.6%); DraftController.kt 174/176
lines; FilesService.kt 12/218 lines; the other compose files are the same as above. Aggregate over the 9
files: 4016/8109 instr (49.5%), 288/652 br (44.2%), 182/300 methods (60.7%), 528/1017 lines (51.9%).
DraftService is IMAP-bound and is exercised mainly by the GreenMail tests.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `DraftService.kt:485-486, 514-517, 583-586, 591-594` | IMAP `MessagingException`/`IOException` while opening the Drafts folder, appending or reading, mapped to `MailConnectionException` | Gap left: needs IMAP fault injection that GreenMail does not offer. The mapping is a one-line rethrow; `MailConnectionException` to Problem Details is tested at the controller level (`DraftControllerTests`) and SMTP connection failures in `MailSenderTests`. Low risk. |
| `DraftService.kt:501-502, 693` (`DraftChangedException`, thrown at 382) | Kept attachment part vanishes between reading the metadata and rebuilding inside the IMAP session (race with another client), reported as 404 | Gap left: only reachable in a narrow race inside one IMAP session. The neighboring "draft deleted/replaced by another client" paths (UID gone, UIDVALIDITY changed, 412 on stale version) are covered by GreenMail tests. |
| `DraftService.kt` partial branches (e.g. 268, 312, 342, 491, 519, 589, 596, 619) | Null-safety and `runCatching` close branches, alternative UIDVALIDITY null cases | Non-material: defensive branches, all lines executed. |
| `DraftController.kt:86, 143, 283, 439, 444, 528, 530` (partial branches); 14 synthetic methods uncovered | Null-safe defaults (`checkNotNull` after validation, `e.message ?: ""`, missing content type), address parsing edge combinations | Non-material: line coverage 100%; the address validation rules (groups, display names, blank) have tests in `DraftControllerTests$Create`. |
| `MailSender.kt:49` | `SendFailedException` with non-`InternetAddress` invalid addresses filtered out | Non-material: JavaMail only reports `InternetAddress` for SMTP; rejected recipient mapping is tested. |
| `ComposeProperties.kt:16` | Range validation of `cockpit.mail.compose.max-message-size` (out-of-range values fail start-up) | Low: configuration guard, not an acceptance criterion; left untested. |
| `ComposeText.kt:64, 93`; `DraftMime.kt:53, 84, 145`; `ReplyComposer.kt:126`; `CorrespondentService.kt:64` | Single elvis/blank branches | Non-material: lines are covered and the main behaviors have tests. |

## Unable to test

- IMAP-level connection failures inside `DraftService` (see the coverage table): GreenMail cannot inject
  mid-session I/O errors without production changes or a custom mock store. Optional follow-up, low priority.
- Real Gmail/Microsoft provider behavior (server-side Sent copy, real XOAUTH2 token exchange): no real provider
  accounts in tests. Covered by the provider-type logic in GreenMail tests and the XOAUTH2 mechanism in
  `MailSenderTests` against GreenMail/mocks. No follow-up needed for this task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. All 141 compose unit tests,
30 compose `external` GreenMail tests and the OpenApiContractTest pass on 7512426e. The full server suite
had one unrelated flaky failure (`ClockConfigurationTests`). In `.taskcheck` the web unit tests failed under
load but passed on a standalone re-run. The parent must re-run `.taskcheck` before `done`.
