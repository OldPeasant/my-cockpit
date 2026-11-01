# Test Report

Date: 2026-11-01

Task: mail-folder-manage-api - Mail folders: create, rename and delete (API)

Scope: `git diff 9f4707b487e5951c7af27d09bb4fdf4b293fc730..d0d9f495abffa4dea24f74f10681e4bc54a30d91` on `main`. The task commits are eda80e264 (API), 00f6fbf23 (GreenMail tests, regenerated API and web client) and d0d9f495a (CR-1 per-account lock, CR-2 explicit `{"name": null}`). The parent named them 5f24de36c, 08938b7eb and 4f869fc04. Those are earlier hashes of the same three commits (same messages, same order on top of the base). Uncommitted inputs: only the two test files listed below.

Requirements:
- `tasks/mail-folder-manage-api.md` acceptance criteria and the recorded assumptions #1-#6 (`python tasks.py assumptions`)
- `reviews/mail-folder-manage-api/review.md` (CR-1, CR-2)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailFolderManageTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailFolderManageWebTests.kt`

## Tests added or updated

Count: 5 (4 in MailFolderManageTests, 1 in MailFolderManageWebTests; no existing test changed)

- `a sync started while a RENAME runs waits for the rename and then keeps the folder and subfolder ids` (CR-1 regression). RENAME is held on a latch after the server already shows the new name. A sync started then must stay blocked (thread parked, future not done, `LIST` never called). Once released, the sync returns `Work`/`Work/Sub` with the original folder ids and nothing is recreated. Without the lock the sync would list `Work`, drop `Projects` (detaching its messages), recreate it with new ids and break the rename's cache step, and the test fails on `LIST` having been called.
- `a rename requested while a sync lists the server waits until the sync has stored its result` (CR-1, other direction). `LIST` is held on a latch. A rename started then must not issue RENAME until the sync has stored. Afterwards the ids are kept and the paths moved.
- `a RENAME in progress does not block a sync of another account`: the lock is per account, not global.
- `rename refuses blank names and the delimiter with 400 before contacting the server`: closes the unit-suite gap at `MailFolderService.kt:236-237`.
- `an explicit null name is 400 on name and neither renames nor changes the role` (CR-2): `{"name": null}`, `{"name": null, "role": "ARCHIVE"}` and `{"name": null, "automatic": true}` each return 400 Problem JSON with `errors[0].field == "name"`, and neither `rename` nor `overrideRole` is called.

The concurrency tests set the order with `CountDownLatch`es and bounded waits (10 s), with no sleeps. The only polling is a bounded spin until the waiting thread is parked, and the latches and futures carry the real assertions.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 create (CREATE+SUBSCRIBE, parent/top level, delimiter, cached, 201+ETag) | MailFolderManageTests create cases; MailFolderManageWebTests `create answers 201 ...`; GreenMail `create makes ...`, `create nests ...` | `cd server && ./gradlew test jacocoTestReport --rerun` (unit); GreenMail run by parent | Pass (unit, this run); GreenMail: Pass, run by parent, not re-run by me |
| AC2 rename (subfolders moved, ids kept, name-only PATCH, role untouched) | MailFolderManageTests rename cases; Web `a body with only a name ...`, `a name with a role ...`; GreenMail `rename moves ... keeps all ids` | same | Pass |
| CR-1: sync/rename serialized per account, folder ids survive a concurrent sync | `a sync started while a RENAME runs ...`, `a rename requested while a sync lists ...`, `a RENAME in progress does not block a sync of another account` | same; also 3x `./gradlew test --rerun --tests '*MailFolderManageTests' --tests '*MailFolderManageWebTests'` | Pass (stable over 4 runs) |
| CR-2: PATCH `{"name": null}` -> 400 on `name`, no rename/role change | `an explicit null name is 400 on name ...` | same | Pass |
| AC3 delete (recursive, messages detached, INBOX/role/holding-role -> 409, INBOX not renamable) | MailFolderManageTests delete cases; GreenMail delete cases | same | Pass |
| AC4 errors: 400 blank/delimiter | MailFolderManageTests create/rename 400 cases (incl. the new rename test); Web Bean Validation case | same | Pass |
| AC4 errors: 409 taken, 404, 412/428 | MailFolderManageTests; GreenMail `rename refuses ...`, `delete refuses ...`, `create refuses ...` | unit as above; GreenMail by parent | Pass |
| AC4 errors: 502 `errorCode` (SERVER_REJECTED, CONNECTION_FAILED) | Web `a server refusal answers 502 ...`; MailFolderManageTests SERVER_REJECTED cases; GreenMail `an unreachable server ...` | same | Pass |
| AC4 folder change event per change | MailFolderManageTests `folderEvents()` assertions; GreenMail event assertions | same | Pass |
| AC5 openapi + web client regenerated | Not covered by these tests (generated artifacts); checked by the parent / code-reviewer | n/a | Not testable here |
| AC5 GreenMail suite and `./gradlew check` pass | MailFolderManageGreenMailTests (13) | `cd server && ./gradlew integrationTest --tests '*Folder*GreenMail*'` | Not run by me: run by the parent before this pass. My changes do not touch that file. `./gradlew check`: Pass (see .taskcheck) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*MailFolderManageTests' --tests '*MailFolderManageWebTests' --tests '*MailFolderSyncTests' --tests '*FolderRoleDetectorTests'`, then three more runs of `./gradlew test --rerun --tests '*MailFolderManageTests' --tests '*MailFolderManageWebTests'` to check for flakiness, then a final focused run with `ktlintCheck` after the last test was added.

Result:

Pass. MailFolderManageTests 23/23, MailFolderManageWebTests 11/11, MailFolderSyncTests 25/25, FolderRoleDetectorTests 15/15, 0 skipped. ktlintCheck clean.

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport --rerun`

Result:

Pass. 5184 tests, 0 failures, 0 errors, 0 skipped (full server unit suite, BUILD SUCCESSFUL in 8m 45s). I did not run the web or Android suites: only server test files changed.

## .taskcheck

Result:

Pass for the server part: `sh scripts/check.sh server` printed "all checks passed (server)". I did not run the tfp, web and android parts, because my changes do not affect them. The parent runs the full `.taskcheck` before `done`.

## Bugs found

None. No production defect was found. All new tests pass against d0d9f495a.

## Coverage

Status: verified (server unit suite). Web generated client files are excluded (see below). The GreenMail integration suite is not part of the JaCoCo `test` report.

Metric: whole-file coverage of task-changed production files. This includes old code in those files and hits from tests that existed before the task.
Changed-line coverage: not measured (the tooling has no changed-line report).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-folder-manage-api; base 9f4707b487e5951c7af27d09bb4fdf4b293fc730; HEAD d0d9f495abffa4dea24f74f10681e4bc54a30d91; commits eda80e264, 00f6fbf23, d0d9f495a (parent-supplied commits, rewritten hashes, matching messages); file scope from `git diff --name-only 9f4707b48..HEAD -- server/src/main web/src android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD d0d9f495a plus the uncommitted test files `MailFolderManageTests.kt` sha256 77d4cb86f3318c0b8854129ff665e288f32d926553d4c509d8d84a99033a7fae and `MailFolderManageWebTests.kt` sha256 b26a708f45ded327e5abc596c5cae72365f6c903d3b7846f9aca725898312ca6. The hashes were identical before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailFolderService.kt`, `.../MailAccountController.kt`, `.../MailSessions.kt`. Excluded: `web/src/app/api/generated/**` (9 files, generated by ng-openapi-gen with no hand-written behavior), `api/openapi.yaml` (generated contract), tests, `tasks/`, `reviews/`. No renamed or deleted production files.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin, project-configured); `./gradlew test jacocoTestReport --rerun` in `server/`; full `test` task, which excludes `integrationTest`.
- Run start/completion time and exit status: started after 2026-11-01T06:43:16+01:00, report written 2026-11-01T06:52:04+01:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 55934d7d33847d7df41b76de3211c64910c98a7e1293cf6256c8ff3e5c3b0ba8.
- Evidence outputs are fresh and inputs stayed unchanged: yes. `--rerun` forced the test task, the report mtime falls inside the run, and the test-file hashes and HEAD were unchanged.
- Final run after latest test additions and production fixes: yes (after the last test was added; no production change during this pass).

Values below use covered/total (percentage).

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/.../mail/imap/MailFolderService.kt` | 1679/1730 (97.1%) | 158/173 (91.3%) | 51/51 (100.0%) | 227/227 (100.0%) |
| `server/.../mail/imap/MailAccountController.kt` | 983/1109 (88.6%) | 33/38 (86.8%) | 80/106 (75.5%) | 230/236 (97.5%) |
| `server/.../mail/imap/MailSessions.kt` | 478/611 (78.2%) | 57/61 (93.4%) | 19/21 (90.5%) | 73/93 (78.5%) |
| Aggregate (summed counters, 3 files; 9 generated web files excluded) | 3140/3450 (91.0%) | 248/272 (91.2%) | 150/178 (84.3%) | 530/556 (95.3%) |

JaCoCo reports instructions rather than statements.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailFolderService.kt:236-237` (before the last test was added) | rename with the delimiter -> 400 | Added `rename refuses blank names and the delimiter with 400 ...`; now covered |
| `MailFolderService.kt:84,115,182,267` (`checkNotNull(transactions.execute …)` null branch) | none: TransactionTemplate returns the non-null block result | Unreachable; no test |
| `MailFolderService.kt:270` (renamed folder missing from the cache after RENAME) | a concurrent uncache from a second server instance | With the per-account lock this is unreachable in one process (assumption #5). Left as a defensive path |
| `MailFolderService.kt:201` (create: folder exists only on the server), `:310` (delete of a folder gone on the server) | AC1 409 / assumption #3 (204) | Covered by GreenMail `create of a folder that exists only on the server is a conflict` and `delete of a folder already gone on the server only uncaches it` (integrationTest, outside this report) |
| `MailFolderService.kt:122,302,354,357,431,452-454` (single partial branches) | role and inbox detection combinations, sync field-change checks from before the task | Each line is otherwise covered; the missing branch combinations are pre-existing sync behavior or redundant `||` operands. Not material |
| `MailAccountController.kt:304` (`checkNotNull(folder)` null branch) | none: either rename or role change always sets `folder` | Unreachable |
| `MailAccountController.kt:238,407-480,504,556-611,662-663` | `syncFolders` endpoint and request/response DTO getters from before the task | Outside the task's changes (exercised by other suites or integration tests) |
| `MailSessions.kt:112-137` (`connectImap`, `checkSmtp`) | real IMAP/SMTP connections, from before the task | The task added only the `SERVER_REJECTED` enum constant, which is covered. These methods are exercised by GreenMail integration tests |

## Unable to test

- The GreenMail integration suite was not re-run in this pass. The parent ran it (13 tests) on the same production code, and this pass did not change it. Follow-up: none, unless production code changes again.
- `api/openapi.yaml` and web client regeneration: generated artifacts with no behavior to unit-test. The code-reviewer or the parent checks them against `npm run api:gen`.
- Multi-instance serialization: the lock is in-process by design (assumption #5), so it cannot be tested beyond one JVM.

## Summary

Tests added and all validation passed. The new tests cover CR-1 (sync and rename serialized per account, in both directions, with folder ids kept) and CR-2 (explicit `{"name": null}` -> 400 on `name`, with no rename or role change). No production defects were found.
