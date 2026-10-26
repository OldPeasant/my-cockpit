# Test Report

Date: 2026-10-26

Task: mail-attachment-to-files - Save mail attachment to Files (/Attachments/Mail)

Scope: `git diff 94293c2ec344830a9eafe10ac4902a316c4d0239..0296d140e47bffcffdb1b2d05c9fd1a7b96e5ba7` (task commits 079f8a4dd implementation, 03f5e8303 tests, 0296d140e review fixes CR-2/CR-3; base and commit list given by the parent worker, checked against the task's acceptance criteria and `touches`). Branch `main`, HEAD 0296d140e47bffcffdb1b2d05c9fd1a7b96e5ba7. Uncommitted: this report and one test addition in `MailAttachmentDownloadTests.kt`. `reviews/mail-attachment-to-files/review.md` was also uncommitted-modified during this run by another helper (not by the test-writer) and is not part of the tested inputs.

Requirements:
- `tasks/mail-attachment-to-files.md` (acceptance criteria and the two recorded assumptions)
- SPEC.md §7.3, §8.2 (as quoted by the task)
- docs/ARCHITECTURE.md "Coverage"
- `reviews/mail-attachment-to-files/review.md` (CR-1..CR-3) and `fixes.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailAttachmentDownloadTests.kt`

## Tests added or updated

Count: 1

- `copyAttachment returns the write failure, not a later close failure` (new): when the output stream fails on write and then on close, `copyAttachment` returns the write failure, closes the stream and ends the IMAP block normally. Save-to-files depends on this: its `TooLargeSignal` (thrown by write) must survive a failing close so that the response is 413 and not the generic buffer failure. This was the only uncovered branch in the changed code of `MailReadService.copyAttachment` (line 180, `failure ?: e` with `failure != null`).

Review of the tests already present (03f5e8303, 0296d140e) against the criteria found them sufficient otherwise: 201 + FileResponse + Location + ETag, `/Attachments/Mail`, numbered name, FILE -> EMAIL link (GreenMail integration and controller tests); 404 for unknown message, unlisted part and part missing on the server; 413 at max size + 1 (exact max is stored) with temp-file cleanup; 502 for an unreachable server or a read failure; 503 POOL_EXHAUSTED; CSRF rejection; local open/write/close failures are not mail failures (CR-2); `fileName`/`mime` sanitizing; `freeFileName` case-insensitivity, Trash and other-folder handling, validation; web `saveToFiles` POSTs to the new endpoint silently, propagates a 413 problem, and makes neither the old download nor the old upload request; the page passes `(id, partId)` and keeps its double-submit guard.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: POST .../save-to-files streams the part into `specialFolder(MAIL)` under the attachment filename, returns 201 FileResponse, links file to message | `MailAttachmentToFilesGreenMailTests.the attachment is stored in Attachments-Mail, linked to the message, a taken name gets a number`; `MailMessageControllerTests.saving an attachment to Files answers 201 with the file, its location and its version`; `MailAttachmentFilesServiceTests.the part is stored in the Mail folder under its name with its type and the upload limit, then linked`, `a part larger than the copy buffer is copied completely` | `cd server && ./gradlew integrationTest --tests '*MailAttachmentToFilesGreenMailTests' --offline`; `cd server && ./gradlew ktlintTestSourceSetCheck test --tests 'ch.sonensei.cockpit.mail.api.*' --tests 'ch.sonensei.cockpit.files.FilesServiceTests' jacocoTestReport --offline` | Pass |
| AC1: taken names get a suffix (case-insensitive, Trash ignored) | GreenMail test above; `MailAttachmentFilesServiceTests.a taken name is replaced by the free one the drive suggests`; `FilesServiceTests.freeFileName keeps an unused name and numbers a taken one, regardless of case`, `freeFileName ignores files in the Trash and in other folders`, `freeFileName validates and trims the name` | same as above | Pass |
| AC1: filename sanitized, "attachment" when missing (assumption) | `MailAttachmentFilesServiceTests.fileName *` (6 tests), `mime keeps a specific type and guesses a missing or generic one from the name`, `an unnamed generic part is stored as attachment with the default type`, `a generic type is guessed from the sanitized name` | focused unit command | Pass |
| AC1: Problem Details 404 | GreenMail `an unknown message or part is 404 Problem Details`; `MailConnectionAdviceWebTests.save-to-files answers a missing message or part with 404 and a too large one with 413 Problem Details`; service tests `an attachment the message does not list is 404 ...`, `an unknown message is 404`, `a part missing on the server is 404 ...` | both commands | Pass |
| AC1: Problem Details 502 | GreenMail `an unreachable server is 502 and stores nothing`; `MailMessageControllerTests.saving to Files while the connections are busy is 503 and on a mail server failure 502`; service `a failure reading from the mail server leaves the IMAP block as a failure and stores nothing` | both commands | Pass |
| AC1: Problem Details 503 | `MailMessageControllerTests.saving to Files while the connections are busy is 503 and on a mail server failure 502` | focused unit command | Pass |
| Assumption: buffered with limit `cockpit.files.max-size` else 413; temp file below data dir, removed | `MailAttachmentFilesServiceTests.a part of exactly the maximum size is stored`, `a part one byte over the maximum size is too large, ...`, `the temporary file lives below the data directory`, `a failure storing the file deletes the temporary file too`; `MailMessageControllerTests.an attachment over the maximum file size is 413 Problem Details`; new `MailAttachmentDownloadTests.copyAttachment returns the write failure, not a later close failure` | focused unit command | Pass |
| CR-2 fix: local open/write/close failures are no mail server failure | `MailAttachmentFilesServiceTests.a failing local write is no mail server failure`, `a buffer file that cannot be opened is no mail server failure`; `MailAttachmentDownloadTests.a client stream that cannot be opened is no mail server failure`, `copyAttachment closes the output on request and returns a close failure instead of throwing it`, `copyAttachment closes the output when reading from the mail server fails`, `the download leaves the client stream open`, new write-then-close test | focused unit command | Pass |
| Security: save-to-files requires CSRF | `MailConnectionAdviceWebTests.save-to-files without a CSRF token is rejected and saves nothing` | focused unit command | Pass |
| AC2: web `MailReadService.saveToFiles` uses the endpoint (generated client) | `mail-read.service.spec.ts`: `has the server save an attachment to Files`, `saves to Files silently and rejects with the server problem, without a second request`; `mail-read-page.spec.ts` save-to-files cases | `cd web && npx ng test --watch=false --include src/app/app.spec.ts --include 'src/app/features/mail/read/**/*.spec.ts'` (122 passed); `cd web && npm run coverage` (3665 passed) | Pass |
| AC2: `api/openapi.yaml` and web client regenerated | Not run by the test-writer (`npm run api:check` is part of `npm run check`) | - | Not run: left to the parent's `npm run check` / `.taskcheck` |
| AC2: `cd server && ./gradlew check` passes | full server check | `cd server && ./gradlew check --offline` | SEE_SERVER_CHECK |
| AC2: `cd web && npm run check` passes | Not run (lint, api:check, build); its unit-test part ran via `npm run coverage` | - | Not run: parent runs `npm run check` / `.taskcheck` |

## Focused test execution

Commands:

- `cd server && ./gradlew ktlintTestSourceSetCheck test --tests 'ch.sonensei.cockpit.mail.api.*' --tests 'ch.sonensei.cockpit.files.FilesServiceTests' jacocoTestReport --offline` - BUILD SUCCESSFUL (after the new test). Before the addition: 353 tests, 0 failures (18 classes, incl. FilesServiceTests 27, MailAttachmentFilesServiceTests 22, MailAttachmentDownloadTests 9, MailMessageControllerTests 9, MailConnectionAdviceWebTests 5); after it MailAttachmentDownloadTests has 10 tests, 0 failures, 0 errors.
- `cd server && ./gradlew integrationTest --tests '*MailAttachmentToFilesGreenMailTests' --offline` - BUILD SUCCESSFUL, 3 tests, 0 failures (2026-10-26T07:46:55Z).
- `cd web && npx ng test --watch=false --include src/app/app.spec.ts --include 'src/app/features/mail/read/**/*.spec.ts'` - 6 files, 122 tests passed.

Result:

Pass

## Full test execution

Commands:

- `cd web && npm run coverage` (all web unit tests): first run 1 failed / 3664 passed: `src/app/app.spec.ts > App > builds every navigation section, so none shows the coming soon placeholder` timed out at 5000 ms. The file is not touched by this task, passes when run alone (see focused command) and passed in the immediate rerun: 175 files, 3665 tests passed (start 08:49:23). Load-related timeout under coverage instrumentation, not a task defect.
- `cd server && ./gradlew check --offline`: SEE_SERVER_CHECK

Result:

SEE_FULL_RESULT

## .taskcheck

Result:

Not run (`sh scripts/check.sh` also covers android and web build; the parent worker runs it before `done`).

## Bugs found

None.

## Coverage

Status: verified (server unit-test JaCoCo and web Vitest coverage, fresh runs on the snapshot below). The GreenMail integration test is not included in the JaCoCo numbers (`jacocoTestReport` covers the `test` task only).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured for the server: JaCoCo XML `<line>` entries (executable lines only) intersected with the added/modified lines of `git diff -U0 94293c2ec..HEAD` per file: lines 97/98 (99.0%), branches on those lines 55/56 (98.2%). Web changed-line coverage not measured separately (all changed web lines lie in files with 100% line coverage).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-attachment-to-files`; base 94293c2ec344830a9eafe10ac4902a316c4d0239; HEAD 0296d140e47bffcffdb1b2d05c9fd1a7b96e5ba7; commits 079f8a4dd, 03f5e8303, 0296d140e as given by the parent worker; file list from `git diff --name-only <base>..HEAD -- server/src/main web/src` minus specs.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 0296d140e plus `MailAttachmentDownloadTests.kt` sha256 7e2099095f797eab2c2fee2339dace45ccff6f57bbc4bec5e85eeb1a493823a5 (no production file modified; `git status` shows only that test and the foreign review.md change).
- Exact included files: server `mail/api/MailAttachmentFilesService.kt`, `mail/api/MailMessageController.kt`, `mail/api/MailReadService.kt`, `files/domain/FilesService.kt`, `files/infra/FilesRepositories.kt`; web `features/mail/read/mail-read.service.ts`, `features/mail/read/mail-read-page.ts`, `api/generated/fn/mail-messages/save-mail-attachment-to-files.ts`. Excluded: `api/openapi.yaml` (generated contract, no executable code); `web/src/app/api/generated/functions.ts` and `index.ts` (re-export barrels, no statements, absent from the Vitest report). Missing instrumentation: `FilesRepositories.kt` has no JaCoCo entry because it only declares Spring Data interfaces (the new `existsActiveInFolderByName` is a `@Query` method without bytecode body); its behaviour is exercised by the `freeFileName` tests in FilesServiceTests against Postgres.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin, Gradle 9.8.1) via `./gradlew ktlintTestSourceSetCheck test --tests 'ch.sonensei.cockpit.mail.api.*' --tests 'ch.sonensei.cockpit.files.FilesServiceTests' jacocoTestReport --offline` in `server/`; Vitest 5.0.5 + `@vitest/coverage-v8` via `npm run coverage` in `web/` (all web specs).
- Run start/completion time and exit status: server ~08:45:45-08:46:36 +0100, BUILD SUCCESSFUL; web start 08:49:23, report written 08:50:31 +0100, all passed.
- Generated report paths and report hashes: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 edd99add9bd3037e8423039f7d4cfe96d32b4584a91cc66cc502777323a9d1b2 (08:46:36); `web/coverage/web/coverage-final.json` sha256 fa7ed2d9969fe5ed91c529ef8d32dfa81a746edd0800663a1551d8ee3cee8e73, `coverage-summary.json` (08:50:30).
- Evidence outputs are fresh and inputs stayed unchanged: yes; both reports were regenerated after the test addition (Gradle recompiled tests; Vitest rewrote `coverage/`), and no source or test file changed afterwards.
- Final run after latest test additions and production fixes: yes (includes fixes commit 0296d140e and the new test).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Server "Statements" = JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/.../mail/api/MailAttachmentFilesService.kt` | 389/412 (94.4%) | 39/40 (97.5%) | 13/14 (92.9%) | 57/58 (98.3%) |
| `server/.../mail/api/MailMessageController.kt` | 1063/1101 (96.5%) | 30/34 (88.2%) | 90/91 (98.9%) | 203/203 (100%) |
| `server/.../mail/api/MailReadService.kt` | 871/876 (99.4%) | 61/62 (98.4%) | 37/37 (100%) | 136/136 (100%) |
| `server/.../files/domain/FilesService.kt` | 1428/1594 (89.6%) | 87/114 (76.3%) | 51/54 (94.4%) | 197/222 (88.7%) |
| `server/.../files/infra/FilesRepositories.kt` | N/A (no bytecode) | N/A | N/A | N/A |
| Server aggregate (4 measured files) | 3751/3983 (94.2%) | 217/250 (86.8%) | 191/196 (97.4%) | 593/619 (95.8%) |
| `web/.../features/mail/read/mail-read.service.ts` | 14/14 (100%) | 15/15 (100%) | 7/7 (100%) | 11/11 (100%) |
| `web/.../features/mail/read/mail-read-page.ts` | 154/159 (96.9%) | 91/107 (85.0%) | 36/36 (100%) | 130/130 (100%) |
| `web/.../api/generated/fn/mail-messages/save-mail-attachment-to-files.ts` | 8/8 (100%) | 1/2 (50.0%) | 3/3 (100%) | 8/8 (100%) |
| Web aggregate (3 measured files) | 176/181 (97.2%) | 107/124 (86.3%) | 46/46 (100%) | 149/149 (100%) |

Aggregates exclude `FilesRepositories.kt` (not instrumented), `api/openapi.yaml`, `functions.ts`, `index.ts` (no executable code). The server numbers come from unit tests only; the GreenMail integration test is not in them.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailReadService.kt:180` (`failure ?: e` with an earlier write failure) | A size signal must survive a failing close (413, not buffer failure) | Added `copyAttachment returns the write failure, not a later close failure`; now covered |
| `MailAttachmentFilesService.kt:102` (`LimitedOutputStream.write(Int)`) | Single-byte write; `copyAttachment` always writes buffers | Not reachable through the endpoint; left uncovered |
| `MailAttachmentFilesService.kt:78` (null branch of `checkNotNull(transactions.execute ...)`) | `TransactionTemplate.execute` returns the lambda's non-null file | Defensive, unreachable; left uncovered |
| `FilesService.kt` lines 67, 130-135, 248-249, 330-332, 360, 396-408, 431, 459-460 | Pre-existing code outside this task (only `freeFileName`, lines 211-224, is new and fully covered) | Outside task scope |
| `MailMessageController.kt` 4 missed branches | Pre-existing endpoints; all 9 changed executable lines covered | Outside task scope |
| `mail-read-page.ts:206,208` (guards `!id || !partId`, already saving) | Pre-existing guards; only line 211 changed. The double-submit behaviour is tested through the disabled button | Defensive; left uncovered |
| `save-mail-attachment-to-files.ts:19` (generated `context` default branch) | Generated client code | Generated; left uncovered |

## Unable to test

- `npm run api:check` (openapi.yaml and generated client in sync), web lint and `ng build`: not run by the test-writer; part of `cd web && npm run check` and `.taskcheck`, which the parent runs.
- Very large attachments against a real mail server (memory behaviour of the streaming path): not tested; the copy-buffer and max-size tests cover the logic.

## Summary

SEE_SUMMARY
