# Test Report

Date: 2026-10-20

Task: mail-imap - IMAP/SMTP connectivity and folder sync

Scope: second round, on the final code after the fixes for review findings CR-1..CR-5. Base 5b0dcae7b4f9e3235f258c58249633cae05a72c6
(given by the parent), HEAD 2f82ff2fcf35ae0065a278a61082ce9b68df5d83 on `main`; task commits `git log 5b0dcae7..HEAD`
(10 `mail-imap:` commits, 6245400f..2f82ff2f). The fix commits since the first test report are e2419dea, 7128f112 (and
2f82ff2f, regenerated client only). Production scope: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap`. Plus
uncommitted test changes of this run (snapshot below).

Requirements:
- `tasks/mail-imap.md` acceptance criteria and recorded assumptions
- `reviews/mail-imap/review.md` findings CR-1..CR-5 and `reviews/mail-imap/fixes.md` (the intended fixed behavior)
- SPEC.md §7.1/§7.2 (via the task), AGENTS.md project notes (secrets never logged, fictitious test data)
- docs/ARCHITECTURE.md "Coverage"

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/ImapConnectionPoolTests.kt` (5 tests added)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailFolderSyncTests.kt` (1 test added)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailAccountServiceTests.kt` (1 test added)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailSecretRedactionTests.kt` (1 test added)

Reviewed and kept unchanged: the fix tests added by the parent/fixer (`two parallel first syncs of one account both
succeed and store every folder once`, the two eviction sweep tests, the two CR-5 settings tests, the CR-3 field-name
assertion in `MailAccountWebTests`, and the CR-2 GreenMail test `a trusted certificate whose name does not match the
host is refused with TLS_FAILED`).

## Tests added or updated

Count: 8 (added in this run)

- `ImapConnectionPoolTests`
  - `an idle connection is not reused after any connection setting changed` (CR-5: port, security, username, password,
    OAuth connection each force a new connection and close the old one; the existing test only changed the host)
  - `the password is compared by value, so a reloaded account with the same password reuses the connection` (CR-5:
    two distinct `EncryptedSecret` instances with the same value must not force a reconnect)
  - `a connection a stale caller returns after the update is closed on the next borrow of the updated account` (CR-5
    race: the stale caller still holds its connection while the updated account borrows; the stale connection comes
    back tagged with its old settings and is closed instead of reused)
  - `the eviction sweep keeps an idle connection just younger than the idle timeout` (CR-4 boundary: idleTimeout - 1 ms)
  - `the eviction sweep does nothing for evicted accounts and an empty pool` (CR-4: no double close, no error)
- `MailFolderSyncTests`
  - `an account deleted while its folders are listed is not found and nothing is stored` (CR-1: the locked re-read of
    the account inside the store transaction; the account is deleted between LIST and store)
- `MailAccountServiceTests`
  - `the pool's idle eviction sweep is scheduled every minute` (CR-4: the `@Scheduled` sweep is actually registered in
    the application context as a 60 s fixed-delay task; the unit tests call `evictExpired()` directly)
- `MailSecretRedactionTests`
  - `the connection settings kept per pooled connection never show the password` (CR-5 added a data class that holds
    the password; its generated `toString` must stay redacted)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Account CRUD API `/api/v1/mail/accounts` | `MailAccountWebTests` (18), `MailAccountServiceTests` (15) | `cd server && ./gradlew check jacocoTestReport` | Pass |
| AC1 secrets write-only | `MailAccountWebTests` create/list/get tests, `MailSecretRedactionTests` (4, incl. new settings `toString` test), `MailSessionsTests` toString test | `./gradlew check jacocoTestReport` | Pass |
| AC1 'test connection' | `MailAccountServiceTests.test connection reports IMAP and SMTP independently`; `ImapGreenMailTests` connection tests | `./gradlew check jacocoTestReport`; `./gradlew integrationTest --tests '*Imap*'` | Pass |
| AC1 Angus sessions TLS/STARTTLS (incl. CR-2 host-name check) | `MailSessionsTests` property tests; `ImapGreenMailTests.implicit TLS works with a trusted certificate whose name matches`, `a trusted certificate whose name does not match the host is refused with TLS_FAILED`, `TLS against a plain port fails with TLS_FAILED`, `STARTTLS is required - a server without it is not used in plain text` | both commands above | Pass |
| AC1 XOAUTH2 for Google/MS | `MailSessionsTests$Credentials`; `ImapGreenMailTests` XOAUTH2 tests | both commands above | Pass (GreenMail only) |
| AC1 create validation field names (CR-3) | `MailAccountWebTests.IMAP_SMTP accounts need servers, username and password` (`imap`, `password`, `smtp`, `username`) | `./gradlew check jacocoTestReport` | Pass |
| AC2 pool per account with limits | `ImapConnectionPoolTests` (24); `ImapGreenMailTests` pool tests | both commands above | Pass |
| AC2 pool: outdated settings never reused (CR-5) | `ImapConnectionPoolTests`: `an idle connection opened with an account loaded before an update is closed instead of reused`, `an idle connection is reused by another instance of the account with the same settings`, + the 3 new CR-5 tests above | `./gradlew check jacocoTestReport` | Pass |
| AC2 pool: expired idle connections closed without a borrow (CR-4) | `ImapConnectionPoolTests` sweep tests (2 existing + 2 new), `MailAccountServiceTests.the pool's idle eviction sweep is scheduled every minute` | `./gradlew check jacocoTestReport` | Pass |
| AC2 folder list with SPECIAL-USE role detection, manual override | `FolderRoleDetectorTests` (15), `MailFolderSyncTests` (25), `MailAccountWebTests` folder tests, `ImapGreenMailTests` folder tests | both commands above | Pass |
| AC2 parallel syncs of one account (CR-1) | `MailFolderSyncTests.two parallel first syncs of one account both succeed and store every folder once`, `an account deleted while its folders are listed is not found and nothing is stored` | `./gradlew check jacocoTestReport` | Pass |
| AC3 `external` tests against GreenMail | `ImapGreenMailTests` (15, tagged `external`) | `cd server && ./gradlew integrationTest --tests '*Imap*'` | Pass (15/15, 0 skipped) |
| AC3 unit tests for role detection | `FolderRoleDetectorTests` (15) | `./gradlew check jacocoTestReport` | Pass |
| AC4 `OpenApiContractTest` passes without the flag | `OpenApiContractTest` (in the server `test` task) | `./gradlew check jacocoTestReport` (UPDATE_OPENAPI unset) | Pass. Regeneration with `UPDATE_OPENAPI=1` not re-run by me (rewrites a tracked file); commit 2f82ff2f did it |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.imap.*'`

Result:

Pass: 126 tests, 0 failures, 0 errors, 0 skipped (ImapConnectionPoolTests 24, MailFolderSyncTests 25,
MailAccountWebTests 18, MailAccountServiceTests 15, FolderRoleDetectorTests 15, MailSessionsTests 25,
MailSecretRedactionTests 4).

`cd server && ./gradlew integrationTest --tests '*Imap*'`

Result:

Pass: `ImapGreenMailTests` 15 tests, 0 failures, 0 errors, 0 skipped (2026-10-20T16:24:47Z-16:25:24Z).

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport` (ktlint main/test/scripts + all server unit tests)

Result:

Pass: BUILD SUCCESSFUL, 1524 tests, 0 failures, 0 errors, 0 skipped (2026-10-20T16:19:58Z-16:24:32Z). ktlint passed on the
new tests without formatting changes.

## .taskcheck

Result:

Pass: `sh scripts/check.sh` -> "== all checks passed" (tfp gate tests, server, web, android), run after the coverage
and integration runs on the same snapshot.

## Bugs found

None. No test exposed behavior contradicting the acceptance criteria, the SPEC, the recorded assumptions or the
intended CR-1..CR-5 fixes.

## Coverage

Status: verified (unit-test run; the GreenMail `integrationTest` run is not part of the JaCoCo report, see note)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured. All 7 scoped files are new in this task (added in 6245400f), so whole-file coverage
equals coverage of the task's lines, but no changed-line tool was run.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-imap; base
  5b0dcae7b4f9e3235f258c58249633cae05a72c6 (from the parent); HEAD 2f82ff2fcf35ae0065a278a61082ce9b68df5d83; commits
  `git log 5b0dcae7..HEAD`, all prefixed `mail-imap:`; files from `git diff --stat 5b0dcae7..HEAD -- server`.
- Source/test snapshot identifier (including uncommitted inputs): `sha256sum server/src/{main,test}/kotlin/ch/sonensei/cockpit/mail/imap/*.kt | sha256sum`
  = bba59eacfd2feb4c711fd35b591fe9e1ca2e5ed9257ee2716802a05ccfb666a3, identical before the coverage run and after the
  integration run. Uncommitted inputs: the 4 test files listed above (production code is unmodified at HEAD).
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/{FolderRoleDetector,ImapConnectionPool,MailAccountController,MailAccountService,MailFolderService,MailImapProperties,MailSessions}.kt`.
  Excluded: `api/openapi.yaml`, `web/src/app/api/generated/**` (generated), task/review files. `Comparisons.kt` appears in
  the JaCoCo package with no counters and is not a file of this task. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin), `./gradlew check jacocoTestReport`
  in `server/`, the full `test` task (tag `external` excluded by the build).
- Run start/completion time and exit status: 2026-10-20T16:19:58Z - 16:24:32Z, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256
  842ab63806f4a97b691bd3cf17d0d57575c346f7acd6f044409d907ceb1ef67e (written 18:24:31+02:00); `server/build/jacoco/test.exec`
  sha256 ab7442b39119d593387bf9044c9462251b119ec2ff622c433783cb099dd57cb5 (18:24:25+02:00).
- Evidence outputs are fresh and inputs stayed unchanged: yes; `test` re-executed (the test sources changed), report
  timestamps fall inside the run window, snapshot hash unchanged.
- Final run after latest test additions and production fixes: yes (covers CR-1..CR-5 fixes and all tests of this run).

Values below use covered/total (percentage); counters from the JaCoCo XML per-sourcefile counters. Statements = JaCoCo INSTRUCTION.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `FolderRoleDetector.kt` | 480/483 (99.4%) | 24/24 (100.0%) | 16/17 (94.1%) | 43/43 (100.0%) |
| `ImapConnectionPool.kt` | 670/688 (97.4%) | 36/36 (100.0%) | 30/36 (83.3%) | 111/111 (100.0%) |
| `MailAccountController.kt` | 900/1014 (88.8%) | 20/24 (83.3%) | 70/95 (73.7%) | 213/218 (97.7%) |
| `MailAccountService.kt` | 1086/1123 (96.7%) | 119/129 (92.2%) | 43/44 (97.7%) | 153/154 (99.4%) |
| `MailFolderService.kt` | 581/603 (96.4%) | 47/54 (87.0%) | 11/11 (100.0%) | 85/85 (100.0%) |
| `MailImapProperties.kt` | 68/68 (100.0%) | 4/4 (100.0%) | 10/10 (100.0%) | 14/14 (100.0%) |
| `MailSessions.kt` | 471/604 (78.0%) | 57/61 (93.4%) | 19/21 (90.5%) | 72/92 (78.3%) |
| **Total (summed counters, 7/7 files measured, none missing)** | 4256/4583 (92.9%) | 307/332 (92.5%) | 199/234 (85.0%) | 691/717 (96.4%) |

Note: `jacocoTestReport` reads only `test.exec`. The GreenMail suite (`integrationTest`, 15 pass) is not in these
numbers; the build has no coverage report for it and adding one is outside test-writer authority.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ImapConnectionPool.kt` 6 methods: `ImapConnectionSettings.get{Host,Port,Security,Username,Password,OauthConnectionId}` | Generated data-class getters; the settings are only compared via `equals` | Non-material. Every compared field is exercised by `an idle connection is not reused after any connection setting changed`; all lines and branches of the pool are covered |
| `MailSessions.kt:111-136` (`connectImap`, `checkSmtp`) | Real IMAP/SMTP login with TLS/STARTTLS/XOAUTH2 (AC1), TLS host-name refusal (CR-2) | Exercised by `ImapGreenMailTests` (15 pass) in `integrationTest`, not in the JaCoCo `test` report. A mocked-Angus unit test would only mock the behavior under test. Justified gap |
| `MailAccountController.kt:240` (`syncFolders` success mapping) | `POST /{id}/folders/sync` returns the folders | Covered by `ImapGreenMailTests.folder sync stores the folder list and detects roles` (integrationTest only) |
| `MailAccountController.kt:310,317,407,459`, branches 381/383/526/527 | Kotlin default-argument constructors, `!!`/`checkNotNull` null halves | Synthetic/defensive; not meaningful to test |
| `MailAccountService.kt:86` and branch halves at 83,124,143,144,167,172 | Unreachable `when` arm (only called for OAuth types), `?:`/`?.let` null halves | Non-material (unchanged since the first report) |
| `MailFolderService.kt` branch halves at 51 (`checkNotNull` on `TransactionTemplate.execute`), 72, 80, 122, 143-145 | Defensive null checks; name/delimiter change on the same path | Non-material. The CR-1 lock path (lines 53-54, found and not-found) is now fully covered |
| `FolderRoleDetector.kt` 1 method | Generated companion accessor | Non-material |

## Unable to test

- XOAUTH2 against real Google/Microsoft servers: not reachable here and would need real accounts; GreenMail's XOAUTH2
  support is used instead. Follow-up is the parent's call.
- STARTTLS success path: GreenMail offers no STARTTLS, so only "required and never silently plain" is proven by an
  integration test; the STARTTLS session properties are unit-tested.
- That the CR-1 row lock serializes syncs in PostgreSQL is shown by the parallel-sync test against the real database
  (Testcontainers via `PostgresTestBase`); its timing relies on a `CyclicBarrier`, not on sleeps.
- `UPDATE_OPENAPI=1` regeneration was not re-run (it rewrites the tracked `api/openapi.yaml`); the contract test without
  the flag passes.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
