# Test Report

Date: 2026-10-26

Task: mail-attachment-to-files - Save mail attachment to Files (/Attachments/Mail)

Scope: `git diff 6168d6141b6c5567cb2a8ecce615f41f1e644199..abc3e22d26a4bc872653912ac663cf551e3eb0ac` (base = merge-base with origin/main; task commits 36e19245d implementation, e037c1875 tests, aed1e4680 review fixes CR-2/CR-3, abc3e22d2 copyAttachment write-then-close test; base and commit list given by the parent worker, checked against the task's acceptance criteria and `touches`). These are the rebased equivalents of the earlier 079f8a4dd/03f5e8303/0296d140e plus the formerly uncommitted test; production code is unchanged since. Branch `main`; the working-tree HEAD c105222d0e50053e717aa74ceec2ccc16f041e13 differs from abc3e22d2 only in `reviews/` and `tasks/` (`git diff --quiet abc3e22d2 HEAD -- server web api` reports no difference). No uncommitted changes besides this report.

Requirements:
- `tasks/mail-attachment-to-files.md` (acceptance criteria and the two recorded assumptions)
- SPEC.md §7.3, §8.2 (as quoted by the task)
- docs/ARCHITECTURE.md "Coverage"
- `reviews/mail-attachment-to-files/review.md` (CR-1..CR-3) and `fixes.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailAttachmentDownloadTests.kt` (committed in abc3e22d2)

## Tests added or updated

Count: 1

- `copyAttachment returns the write failure, not a later close failure` (new): when the output stream fails on write and then on close, `copyAttachment` returns the write failure, closes the stream and ends the IMAP block normally. Save-to-files depends on this: its `TooLargeSignal` (thrown by write) must survive a failing close so that the response is 413 and not the generic buffer failure. This was the only uncovered branch in the changed code of `MailReadService.copyAttachment` (line 180, `failure ?: e` with `failure != null`).

Review of the tests already present (e037c1875, aed1e4680) against the criteria found them sufficient otherwise: 201 + FileResponse + Location + ETag, `/Attachments/Mail`, numbered name, FILE -> EMAIL link (GreenMail integration and controller tests); 404 for unknown message, unlisted part and part missing on the server; 413 at max size + 1 (exact max is stored) with temp-file cleanup; 502 for an unreachable server or a read failure; 503 POOL_EXHAUSTED; CSRF rejection; local open/write/close failures are not mail failures (CR-2); `fileName`/`mime` sanitizing; `freeFileName` case-insensitivity, Trash and other-folder handling, validation; web `saveToFiles` POSTs to the new endpoint silently, propagates a 413 problem, and makes neither the old download nor the old upload request; the page passes `(id, partId)` and keeps its double-submit guard. No further gap was found at the final code.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: POST .../save-to-files streams the part into `specialFolder(MAIL)` under the attachment filename, returns 201 FileResponse, links file to message | `MailAttachmentToFilesGreenMailTests.the attachment is stored in Attachments-Mail, linked to the message, a taken name gets a number`; `MailMessageControllerTests.saving an attachment to Files answers 201 with the file, its location and its version`; `MailAttachmentFilesServiceTests.the part is stored in the Mail folder under its name with its type and the upload limit, then linked`, `a part larger than the copy buffer is copied completely` | `server/gradlew -p server integrationTest --offline --tests '*MailAttachmentToFilesGreenMailTests'` (parent, abc3e22d2: 3 tests, 0 failures); `server/gradlew -p server check --offline` (parent, abc3e22d2) | Pass |
| AC1: taken names get a suffix (case-insensitive, Trash ignored) | GreenMail test above; `MailAttachmentFilesServiceTests.a taken name is replaced by the free one the drive suggests`; `FilesServiceTests.freeFileName keeps an unused name and numbers a taken one, regardless of case`, `freeFileName ignores files in the Trash and in other folders`, `freeFileName validates and trims the name` | same as above | Pass |
| AC1: filename sanitized, "attachment" when missing (assumption) | `MailAttachmentFilesServiceTests.fileName *` (6 tests), `mime keeps a specific type and guesses a missing or generic one from the name`, `an unnamed generic part is stored as attachment with the default type`, `a generic type is guessed from the sanitized name` | `server/gradlew -p server check --offline` | Pass |
| AC1: Problem Details 404 | GreenMail `an unknown message or part is 404 Problem Details`; `MailConnectionAdviceWebTests.save-to-files answers a missing message or part with 404 and a too large one with 413 Problem Details`; service tests `an attachment the message does not list is 404 ...`, `an unknown message is 404`, `a part missing on the server is 404 ...` | both commands | Pass |
| AC1: Problem Details 502 | GreenMail `an unreachable server is 502 and stores nothing`; `MailMessageControllerTests.saving to Files while the connections are busy is 503 and on a mail server failure 502`; service `a failure reading from the mail server leaves the IMAP block as a failure and stores nothing` | both commands | Pass |
| AC1: Problem Details 503 | `MailMessageControllerTests.saving to Files while the connections are busy is 503 and on a mail server failure 502` | `server/gradlew -p server check --offline` | Pass |
| Assumption: buffered with limit `cockpit.files.max-size` else 413; temp file below data dir, removed | `MailAttachmentFilesServiceTests.a part of exactly the maximum size is stored`, `a part one byte over the maximum size is too large, ...`, `the temporary file lives below the data directory`, `a failure storing the file deletes the temporary file too`; `MailMessageControllerTests.an attachment over the maximum file size is 413 Problem Details`; `MailAttachmentDownloadTests.copyAttachment returns the write failure, not a later close failure` | `server/gradlew -p server check --offline` | Pass |
| CR-2 fix: local open/write/close failures are no mail server failure | `MailAttachmentFilesServiceTests.a failing local write is no mail server failure`, `a buffer file that cannot be opened is no mail server failure`; `MailAttachmentDownloadTests.a client stream that cannot be opened is no mail server failure`, `copyAttachment closes the output on request and returns a close failure instead of throwing it`, `copyAttachment closes the output when reading from the mail server fails`, `the download leaves the client stream open`, the write-then-close test | `server/gradlew -p server check --offline` | Pass |
| Security: save-to-files requires CSRF | `MailConnectionAdviceWebTests.save-to-files without a CSRF token is rejected and saves nothing` | `server/gradlew -p server check --offline` | Pass |
| AC2: web `MailReadService.saveToFiles` uses the endpoint (generated client) | `mail-read.service.spec.ts`: `has the server save an attachment to Files`, `saves to Files silently and rejects with the server problem, without a second request`; `mail-read-page.spec.ts` save-to-files cases | `cd web && npx ng test --watch=false --include src/app/app.spec.ts --include 'src/app/features/mail/read/**/*.spec.ts'` (122 passed); `npm --prefix web run check` (parent, abc3e22d2: 3665 passed) | Pass |
| AC2: `api/openapi.yaml` and web client regenerated | `api:check` step of `npm run check` ("src/app/api/generated is up to date") | `npm --prefix web run check` (parent, abc3e22d2) | Pass |
| AC2: `cd server && ./gradlew check` passes | ktlint + all server unit tests | `server/gradlew -p server check --offline` (parent, abc3e22d2) | Pass: BUILD SUCCESSFUL in 8m 7s, 4739 tests, 0 failures |
| AC2: `cd web && npm run check` passes | api:check, `ng lint --max-warnings=0`, test:ci, build | `npm --prefix web run check` (parent, abc3e22d2) | Pass: exit 0, 175 files / 3665 tests passed |

## Focused test execution

Commands (test-writer, earlier run on code identical to abc3e22d2 in production code and web code):

- `cd server && ./gradlew ktlintTestSourceSetCheck test --tests 'ch.sonensei.cockpit.mail.api.*' --tests 'ch.sonensei.cockpit.files.FilesServiceTests' jacocoTestReport --offline` - BUILD SUCCESSFUL (after the new test). Before the addition: 353 tests, 0 failures (18 classes, incl. FilesServiceTests 27, MailAttachmentFilesServiceTests 22, MailAttachmentDownloadTests 9, MailMessageControllerTests 9, MailConnectionAdviceWebTests 5); after it MailAttachmentDownloadTests has 10 tests, 0 failures, 0 errors. The test file is byte-identical to the committed version (sha256 7e2099095f797eab2c2fee2339dace45ccff6f57bbc4bec5e85eeb1a493823a5).
- `cd server && ./gradlew integrationTest --tests '*MailAttachmentToFilesGreenMailTests' --offline` - BUILD SUCCESSFUL, 3 tests, 0 failures (2026-10-26T07:46:55Z); rerun by the parent on abc3e22d2 at 09:31 +0100: exit 0, 3 tests, 0 failures, 0 errors.
- `cd web && npx ng test --watch=false --include src/app/app.spec.ts --include 'src/app/features/mail/read/**/*.spec.ts'` - 6 files, 122 tests passed.

Result:

Pass

## Full test execution

Commands:

- `server/gradlew -p server check --offline` (ktlint + all server unit tests), run by the parent worker on abc3e22d2: BUILD SUCCESSFUL in 8m 7s, 4739 tests, 0 failures (finished ~09:30 +0100).
- `server/gradlew -p server integrationTest --offline --tests '*MailAttachmentToFilesGreenMailTests'`, run by the parent on abc3e22d2: exit 0, 3 tests, 0 failures, 0 errors (09:31 +0100).
- `npm --prefix web run check` (api:check "src/app/api/generated is up to date", `ng lint --max-warnings=0`, test:ci, build), run by the parent on abc3e22d2: exit 0, 175 files / 3665 tests passed.
- `server/gradlew -p server jacocoTestReport --offline` after the full check, run by the parent: fresh report used for the server coverage below.
- `cd web && npm run coverage` (all web unit tests, test-writer, earlier run): first run 1 failed / 3664 passed: `src/app/app.spec.ts > App > builds every navigation section, so none shows the coming soon placeholder` timed out at 5000 ms. The file is not touched by this task, passes when run alone and passed in the immediate rerun: 175 files, 3665 tests passed (start 08:49:23). Load-related timeout under coverage instrumentation, not a task defect; the parent's `npm run check` passed it.

The test-writer cannot run Gradle in this environment; the Gradle and `npm run check` results above are the parent worker's runs on the final code, recorded as reported.

Result:

Pass

## .taskcheck

Result:

Not run by the test-writer (`sh scripts/check.sh` also covers android; the parent worker runs it before `done`). Its server and web parts passed in the parent's runs above.

## Bugs found

None.

## Coverage

Status: verified. Server: JaCoCo report from the full server unit suite (parent's `jacocoTestReport` after `check`, on abc3e22d2), recomputed by the test-writer from the XML. Web: Vitest coverage from the earlier test-writer run at the pre-rebase revision 0296d140e, whose web sources and specs are identical to abc3e22d2 (the later task commits change only server code/tests). The GreenMail integration test is not included in the JaCoCo numbers (`jacocoTestReport` covers the `test` task only).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured for the server: JaCoCo XML `line` elements (executable lines only) intersected with the added/modified lines of `git diff -U0 6168d6141..abc3e22d2 -- server/src/main` per file: lines 97/98 (99.0%), branches on those lines 55/56 (98.2%). Uncovered changed line: `MailAttachmentFilesService.kt:102`; uncovered changed branch: `MailAttachmentFilesService.kt:78` (both justified below). Per file: MailAttachmentFilesService 57/58 lines, 39/40 branches; MailMessageController 9/9 lines, 0/0 branches; MailReadService 27/27 lines, 12/12 branches; FilesService 4/4 lines, 4/4 branches. Web changed-line coverage not measured separately (all changed web lines lie in files with 100% line coverage).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-attachment-to-files`; base 6168d6141b6c5567cb2a8ecce615f41f1e644199 (merge-base with origin/main); code revision abc3e22d26a4bc872653912ac663cf551e3eb0ac; commits 36e19245d, e037c1875, aed1e4680, abc3e22d2 as given by the parent worker; file list from `git diff --name-only 6168d6141..abc3e22d2 -- server/src/main web/src` minus specs.
- Source/test snapshot identifier (including uncommitted inputs): abc3e22d2, no uncommitted source or test changes; the working-tree HEAD c105222d0 has no diff under `server`, `web`, `api` against it. `MailAttachmentDownloadTests.kt` sha256 7e2099095f797eab2c2fee2339dace45ccff6f57bbc4bec5e85eeb1a493823a5.
- Exact included files: server `mail/api/MailAttachmentFilesService.kt`, `mail/api/MailMessageController.kt`, `mail/api/MailReadService.kt`, `files/domain/FilesService.kt`, `files/infra/FilesRepositories.kt`; web `features/mail/read/mail-read.service.ts`, `features/mail/read/mail-read-page.ts`, `api/generated/fn/mail-messages/save-mail-attachment-to-files.ts`. Excluded: `api/openapi.yaml` (generated contract, no executable code); `web/src/app/api/generated/functions.ts` and `index.ts` (re-export barrels, no statements, absent from the Vitest report). `FilesRepositories.kt` has a JaCoCo entry without counters: it only declares Spring Data interfaces (the new `existsActiveInFolderByName` is a `@Query` method without a bytecode body); its behaviour is exercised by the `freeFileName` tests in FilesServiceTests against Postgres.
- Provider/version, command, working directory, and test selection: server JaCoCo (Gradle `jacoco` plugin, Gradle 9.8.1) via `server/gradlew -p server check --offline` then `server/gradlew -p server jacocoTestReport --offline` from the repo root, all server unit tests (run by the parent worker); web Vitest 5.0.5 + `@vitest/coverage-v8` via `npm run coverage` in `web/`, all web specs (earlier test-writer run).
- Run start/completion time and exit status: server check finished ~09:30 +0100, BUILD SUCCESSFUL; JaCoCo report written 09:31:48 +0100. Web start 08:49:23, report written 08:50:31 +0100, all passed.
- Generated report paths and report hashes: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 078baa41917d60ad7857c90d2cff7f527d71542e06cbd0efb7100d2cb136386b (09:31:48); `web/coverage/web/coverage-final.json` sha256 fa7ed2d9969fe5ed91c529ef8d32dfa81a746edd0800663a1551d8ee3cee8e73, `coverage-summary.json` (08:50:30).
- Evidence outputs are fresh and inputs stayed unchanged: server yes (report regenerated after the full check on the final code; no source or test change since). Web: measured at 0296d140e; web sources and specs are identical at abc3e22d2.
- Final run after latest test additions and production fixes: yes for the server (includes fixes aed1e4680 and test abc3e22d2); web numbers are from the code-identical earlier revision.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Server "Statements" = JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/.../mail/api/MailAttachmentFilesService.kt` | 389/412 (94.4%) | 39/40 (97.5%) | 13/14 (92.9%) | 57/58 (98.3%) |
| `server/.../mail/api/MailMessageController.kt` | 1063/1101 (96.5%) | 30/34 (88.2%) | 90/91 (98.9%) | 203/203 (100%) |
| `server/.../mail/api/MailReadService.kt` | 871/876 (99.4%) | 61/62 (98.4%) | 37/37 (100%) | 136/136 (100%) |
| `server/.../files/domain/FilesService.kt` | 1592/1594 (99.9%) | 111/114 (97.4%) | 54/54 (100%) | 222/222 (100%) |
| `server/.../files/infra/FilesRepositories.kt` | N/A (no bytecode) | N/A | N/A | N/A |
| Server aggregate (4 measured files) | 3915/3983 (98.3%) | 241/250 (96.4%) | 194/196 (99.0%) | 618/619 (99.8%) |
| `web/.../features/mail/read/mail-read.service.ts` | 14/14 (100%) | 15/15 (100%) | 7/7 (100%) | 11/11 (100%) |
| `web/.../features/mail/read/mail-read-page.ts` | 154/159 (96.9%) | 91/107 (85.0%) | 36/36 (100%) | 130/130 (100%) |
| `web/.../api/generated/fn/mail-messages/save-mail-attachment-to-files.ts` | 8/8 (100%) | 1/2 (50.0%) | 3/3 (100%) | 8/8 (100%) |
| Web aggregate (3 measured files) | 176/181 (97.2%) | 107/124 (86.3%) | 46/46 (100%) | 149/149 (100%) |

Aggregates exclude `FilesRepositories.kt` (not instrumented), `api/openapi.yaml`, `functions.ts`, `index.ts` (no executable code). The server numbers come from the full server unit suite only (FilesService is higher than in the earlier focused run because the full suite exercises more of it); the GreenMail integration test is not in them.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailReadService.kt:180` (`failure ?: e` with an earlier write failure) | A size signal must survive a failing close (413, not buffer failure) | Added `copyAttachment returns the write failure, not a later close failure` (commit abc3e22d2); covered |
| `MailReadService.kt:84` (1 branch, `byId[...]?.let` null) | Pre-existing list paging code, not changed by this task | Outside task scope |
| `MailAttachmentFilesService.kt:102` (`LimitedOutputStream.write(Int)`) | Single-byte write; `copyAttachment` always writes buffers | Not reachable through the endpoint; left uncovered |
| `MailAttachmentFilesService.kt:78` (null branch of `checkNotNull(transactions.execute ...)`) | `TransactionTemplate.execute` returns the lambda's non-null file | Defensive, unreachable; left uncovered |
| `FilesService.kt` branches at lines 75, 247, 329 | Pre-existing code outside this task (the 4 changed executable lines of `freeFileName` and their 4 branches are covered) | Outside task scope |
| `MailMessageController.kt` branches at lines 498, 521, 554, 555 | Pre-existing endpoints; all 9 changed executable lines covered | Outside task scope |
| `mail-read-page.ts:206,208` (guards `!id || !partId`, already saving) | Pre-existing guards; only line 211 changed. The double-submit behaviour is tested through the disabled button | Defensive; left uncovered |
| `save-mail-attachment-to-files.ts:19` (generated `context` default branch) | Generated client code | Generated; left uncovered |

## Unable to test

- Gradle and `npm run check` were not run by the test-writer in this pass (Gradle is not available to sub-agents here); the parent worker's results on abc3e22d2 are recorded above.
- Very large attachments against a real mail server (memory behaviour of the streaming path): not tested; the copy-buffer and max-size tests cover the logic. No follow-up required for this task.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. One test was added (`copyAttachment returns the write failure, not a later close failure`, committed in abc3e22d2); no further gap was found at the final code. The server check (4739 tests), the GreenMail integration tests and `npm run check` (3665 tests, api:check, lint, build) passed on abc3e22d2. Task-scoped server coverage: lines 618/619, branches 241/250; changed lines 97/98, changed branches 55/56; the remaining gaps are justified above. `.taskcheck` remains for the parent worker to run before `done`.
