# Test Report

Date: 2026-10-20

Task: mail-imap - IMAP/SMTP connectivity and folder sync

Scope: task commits d44ef8c, d270bd7, fd1dbad, f64096d (the 4 `mail-imap:` commits on top of
768d912c5e41dc9fc40fa71cf537212855a9f8b0 = `git merge-base HEAD origin/main` after the rebase; original base
2b4cd20bd437e180065cdc600de408452acd2401). HEAD f64096deeaee7985bf771ecb417cebba3a7588a1 on `main`, plus
uncommitted test files (see snapshot below). Production scope: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap`.

Requirements:
- `tasks/mail-imap.md` acceptance criteria and recorded assumptions
- SPEC.md §7.1/§7.2, §4.2 (via the task), AGENTS.md project notes (secrets never logged, fictitious test data)
- docs/ARCHITECTURE.md "Coverage"

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/ImapConnectionPoolTests.kt` (new, left by the previous test-writer run; reviewed, kept unchanged)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailSessionsTests.kt` (new, previous run; reviewed, kept unchanged)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailSecretRedactionTests.kt` (new, previous run; reviewed, kept unchanged)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailAccountServiceTests.kt` (new, previous run; 1 test added)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailFolderSyncTests.kt` (new, previous run; 4 tests added)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/imap/MailAccountWebTests.kt` (committed by the task; 5 tests added)

## Tests added or updated

Count: 85 (relative to HEAD: 80 in the five new files, 5 added to `MailAccountWebTests`; 10 of them written in this run)

Added in this run:
- `MailAccountWebTests`: `syncWindowMonths is accepted from 1 to 600 and rejected outside`, `a display name is limited to 200 characters`,
  `an unknown account answers 404 on every account endpoint`, `a stale folder ETag answers 412 and a missing one 428`,
  `the account API requires login`
- `MailFolderSyncTests`: `a vanished folder is removed but its cached messages stay without a folder`,
  `an unknown account is not found and the server is not contacted`,
  `an override of an unknown folder or a folder of another account is not found`,
  `a flat namespace without delimiter is stored with depth 0 and no delimiter`
- `MailAccountServiceTests`: `changing only the SMTP server is stored but keeps the pooled IMAP connections`

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Account CRUD API `/api/v1/mail/accounts` | `MailAccountWebTests` (create/list/get/patch/delete, If-Match 428/412, validation, 404s, login required), `MailAccountServiceTests` (events, OAuth connection switch/reject, defaults) | `./gradlew check` | Pass |
| AC1 secrets write-only | `MailAccountWebTests.create returns the account without the password and stores it encrypted`, `list and get never contain the password`; `MailSecretRedactionTests` (toString, Problem Details, INFO logs); `MailSessionsTests.toString never shows the secret` | `./gradlew check` | Pass |
| AC1 'test connection' | `MailAccountServiceTests.test connection reports IMAP and SMTP independently`; `ImapGreenMailTests.test connection logs in to plain IMAP and SMTP`, `a wrong password fails both logins with AUTHENTICATION_FAILED`, `an unreachable server fails with CONNECTION_FAILED` | `./gradlew check`; `./gradlew integrationTest --tests '*Imap*'` | Pass |
| AC1 Angus sessions TLS/STARTTLS | `MailSessionsTests$ImapProperties`/`$SmtpProperties` (property sets per mode, host-name check, trust list); `ImapGreenMailTests.implicit TLS works with a trusted certificate whose name matches`, `TLS against a plain port fails with TLS_FAILED`, `STARTTLS is required - a server without it is not used in plain text` | both commands above | Pass |
| AC1 XOAUTH2 for Google/MS | `MailSessionsTests$Credentials` (token of the connection, MAIL resource, failure classification), `MailSessionsTests.XOAUTH2 restricts the login mechanisms`; `ImapGreenMailTests.a Google account logs in with XOAUTH2 using the connection's access token`, `an OAuth connection without usable token reports OAUTH_RECONNECT_REQUIRED` | both commands above | Pass (against GreenMail; real Google/Microsoft servers not contacted) |
| AC2 connection pool per account with limits | `ImapConnectionPoolTests` (15: reuse, idle timeout boundary, dropped connections, borrow timeout -> POOL_EXHAUSTED, per-account limits, eviction on change/OAuth disconnect, shutdown); `MailSecretRedactionTests.an exhausted pool answers 503`; `ImapGreenMailTests.the pool reuses connections and never opens more than the limit`, `changing the credentials closes the pooled connections`, `a failed operation does not return its connection to the pool` | both commands above | Pass |
| AC2 folder list with SPECIAL-USE role detection | `FolderRoleDetectorTests` (15), `MailFolderSyncTests` (23: role moves under the unique index, swaps, vanished folders, INBOX added, `\Noselect`, events, failures leave cache unchanged); `ImapGreenMailTests.folder sync stores the folder list and detects roles` | both commands above | Pass |
| AC2 manual override | `FolderRoleDetectorTests` override tests, `MailFolderSyncTests` (manual role/no role survives sync, `automatic` hands back, events, stale ETag), `MailAccountWebTests.folders are listed by path and a role can be set by hand`, `a stale folder ETag answers 412 and a missing one 428`; `ImapGreenMailTests.a manual role survives the next sync and vanished folders are removed` | both commands above | Pass |
| AC3 `external` tests against GreenMail | `ImapGreenMailTests` (14 tests, tagged `external`) | `cd server && ./gradlew integrationTest --tests '*Imap*'` | Pass (14/14) |
| AC3 unit tests for role detection | `FolderRoleDetectorTests` (15) | `./gradlew check` | Pass |
| AC4 `OpenApiContractTest` passes without the flag | `OpenApiContractTest` (part of the server `test` task) | `cd server && ./gradlew check` (UPDATE_OPENAPI unset) | Pass. Regeneration with `UPDATE_OPENAPI=1` was not re-run by me (it rewrites a tracked file); the parent's commit f64096d did it |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.imap.*'`

Result:

Pass: 112 tests, 0 failures, 0 errors, 0 skipped (before the final SMTP test was added; that test then ran in the full run below).

`cd server && ./gradlew integrationTest --tests '*Imap*'`

Result:

Pass: `ImapGreenMailTests` 14 tests, 0 failures, 0 errors, 0 skipped (2026-10-20T15:38Z).

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport` (ktlint main/test/scripts + all server unit tests)

Result:

Pass: BUILD SUCCESSFUL, 1360 tests, 0 failures, 0 errors, 0 skipped; mail.imap suites 113 tests (2026-10-20T15:43:39Z-15:47:39Z).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` -> "== all checks passed" (server, web, android; 2026-10-20T15:47:52Z-15:49:02Z, ~70 s).

## Bugs found

None. No test exposed behavior contradicting the acceptance criteria, SPEC or recorded assumptions.

## Coverage

Status: verified (unit-test run only; see note on GreenMail below)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (all scoped files are new in this task, so whole-file equals changed-file coverage, but no changed-line tool was run).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-imap; original base 2b4cd20bd437e180065cdc600de408452acd2401, current base after rebase 768d912c5e41dc9fc40fa71cf537212855a9f8b0; HEAD f64096deeaee7985bf771ecb417cebba3a7588a1; commits d44ef8c, d270bd7, fd1dbad, f64096d given by the parent; files from `git diff --name-only 768d912..HEAD -- server/src/main web/src android/app/src/main` minus generated web client.
- Source/test snapshot identifier (including uncommitted inputs): `sha256sum server/src/{main,test}/kotlin/ch/sonensei/cockpit/mail/imap/*.kt | sha256sum` = d8d5f6dce03b1b9973e5559b6e05c08cb82e93cf16e9a42bf61af32fd86b42b5, identical before and after the final run.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/{FolderRoleDetector,ImapConnectionPool,MailAccountController,MailAccountService,MailFolderService,MailImapProperties,MailSessions}.kt`. Excluded: `api/openapi.yaml` and `web/src/app/api/generated/**` (generated), task files. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin), `./gradlew check jacocoTestReport` in `server/`, the full `test` task (tag `external` excluded by build config).
- Run start/completion time and exit status: 2026-10-20T15:43:39Z - 15:47:39Z, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 9646284644e3df21e73ae6a787fb3eab49e12c2c633de3cb5ed72f97e959b0bb (written 17:47:39+02:00); `server/build/jacoco/test.exec` sha256 b298a16d653e1c30855e9a535343ceec4c6f136ccfc7865da195f316c626b00b (17:47:35+02:00).
- Evidence outputs are fresh and inputs stayed unchanged: yes, `test` re-executed (not UP-TO-DATE), exec/report timestamps inside the run window, snapshot hash unchanged.
- Final run after latest test additions and production fixes: yes (no production changes in this run).

Values below use covered/total (percentage); counters from the JaCoCo XML per-sourcefile counter elements. Statements = JaCoCo INSTRUCTION.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `FolderRoleDetector.kt` | 480/483 (99.4%) | 24/24 (100.0%) | 16/17 (94.1%) | 43/43 (100.0%) |
| `ImapConnectionPool.kt` | 478/478 (100.0%) | 28/28 (100.0%) | 23/23 (100.0%) | 82/82 (100.0%) |
| `MailAccountController.kt` | 900/1014 (88.8%) | 20/24 (83.3%) | 70/95 (73.7%) | 213/218 (97.7%) |
| `MailAccountService.kt` | 1119/1156 (96.8%) | 119/129 (92.2%) | 43/44 (97.7%) | 160/161 (99.4%) |
| `MailFolderService.kt` | 560/582 (96.2%) | 45/52 (86.5%) | 11/11 (100.0%) | 80/80 (100.0%) |
| `MailImapProperties.kt` | 68/68 (100.0%) | 4/4 (100.0%) | 10/10 (100.0%) | 14/14 (100.0%) |
| `MailSessions.kt` | 471/604 (78.0%) | 57/61 (93.4%) | 19/21 (90.5%) | 72/92 (78.3%) |
| **Total (summed counters, 7/7 files measured, none missing)** | 4076/4385 (93.0%) | 297/322 (92.2%) | 192/221 (86.9%) | 664/690 (96.2%) |

Note: `jacocoTestReport` reads only `test.exec`. The GreenMail suite (`integrationTest`) is not in these numbers; the build has no report for it and changing that is outside test-writer authority.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailSessions.kt:111-136` (`connectImap`, `checkSmtp`) | Real IMAP/SMTP login with TLS/STARTTLS/XOAUTH2 (AC1) | Exercised by `ImapGreenMailTests` (14 pass) in `integrationTest`, which JaCoCo's `test` report does not include. No unit test with mocked Angus `Session`, which would only mock the behavior under test. Justified gap |
| `MailAccountController.kt:240` (`syncFolders` success mapping) | `POST /{id}/folders/sync` returns the folders | Covered by `ImapGreenMailTests.folder sync stores the folder list and detects roles` (integrationTest only); 404 path added to unit suite (`an unknown account answers 404 on every account endpoint`) |
| `MailAccountController.kt:310,317,407,459`, `381/383/526/527` branches | Kotlin default-argument constructors, `!!`/`checkNotNull` null branches | Synthetic/defensive code; not meaningful to test |
| `MailAccountService.kt:86` (`ProviderServers.provider(IMAP_SMTP)` -> null) and residual branch halves at 83,124,143,144,167,172 | Unreachable `when` arm (only called for OAuth types) and `?:`/`?.let` null halves | Non-material. The meaningful gap here (SMTP server update, lines 179-182, blank display name) was closed with `changing only the SMTP server is stored but keeps the pooled IMAP connections` |
| `MailFolderService.kt:46,61,69` (`checkNotNull` on `TransactionTemplate.execute`), `111` (name/delimiter-change halves), `132-134` | Defensive null checks; a server renaming a folder's last segment or delimiter on the same path | Non-material: the update branch itself is covered (`a changed Noselect flag updates the folder`); same code path |
| `FolderRoleDetector.kt` 1 method | Generated companion/`private companion object` accessor | Non-material |

## Unable to test

- XOAUTH2 against real Google/Microsoft servers: not reachable here and would need real accounts; GreenMail's XOAUTH2 support is used instead (AC1 is verified against GreenMail only). Follow-up is the parent's call.
- STARTTLS success path: GreenMail offers no STARTTLS, so only "required and never silently plain" is proven by integration test; the STARTTLS session properties are unit-tested.
- `UPDATE_OPENAPI=1` regeneration was not re-run (it rewrites the tracked `api/openapi.yaml`); the contract test without the flag passes.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
