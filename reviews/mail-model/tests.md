# Test Report

Date: 2026-10-20

Task: mail-model — Mail data model (accounts, identities, folders, cached headers and bodies, EMAIL resolver)

Scope: base `0d8a31f3ded6b972b403bf664177f5c7e7f31266` .. HEAD `dc391ff64a686a636bbf71743cca157162f86150` on branch `main`. The parent supplied the task commits: `bbcfc97` (implementation), `4445a4b` (tests and the first report), `7621d6e` (fix for TW-1 and TW-2), `dc391ff` (fixes.md). `git diff --stat 0d8a31f..HEAD` contains only task files: 11 `.kt` files in `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/`, the migration `server/src/main/resources/db/migration/mail/V202610201402__mail_model.sql`, the mail model tests and `reviews/mail-model/`. The uncommitted changes are only the two test files listed below. This report replaces the earlier one, which described the code before the fix (`927e3c6`, base `8507047`, before a rebase).

Requirements:
- `tasks/mail-model.md`: the acceptance criteria and the five assumptions in its Notes
- `reviews/mail-model/fixes.md` (TW-1 and TW-2 fixes, to check them again)
- SPEC.md §4.3 and §7.1–7.2, as cited by the task and in the production KDoc
- docs/ARCHITECTURE.md ("Coverage"), docs/QUALITY.md, AGENTS.md

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/model/MailItemResolverTests.kt` (1 test added)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/model/MailAddressTests.kt` (1 test added)

No existing test was changed, weakened or disabled. No production code was modified.

## Tests added or updated

Count: 2 added. The package now has 70 tests: 68 at `dc391ff`, plus these 2.

- `MailItemResolverTests > stale UIDs left by deleted folders are never used as the current copy`: this is a regression test for the TW-1 fix in `MailMessageLocator`. Two rows with the same Message-ID lose their folders, and both keep a stale UID. The link must not resolve, so `resolve` is null and `exists` is false. Once a located copy appears, the link resolves to that copy. Before the fix, the locator query had no `folder_id is not null` filter. Both stale rows then matched, and their `null == null` folder matched the "same folder" preference, so the link resolved to a stale row.
- `MailAddressTests > the mailbox shape is checked per group member and allows an at sign in a quoted local part`: this test covers the TW-2 fix. Malformed members inside a group are dropped and a valid member is kept. A quoted local part that contains `@` is kept, because the check uses the last `@`.

## Requirement coverage

Command F = `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.mail.model.*'` (Postgres via Testcontainers). Final result: 70 tests, 0 failures, 0 errors, 0 skipped.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 mail_account: type IMAP_SMTP/GOOGLE/MICROSOFT, hosts/ports/TLS, encrypted password or OAuth connection, signature, notify flag | `MailModelPersistenceTests` round-trips; `MailModelConstraintTests`: ports range, sync window, password/OAuth by type, enum checks, ordering | F | Pass |
| AC1 identity (unique email per account regardless of case, one default, reply-to/signature override) | `MailModelPersistenceTests` identity tests; `MailModelConstraintTests`: per-account uniqueness, several non-default identities, override round-trip | F | Pass |
| AC1 mail_folder (path, role, uidvalidity, uidnext, highestmodseq) | `MailModelPersistenceTests` folder test; `MailModelConstraintTests`: roles, role check, folder flags | F | Pass |
| AC1 mail_message (account, folder, uid, Message-ID, headers, flags, size, hasAttachments, snippet, thread key) | `MailModelPersistenceTests` round-trip; `MailModelConstraintTests`: non-positive UID or negative size rejected (also with `folder_id` null), same Message-ID in several folders, thread query; `MailMessageLocationTests` (6); `MailAddressTests` (14, incl. TW-2 cases and the new group/quoted-local-part test) | F | Pass |
| AC1 cascades: deleting an account removes everything; deleting a folder keeps its messages as link targets (TW-1) | `deleting an account removes its folders, located messages and identities`; `deleting a folder keeps its messages as link targets`; `stale UIDs of a deleted folder do not collide and the row can be located again`; persistence round-trip that deletes a folder holding a located message | F | Pass |
| AC1 body cache with size accounting (UTF-8 bytes, LRU eviction down to the budget, the body just stored is kept) | `MailBodySizeTests` (2), `MailBodyCacheTests` (13) | F | Pass |
| AC2 EMAIL resolver: re-resolve by Message-ID when the UID changed | `MailItemResolverTests` (11), incl. `a located row whose folder was deleted re-resolves to a copy in another folder` and the new `stale UIDs left by deleted folders are never used as the current copy` | F | Pass |
| AC3 `./gradlew test --tests 'ch.sonensei.cockpit.mail.model.*'` passes | whole package (70 tests) | F | Pass |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.mail.model.*'`

Result:

Pass: BUILD SUCCESSFUL (2026-10-20, about 16:36 to 16:37 +02:00). ktlint passed on the main, test and script source sets. 70 tests, 0 failures.

An earlier run of the same command (about 16:35) failed 1 of 70. The cause was an assertion in my new `MailAddressTests` test: I had expected `"ann@example.com" <>` to give no addresses. The lenient Jakarta parser reads the quoted phrase as the address in that case, so the entry has the `local@domain` shape. No requirement defines this input, and the KDoc judges entries by the address the parser read, so my test was wrong and this is not a production defect. I removed that assertion and kept the rest of the test.

## Full test execution

Command:

`cd server && ./gradlew --quiet check`

Result:

Pass: exit 0 (2026-10-20 16:38:23 to 16:42:12 +02:00). The `build/test-results/test/` XMLs from this run report 1131 tests, 0 failures, 0 errors, 0 skipped. Web and Android were not run because this task changes only the server.

## .taskcheck

Result:

Not run. The parent instructed this, because `.taskcheck` is the parent worker's step.

## Bugs found

None in this revision. TW-1 and TW-2 from the earlier report are fixed in `7621d6e`. Both fixes were checked again with the tests above, including the two new regression tests.

## Coverage

Status: verified (focused run, exit 0, fresh report).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Every scoped Kotlin file is new in this task (added between base and HEAD), so whole-file coverage equals changed-line coverage for these files.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-model`; base `0d8a31f3ded6b972b403bf664177f5c7e7f31266`; HEAD `dc391ff64a686a636bbf71743cca157162f86150`; task commits `bbcfc97`, `4445a4b`, `7621d6e`, `dc391ff` (supplied by the parent); file scope from `git diff --stat 0d8a31f..HEAD`, which agrees with the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): production at HEAD, unchanged. File sha256 values for the uncommitted tests: `MailAddressTests.kt` `7b075590a66b085640ec378b322bddb6be431aaf630e653095e748ecfea0fc37`, `MailItemResolverTests.kt` `7c2ac15c2de7a1c27bda76b689959b4dec4a1e2b5fc3fafb77afe26be9a8cefe`. Every other mail model test and production file matches HEAD (`git status --short` shows only these two files). Hashes were taken before and after the measured run and are identical. Production file hashes are unchanged too, e.g. `MailMessage.kt` `d4be96ba…`, `MailMessageLocator.kt` `66736974…`, migration `252bbfa0…`.
- Exact included files: the 11 `.kt` files of `ch/sonensei/cockpit/mail/model` in the table below. Excluded: `V202610201402__mail_model.sql`, because JaCoCo cannot instrument SQL; its constraints and cascades are exercised by `MailModelConstraintTests` and `MailModelPersistenceTests`. `MailRepositories.kt` holds only Spring Data interfaces with no counters, so its metrics are N/A; its queries are exercised by the DB tests. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (project default version). Command `./gradlew test --rerun --tests 'ch.sonensei.cockpit.mail.model.*' jacocoTestReport --quiet`, working directory `server/`. `--rerun` forces a fresh test execution.
- Run start/completion time and exit status: 2026-10-20 16:37:08 to 16:37:59 +02:00, exit 0. Test result timestamps are 14:37:15Z to 14:37:55Z; the run had 70 tests, 0 failures.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 `a39523250e815675c5ee2f56d94698119bc50a1e09a016df46dae0f2b43e05f1` (read right after the run; per-file counters were extracted with a scratch script, `.tmp/jacoco_scope.py`).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The later `./gradlew check` run may have rewritten the JaCoCo data with full-suite hits. The numbers below come from the focused run, with the hash above.
- Final run after latest test additions and production fixes: yes. It includes the fix commit `7621d6e` and both new tests.

Values below use covered/total (percentage); zero-denominator metrics are N/A. "Statements" are JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailAccount.kt` | 123/174 (70.7%) | 2/2 (100.0%) | 19/33 (57.6%) | 26/31 (83.9%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailAddress.kt` | 209/217 (96.3%) | 32/36 (88.9%) | 12/12 (100.0%) | 15/15 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailBody.kt` | 102/109 (93.6%) | 4/4 (100.0%) | 13/15 (86.7%) | 20/21 (95.2%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailBodyCache.kt` | 97/97 (100.0%) | 6/6 (100.0%) | 7/7 (100.0%) | 16/16 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailEnums.kt` | 93/93 (100.0%) | N/A | 3/3 (100.0%) | 12/12 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailFolder.kt` | 96/107 (89.7%) | N/A | 21/24 (87.5%) | 19/19 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailIdentity.kt` | 68/76 (89.5%) | N/A | 14/16 (87.5%) | 15/15 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailItemResolver.kt` | 88/88 (100.0%) | 16/16 (100.0%) | 3/3 (100.0%) | 12/12 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailMessage.kt` | 246/272 (90.4%) | 12/12 (100.0%) | 46/54 (85.2%) | 45/45 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailMessageLocator.kt` | 57/57 (100.0%) | 8/8 (100.0%) | 2/2 (100.0%) | 8/8 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailRepositories.kt` | N/A | N/A | N/A | N/A |
| **Aggregate (summed counters, 10 measured files)** | 1179/1290 (91.4%) | 80/84 (95.2%) | 140/169 (82.8%) | 188/194 (96.9%) |

The aggregate excludes the migration SQL (not instrumentable) and `MailRepositories.kt` (no counters). No scoped file is missing from the report.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailAccount.kt:25,27,29,31,34,36,38,41,44` (setters of the constructor `var` properties) | Plain JPA property setters. Changing an account is account-API behavior, not part of this task | Justified gap: getters and persistence of every column are covered by round-trip tests |
| `MailAddress.kt:27` (`mailbox.address == null` branch), `:38` (`getGroup(false) == null` branch) | Defensive null handling of Jakarta Mail results | Justified gap: `parseHeader` does not produce these. Malformed, blank and group cases are tested |
| `MailBody.kt:19,23`, `MailFolder.kt:24,26,31`, `MailIdentity.kt:19,21`, `MailMessage.kt:52,56,79,83,87,91,119` (unused setters/getters and synthetic members) | Accessors, no behavior | Justified gap |
| Migration `mail_message_uid` check and `folder_id on delete set null` (TW-1 fix) | Link survival on folder deletion | Covered behaviorally (constraint, persistence and resolver tests); SQL itself is not instrumentable |

## Unable to test

- Concurrency of the LRU eviction itself, meaning two `put`s of different messages that evict at the same time. Concurrent upserts of the same message are tested and pass. Testing eviction under contention deterministically would need lock-level control that the current test infrastructure does not offer. Low risk; no follow-up needed.
- The 2 GiB default budget (`DEFAULT_MAX_BYTES`) under real data volumes. Only a small configured budget and the bean construction are exercised. No follow-up needed for this task.
- Backup/restore round-trip and search integration of mail entities (AGENTS.md convention, SPEC.md §14) are not in this task's acceptance criteria, so they were not tested here.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.

The TW-1 and TW-2 fixes in `7621d6e` hold. Two regression tests were added. AC3's command passes (70/70), and the full server `check` passes (1131 tests).
