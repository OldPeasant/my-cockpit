# Test Report

Date: 2026-10-22

Task: mail-search - Mail search

Scope: the task's commits after the rebase on main: `git diff d41cf2b3d04b9ce2d8fecb0f233902cc7774eca1..2dd9badb72c01e962aaf36dd97f40cb4e787a63a` (merge-base with origin/main .. HEAD on `main`; task commits 0abcafcd4, 127cbeb4e, 1687d8f90, 7a39a2142, c1bf57ae9, 2dd9badb7). Before the rebase the same commits were 596760fe8 .. a36ff687a, merged as 67de2a00a on base c04c9f5e6; `git diff 67de2a00a 2dd9badb7 -- server api web` is empty, so the code is the same. This round's uncommitted test change in `MailSearchWebTests.kt` is also in scope.

Requirements:
- tasks/mail-search.md acceptance criteria 1-3 and the five recorded assumptions (Notes)
- SPEC.md §7.3 (mail search), §4.4 (search_document), via the task and the assumptions
- docs/ARCHITECTURE.md "Coverage", AGENTS.md project notes, docs/QUALITY.md
- reviews/mail-search/review.md (round 2, 0 findings) and reviews/mail-search/fixes.md
- Existing test conventions: Spring Boot + MockMvc + `PostgresTestBase` (Testcontainers), kotest matchers, scripted `RemoteMailbox` fakes, GreenMail for the IMAP path

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/search/MailSearchWebTests.kt` (this round, uncommitted, sha256 3e30fd5c474a04c950ece014cf70e6e2267dc3726d4dadc47fb51dada432ccb9)

These test files came from earlier rounds and are already in the task commits. I reviewed them but did not change them: `MailSearchIndexTests`, `MailSearchReconcilerTests`, `MailSearchWebTests`, `MailSearchWordsTests`, `MailSearchGreenMailTests` (integration), `MailHeaderSyncServiceTests`, `MailActionServiceTests`, `MailFolderSyncTests`, and the test `application.properties` (which turns the reconciler off in tests).

## Tests added or updated

Count: 3 (1 added, 2 strengthened)

- Added `server search skips a folder removed from the cache while the search runs`. A folder list sync deletes a folder while `server=true` is running. The test expects that folder to be skipped (no 500), the other folder's hit to be cached and returned, and no failure to be reported. This runs the `NotFoundException` catch in `MailSearchService.searchServer`, which no test reached before. `FakeMailbox` got an `onOpen` hook (reset in `setUp`) so the test can make this change at a fixed point.
- Strengthened `server search reports an unreachable account and still answers with the other hits`. It now checks that `failures[].message` is the fixed `MailConnectionError` message, not the exception cause, so no server response leaks into the API.
- Strengthened `a query without words finds nothing and does not ask the server`. It now checks that the response has `server: null` when `server=true` is not set. This branch at `MailSearchService.kt:78` was not run before.

## Requirement coverage

Commands, all run by the parent worker on the code of HEAD 2dd9badb7 / 67de2a00a:
- (A) `server/gradlew -p server test --tests 'ch.sonensei.cockpit.mail.*' --tests '*OpenApiContractTest' jacocoTestReport` on HEAD 67de2a00a, clean tree, without this round's test change -> BUILD SUCCESSFUL, 1145 tests, 0 failures, 0 errors, 0 skipped
- (B) `server/gradlew -p server test --tests 'ch.sonensei.cockpit.mail.search.*' jacocoTestReport` on the final working tree, with this round's change -> BUILD SUCCESSFUL, 36 tests, 0 failures/errors/skipped. I checked `server/build/test-results/test/TEST-ch.sonensei.cockpit.mail.search.MailSearchWebTests.xml`: tests=21, failures=0, errors=0, skipped=0, and it includes the new test.
- (C) `server/gradlew -p server integrationTest --tests '*MailSearchGreenMailTests'` -> BUILD SUCCESSFUL, 1 test, 0 failures

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1a: cached headers are indexed in search_document (subject, sender/recipient names and address parts, snippet; recency = arrival) | `MailSearchIndexTests` (all 7); `MailSearchWebTests`: finds cached headers by subject, sender and recipient words; cached mail is found by the global search too, with arrival as recency; query text with full-text operators ... plain words; `MailSearchWordsTests` (4) | (A), (B) | Pass |
| AC1b: cached bodies are indexed (plain text, else the text of the HTML); the body stays after a header re-sync and a UIDVALIDITY reattach; the body is indexed when it is read from the server | `MailSearchWebTests`: finds cached bodies...; a body indexed when it was cached...; a message found again after a UIDVALIDITY change...; `MailSearchGreenMailTests` (the body is found only after reading it through `GET /api/v1/mail/messages/{id}`) | (A), (B), (C) | Pass |
| AC1c: removed, detached, deleted-folder and account-deleted mail leaves the index (hooks + reconciler) | `MailSearchWebTests`: removed and detached messages are not found; messages of a folder deleted from the cache are not found; reconcile indexes ... drops documents of gone mail; reconcile drops documents of messages whose folder was deleted...; `MailSearchReconcilerTests` (4); `MailFolderSyncTests` (the folder sync removes the document); `MailActionServiceTests` (remove unindexes) | (A); search-package tests also (B) | Pass |
| AC1d: `GET /api/v1/mail/search` scope, newest first, limit/hasMore, validation (400/404) | `MailSearchWebTests`: scopes by folder and account...; limit cuts newest first and reports more; limit and query length are accepted at their bounds...; invalid requests are 400 or 404 | (A), (B) | Pass |
| AC1e: `server=true` runs IMAP SEARCH in each selectable folder, caches the found uncached headers (most recent `limit`), merges them, and reports unreachable accounts in `server.failures` | `MailSearchWebTests`: server search caches found messages...; reports an unreachable account...; keeps the most recent matches per folder and skips folders gone from the server...; scoped to a folder that cannot hold messages...; an unreachable account is tried once...; a query without words ... does not ask the server; `MailHeaderSyncServiceTests` (searchServer cases); `MailSearchGreenMailTests` (real IMAP `UID SEARCH`, ASCII and UTF-8 CHARSET, uncached old mail) | (A), (B), (C) | Pass |
| AC1e (race): a folder removed while the server search runs is skipped, no 500 | `MailSearchWebTests`: server search skips a folder removed from the cache while the search runs (new) | (B) | Pass |
| AC1e: the failure message is the fixed message with no cause leaked; a search without words and without `server` returns `server: null` | the strengthened assertions in `MailSearchWebTests` (see above) | (B) | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.mail.search.*'` passes (Postgres via Testcontainers) | all `ch.sonensei.cockpit.mail.search.*` classes in the unit `test` task | (B) runs exactly this selection, on the final tree | Pass (36 tests) |
| AC3: `api/openapi.yaml` regenerated; `OpenApiContractTest` passes without the flag | `OpenApiContractTest` | (A), without `UPDATE_OPENAPI` | Pass. The test change has no effect on the API |

## Focused test execution

Command:

`server/gradlew -p server test --tests 'ch.sonensei.cockpit.mail.search.*' jacocoTestReport` (run by the parent on the final working tree)

Result:

Pass: BUILD SUCCESSFUL, 36 tests, 0 failures, 0 errors, 0 skipped. Integration: `server/gradlew -p server integrationTest --tests '*MailSearchGreenMailTests'` -> Pass (1 test).

## Full test execution

Command:

`server/gradlew -p server test --tests 'ch.sonensei.cockpit.mail.*' --tests '*OpenApiContractTest' jacocoTestReport` (run by the parent on HEAD 67de2a00a, whose code is the same as 2dd9badb7)

Result:

Pass: 1145 tests, 0 failures, 0 errors, 0 skipped. This run did not include this round's change to `MailSearchWebTests.kt`. That change only touches that one test class, and run (B) covers it. The whole server suite (`./gradlew test` without a filter) was not run for this report.

## .taskcheck

Result:

Not run: `.taskcheck` is `sh scripts/check.sh`, and this helper is not allowed to run Gradle. The parent runs it before `done`.

## Bugs found

None. No test exposed a production defect.

## Coverage

Status: verified. The final run (B) measures the four `mail/search` files on the final tree. The hook files outside `mail/search` were measured by run (A) on the same production code without this round's test change, which does not affect them. I give the two runs separately and do not add their numbers together.

Metric: whole-file coverage of the production files the task changed. Only the server unit `test` task is measured: the GreenMail `integrationTest` is not instrumented by `jacocoTestReport`.
Changed-line coverage: I took the JaCoCo HTML line classes (`fc`/`pc`/`nc`) and matched them against the added and modified lines of `git diff -U0 c04c9f5e6..67de2a00a -- server/src/main` (the same code as `d41cf2b3d..2dd9badb7`) (read-only script `.tmp/linecov.py`). Run (A): 245/261 changed executable lines were run, fully or partly. Run (B): 238/261, lower only because it runs just the search-package tests. Both runs miss `MailSearchService.kt:25`, which is a Kotlin generated synthetic. The `NotFoundException` lines `MailSearchService.kt:133,135` were missed in (A) and are covered in (B). I did not measure changed-branch coverage separately.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-search. The parent gave base c04c9f5e6216fe23d76bc2b0597eb05e65cd7a1e and HEAD 67de2a00aca45ac68e888af55660705a1f82bd94. After the rebase, the base is d41cf2b3d04b9ce2d8fecb0f233902cc7774eca1 (`git merge-base HEAD origin/main`) and HEAD is 2dd9badb72c01e962aaf36dd97f40cb4e787a63a. Between the two HEADs only `tasks/` changed. The parent named the task commits; I checked them against `git log` and the task Notes, where assumptions #1 and #5 record the hook files outside `touches`.
- Source/test snapshot identifier (including uncommitted inputs): (A) used the clean tree of 67de2a00a. (B) used the code of 2dd9badb7 plus the uncommitted `MailSearchWebTests.kt` (sha256 3e30fd5c474a04c950ece014cf70e6e2267dc3726d4dadc47fb51dada432ccb9). After (B), `git status` shows only that file and this report as changed.
- Exact included files; renamed/deleted/excluded files and reasons: the 13 files the task changed under `server/src/main` (tables below). There are no renames or deletions. Excluded: `api/openapi.yaml` and `web/src/app/api/generated/**` (generated), `reviews/`, `tasks/`, test files, and the test `application.properties`.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin, version as configured in `server/build.gradle.kts` (unchanged). Commands (A) and (B) above, run by the parent from the clone root.
- Run start/completion time and exit status: (A) finished 2026-10-22 01:28 +0200, BUILD SUCCESSFUL. (B) finished 2026-10-22 01:33:21 +0200 (XML timestamp), BUILD SUCCESSFUL.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`. (A) had sha256 ae037b0a0c41898395fce5f1d80143489d2d2862a9d16bbd12a4c719d1513a2c, and (B) overwrote it with sha256 81904decbede810faf4254cc608bf75e583e3fbbb14d12b8d34f6ec81b38c1e8. I read the per-file counters of both with `.tmp/xmlcov.py`.
- Evidence outputs are fresh and inputs stayed unchanged: each XML postdates the inputs it measured, and its `mail/search` counters match the numbers the parent reported for that run.
- Final run after latest test additions and production fixes: yes for the `mail/search` files, run (B). Coverage of the hook files comes from (A), the latest full mail run. This round changed no production code, and the new test does not target the hook files.

JaCoCo has no statement counter, so the Instructions column takes its place. Values are covered/total (percentage).

Final run (B), `mail/search` files:

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/search/MailSearchController.kt` | 224/226 (99.1%) | 17/18 (94.4%) | 15/15 (100%) | 43/43 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/search/MailSearchService.kt` | 584/599 (97.5%) | 33/34 (97.1%) | 24/25 (96.0%) | 100/101 (99.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/search/MailSearchIndex.kt` | 291/314 (92.7%) | 28/28 (100%) | 11/12 (91.7%) | 37/41 (90.2%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/search/MailSearchReconciler.kt` | 58/66 (87.9%) | 5/6 (83.3%) | 2/4 (50.0%) | 15/17 (88.2%) |

Aggregate for (B) over these 4 files (summed counters): instructions 1157/1205 (96.0%), branches 83/86 (96.5%), methods 52/56 (92.9%), lines 195/202 (96.5%). `MailSearchIndex.unindexFolder` (4 lines) is not covered in (B) because only the `mail.imap` tests (`MailFolderSyncTests`) call it. Run (A) covers it: 41/41 lines, 314/314 instructions.

Run (A), all 13 files the task changed (code the same as the final code; the test change is not included):

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/search/MailSearchController.kt` | 224/226 (99.1%) | 17/18 (94.4%) | 15/15 (100%) | 43/43 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/search/MailSearchService.kt` | 574/599 (95.8%) | 32/34 (94.1%) | 24/25 (96.0%) | 98/101 (97.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/search/MailSearchIndex.kt` | 314/314 (100%) | 28/28 (100%) | 12/12 (100%) | 41/41 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/search/MailSearchReconciler.kt` | 58/66 (87.9%) | 5/6 (83.3%) | 2/4 (50.0%) | 15/17 (88.2%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/search/domain/SearchIndexer.kt` | 66/75 (88.0%) | 4/4 (100%) | 5/6 (83.3%) | 12/13 (92.3%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt` | 1466/1528 (95.9%) | 128/142 (90.1%) | 42/42 (100%) | 197/197 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailContentLoader.kt` | 162/401 (40.4%) | 9/28 (32.1%) | 11/17 (64.7%) | 31/65 (47.7%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailMessageController.kt` | 978/1016 (96.3%) | 18/22 (81.8%) | 84/85 (98.8%) | 186/186 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailFolderService.kt` | 589/611 (96.4%) | 47/54 (87.0%) | 11/11 (100%) | 87/87 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/ImapRemoteMailbox.kt` | 584/2302 (25.4%) | 68/294 (23.1%) | 20/56 (35.7%) | 78/251 (31.1%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderStore.kt` | 786/799 (98.4%) | 50/54 (92.6%) | 26/26 (100%) | 133/133 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt` | 1193/1198 (99.6%) | 91/92 (98.9%) | 33/34 (97.1%) | 154/154 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/RemoteFolder.kt` | 282/291 (96.9%) | N/A (0 branches) | 40/43 (93.0%) | 43/43 (100%) |

Aggregate for (A) over all 13 files (summed counters): instructions 7276/9426 (77.2%), branches 497/776 (64.0%), methods 325/376 (86.4%), lines 1118/1331 (84.0%). All 13 files are in the report. The whole-file numbers for `ImapRemoteMailbox.kt` and `MailContentLoader.kt` are low mostly because of older IMAP code that only the GreenMail `integrationTest` runs, and that run is not measured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail/search/MailSearchService.kt:133,135` | `NotFoundException` when a folder is removed while the server search runs (AC1e, no 500 on a race) | Added `server search skips a folder removed from the cache while the search runs`; covered in (B) |
| `mail/search/MailSearchService.kt:78` (branch) | A query without words and without `server=true` returns `server: null` | Strengthened `a query without words finds nothing and does not ask the server`; covered in (B) |
| `mail/search/MailSearchService.kt:22-25` | Kotlin default-argument and `copy` code generated for `MailSearchScope` | Not material: generated code with no behavior |
| `mail/search/MailSearchService.kt:128` (branch) | The `truncated` OR short-circuits once it is already true | Not material: a plain boolean OR; truncation itself is covered |
| `mail/search/MailSearchController.kt:89` (branch) | One side of `toSummary`'s `seen` / `server?.let` branches | Not material: tests check both response shapes (`server` null and present) |
| `mail/search/MailSearchReconciler.kt:14,19` | `MailSearchReconcileConfiguration` bean (scheduling, on by default) | Gap left on purpose: tests turn it off (`cockpit.mail.search.reconcile.enabled=false`, assumption #4); `MailSearchReconcilerTests` tests `run()` directly |
| `mail/search/MailSearchReconciler.kt:46` (branch) | A log line written only when something changed | Not material: logging only |
| `mail/api/MailContentLoader.kt:83` | The body is indexed when it is fetched from the server and cached (AC1b) | `MailSearchGreenMailTests` covers it (run C, pass) but is not measured; `MailSearchIndexTests` covers the indexing logic |
| `mail/sync/ImapRemoteMailbox.kt:152-157,244-248,280,285` | Real IMAP `UID SEARCH [CHARSET UTF-8] TEXT ...` (AC1e) | `MailSearchGreenMailTests` covers it with ASCII and UTF-8 words (run C, pass) but is not measured |

## Unable to test

- Scheduling of the reconciler (the `@Scheduled` interval and `@ConditionalOnProperty`): not tested because it is deliberately off in tests. No follow-up needed.
- This helper could not run Gradle in its sandbox, so the parent worker ran every test command above and sent the results. I read the test-result and JaCoCo XML files of run (B) myself.

## Summary

Tests added and all validation passed. I added one test for the gap I found (a folder removed during the server search) and strengthened two weak tests. The search-package tests (36), the earlier mail suite (1145) and the GreenMail integration test (1) all pass. No production defects were found. `.taskcheck` is left to the parent.
