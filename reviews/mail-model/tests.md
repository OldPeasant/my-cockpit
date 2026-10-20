# Test Report

Date: 2026-10-20

Task: mail-model — Mail data model (accounts, identities, folders, cached headers and bodies, EMAIL resolver)

Scope: base `85070479fe153c847e18ec4e3c204f7270f17b53` .. HEAD `927e3c60a1c351f90006d2a5316ca11fcc4d4777` on branch `main` (one task commit, `927e3c6`, supplied by the parent worker and checked against the task's `touches`). Production paths: `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/*.kt` (11 files) and `server/src/main/resources/db/migration/mail/V202610201402__mail_model.sql`. The only uncommitted changes are the test files listed below.

Requirements:
- `tasks/mail-model.md` acceptance criteria and the five recorded assumptions in its Notes
- SPEC.md §4.3 and §7.1–7.2, as cited by the task and in the production KDoc
- docs/ARCHITECTURE.md ("Coverage", persistence conventions), docs/QUALITY.md, AGENTS.md
- The intent stated in the migration and the KDoc, used only where the task leaves behaviour open (marked as such in the findings)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/model/MailAddressTests.kt` (new, unit)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/model/MailMessageLocationTests.kt` (new, unit)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/model/MailBodySizeTests.kt` (new, unit)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/model/MailModelConstraintTests.kt` (new, Spring Boot + Testcontainers Postgres)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/model/MailBodyCacheTests.kt` (8 tests added)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/model/MailItemResolverTests.kt` (3 tests added)

None of the 20 existing tests was changed, weakened or disabled. No production code was modified.

## Tests added or updated

Count: 44 added. The package now has 64 tests; before this run it had 20.

## Requirement coverage

Command F = `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.model.*'` (Postgres 16 via Testcontainers). Final run: 64 tests, 63 passed, 1 failed.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 mail_account: type IMAP_SMTP/GOOGLE/MICROSOFT, hosts/ports/TLS, encrypted password or OAuth connection, signature, notify flag | existing persistence tests; new `ports must be between 1 and 65535`, `the sync window must be at least one month`, `a Microsoft account cannot carry a password, but an IMAP account may have none`, `unknown enum values are rejected by check constraints`, `accounts list by display name and folders by path` | F | Pass |
| AC1 identity (unique email per account regardless of case, one default, reply-to/signature override) | existing identity test; new `paths, roles, identity emails and defaults are unique per account only`, `several non-default identities are allowed per account`, `identity overrides, folder flags and remaining message flags round-trip` | F | Pass |
| AC1 mail_folder (path, role, uidvalidity, uidnext, highestmodseq) | existing folder test; new `every folder role is accepted and a folder may have no role`, the role check in `unknown enum values ...`, folder flags in the `... round-trip` test | F | Pass |
| AC1 mail_message (account, folder, uid, Message-ID, headers, flags, size, hasAttachments, snippet, thread key) | existing round-trip and UID tests; new `a UID without folder, a non-positive UID and a negative size are rejected by the database`, `the same Message-ID may exist in several folders of an account`, `a thread lists its located messages by arrival`, `MailMessageLocationTests` (6), `MailAddressTests` (11: parse/format, groups, RFC 2047, Unicode, malformed input, converter null/empty) | F | Pass |
| AC1 cascades: deleting an account removes everything; deleting a folder keeps its messages as link targets (migration comment) | `deleting an account removes its folders, located messages and identities` (Pass); `deleting a folder keeps its messages as link targets` (**Fail**, TW-1) | F | Fail (TW-1) |
| AC1 body cache with size accounting (UTF-8 bytes, LRU eviction down to the budget, the body just stored is kept) | existing 5 cache tests; new `MailBodySizeTests` (2), `reading a body marks it as used without bumping its version`, `replacing a body bumps its version and keeps its id`, `a cache filled exactly to the budget evicts nothing`, `evict removes the least recently used bodies until the total fits`, `replacing a body with a larger one evicts others but keeps it`, `storing a body for an unknown message fails`, `concurrent stores of the same message leave one body` (8 threads), `a negative budget is rejected` | F | Pass |
| AC2 EMAIL resolver: re-resolve by Message-ID when the UID changed | existing 6 resolver tests; new `a detached row whose folder was deleted re-resolves to the oldest copy in another folder`, `a located message resolves to itself even when other copies exist`, `a message without sender has no subtitle` | F | Pass |
| AC3 `./gradlew test --tests 'ch.sonensei.cockpit.mail.model.*'` passes | the whole package | F | **Fail**: 1 of 64 fails (TW-1). Without the new TW-1 test, all 63 pass. |

## Focused test execution

Commands:

`cd server && ./gradlew ktlintCheck` -> BUILD SUCCESSFUL. The new tests were formatted with `./gradlew ktlintTestSourceSetFormat`, which touches test sources only.

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.model.*'`

Result:

Fail: 64 tests completed, 1 failed (`MailModelConstraintTests > deleting a folder keeps its messages as link targets()`, TW-1). The other 63 passed.

## Full test execution

Command:

`sh scripts/check.sh` (= `.taskcheck`; its server step is `cd server && ./gradlew --quiet check`)

Result:

Fail. The server step ran 1065 tests and 1 failed: the same TW-1 test. That was the only failing result file (`TEST-ch.sonensei.cockpit.mail.model.MailModelConstraintTests.xml`). The TFP gate tests ran before the server step. Because the script uses `set -e`, the web and android steps did not run.

## .taskcheck

Result:

Fail. This is the same run as above (2026-10-20 16:19:04 to 16:22:47 +02:00, exit 1), caused only by TW-1.

## Bugs found

### Deleting a folder that still holds located messages fails with a check violation

Finding ID:

`TW-1`

Test:

`MailModelConstraintTests > deleting a folder keeps its messages as link targets()` (`server/src/test/kotlin/ch/sonensei/cockpit/mail/model/MailModelConstraintTests.kt:245`)

Production source:

`server/src/main/resources/db/migration/mail/V202610201402__mail_model.sql:83,90,114`. `folder_id ... references mail_folder (id) on delete set null` conflicts with `constraint mail_message_uid check (uid is null or (uid > 0 and folder_id is not null))`.

Requirement:

AC1 (mail_message ↔ mail_folder) together with the link-survival assumption in the task Notes. The migration states the intent: "folder_id survives a folder deletion as null for the same reason" (link targets survive). Deleting a folder whose messages are still located is a normal sync event: the folder was deleted in another client.

Expected behavior:

Deleting a `mail_folder` row keeps its `mail_message` rows as link targets, with `folder_id` null and the message no longer located (`uid` null).

Actual behavior:

`folders.deleteById(folder.id)` throws `DataIntegrityViolationException`: `new row for relation "mail_message" violates check constraint "mail_message_uid"`, raised from `UPDATE ONLY "public"."mail_message" SET "folder_id" = NULL ...`. The SET NULL action clears only `folder_id`, so a row with a UID breaks the check, and the whole folder deletion is rolled back. The existing test `deleting a folder keeps its messages as link targets, deleting the account removes everything` passes only because it first clears `uid` with raw SQL. Deleting the account works, even with located messages; this was tested and passes.

Severity:

Medium. Any later sync code that deletes a vanished folder without first detaching every message fails. Today this precondition is documented nowhere.

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` Possible fixes: a new migration that also clears `uid` when the folder goes away (for example a `BEFORE DELETE` trigger on `mail_folder` that sets `uid = null, modseq = null` on its messages), or relaxing the check so that a null `folder_id` means "not located". If the parent decides that "callers must detach first" is the intended contract, that is a requirement decision: record it as an assumption and in the `MailFolder` KDoc.

### Lenient address parsing never reaches the documented "cannot be parsed" branch

Finding ID:

`TW-2`

Test:

`MailAddressTests > malformed headers are parsed leniently without failing, keeping valid neighbours and no blank addresses()` (passes)

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailAddress.kt:20-27`

Requirement:

KDoc of `parseList`: "A list that cannot be parsed at all gives no addresses." No acceptance criterion covers this.

Expected behavior:

Garbage header text gives no addresses, as the KDoc says.

Actual behavior:

`InternetAddress.parseHeader(header, false)` does not throw for any malformed input tried, so the `AddressException` branch (lines 26-27) is unreachable in practice. Malformed entries come through as pseudo addresses instead, for example `MailAddress("<")` for `<<>>`, `MailAddress("@@@")`, and `MailAddress("Ann <ann@example.com")` for a missing `>`. Valid neighbours are kept, and nothing throws.

Severity:

Low. This is informational: the KDoc and the behavior disagree. Values written by `formatList` round-trip correctly, including quotes, commas, `<>`, `;`, emoji and CJK names (tested). There is no failing test, because no requirement defines how malformed input should be handled.

Fix required:

`No` failing test. The parent decides whether to correct the KDoc or to filter out entries without `@`.

## Coverage

Status: partial-failed. The test command exited nonzero because of TW-1. All 64 tests ran and the JaCoCo data comes from that same run, so the numbers are fresh and diagnostic, but this is not a passing validation.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Every scoped Kotlin file is new in this task, so whole-file coverage equals changed-line coverage for those files.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-model`; base `85070479fe153c847e18ec4e3c204f7270f17b53`; HEAD `927e3c60a1c351f90006d2a5316ca11fcc4d4777`; task commit `927e3c6`, supplied by the parent; scope from `git diff --name-only 85070479..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): production at HEAD `927e3c6`, unchanged. Test sources are uncommitted. sha256 of `sha256sum server/src/test/kotlin/ch/sonensei/cockpit/mail/model/*.kt` output: `95f2e9011a3c39297bc9228ade8610bc664733658848be11b5a4a37ee96c94cc`. It was recorded before the final run and verified again after it.
- Exact included files: the 11 `.kt` files of `ch/sonensei/cockpit/mail/model` listed below. Excluded: `V202610201402__mail_model.sql`, because SQL cannot be instrumented by JaCoCo; its constraints and cascades are exercised behaviourally by `MailModelConstraintTests` and `MailModelPersistenceTests`. `MailRepositories.kt` holds only Spring Data interfaces with no bytecode, so all its metrics are N/A; its queries are exercised by the persistence and cache tests. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (project default tool version; report DTD 1.1); `./gradlew test --tests 'ch.sonensei.cockpit.mail.model.*'` followed by `./gradlew jacocoTestReport -x test`, run in `server/`. `jacocoTestReport` was run separately because Gradle skips it after a failed `test` task. It reads the `build/jacoco/test.exec` that this run wrote.
- Run start/completion time and exit status: test run 2026-10-20 about 16:18 to 16:18:44 +02:00 (exec file mtime 16:18:44.87), exit nonzero (1 failed test, TW-1); report generated 16:18:56, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` sha256 `f9763564d20bebca953baef61f48059cdc0294e0c27ceba3cfb6cd02199038c9`; `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 `7f0b5d50532ddd43454c5c306b3515c3ee85453830b6dac4df50d15455c5310a`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The exec and XML timestamps come after the test edits (last edit 16:17:54), and the snapshot hash was unchanged after the run. The later `.taskcheck` run overwrote `test.exec` with full-suite data; the numbers below come from the focused run.
- Final run after latest test additions and production fixes: yes for the latest test additions. No production fixes had been made yet. After TW-1 is fixed, this coverage describes a pre-fix revision and must be measured again.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" are JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailAccount.kt` | 123/174 (70.7%) | 2/2 (100.0%) | 19/33 (57.6%) | 26/31 (83.9%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailAddress.kt` | 195/206 (94.7%) | 30/34 (88.2%) | 11/11 (100.0%) | 16/18 (88.9%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailBody.kt` | 102/109 (93.6%) | 4/4 (100.0%) | 13/15 (86.7%) | 20/21 (95.2%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailBodyCache.kt` | 97/97 (100.0%) | 6/6 (100.0%) | 7/7 (100.0%) | 16/16 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailEnums.kt` | 93/93 (100.0%) | N/A | 3/3 (100.0%) | 12/12 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailFolder.kt` | 96/107 (89.7%) | N/A | 21/24 (87.5%) | 19/19 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailIdentity.kt` | 68/76 (89.5%) | N/A | 14/16 (87.5%) | 15/15 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailItemResolver.kt` | 88/88 (100.0%) | 16/16 (100.0%) | 3/3 (100.0%) | 12/12 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailMessage.kt` | 243/269 (90.3%) | 10/10 (100.0%) | 46/54 (85.2%) | 45/45 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailMessageLocator.kt` | 57/57 (100.0%) | 8/8 (100.0%) | 2/2 (100.0%) | 8/8 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailRepositories.kt` | N/A | N/A | N/A | N/A |
| **Aggregate (summed counters, 10 measured files)** | 1162/1276 (91.1%) | 76/80 (95.0%) | 139/168 (82.7%) | 189/197 (95.9%) |

The aggregate excludes the migration SQL (not instrumentable) and `MailRepositories.kt` (no bytecode). No scoped file is missing from the report.

For comparison, the run before the 44 new tests was diagnostic only, so it was not used for the aggregate. It measured instructions 1105/1276 (86.6%), methods 122/168 (72.6%) and lines 182/197 (92.4%).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailAccount.kt:25,27,36,38,44` (setters of `displayName`, `emailAddress`, `imapSecurity`, `smtpHost`, `username`) | Plain JPA property setters; mutation is account-API behavior, not this task | Justified gap: getters and persistence of every column are covered by round-trip tests |
| `MailAddress.kt:26-27` (`catch AddressException`) | Documented "cannot be parsed" fallback | Unreachable with lenient `parseHeader` (probed with 11 malformed inputs); reported as TW-2 |
| `MailAddress.kt:30` (`mailbox.address == null` branch), `:36` (`getGroup(false) == null` branch) | Defensive null handling of Jakarta Mail results | Justified gap: not producible through `parseHeader`; blank addresses and empty groups are tested |
| `MailBody.kt:19` (`messageId` getter) and the remaining uncovered methods in entity files | Accessors and Hibernate-generated or synthetic members | Justified gap: no behavior |
| Migration `mail_message.folder_id on delete set null` vs `mail_message_uid` check | Link survival on folder deletion | Test added; fails; TW-1 |

## Unable to test

- Concurrency of the LRU eviction itself, meaning two `put`s of different messages that evict at the same time. Concurrent upserts of the same message are tested and pass. Testing eviction under contention deterministically would need lock-level control that the current infrastructure does not offer. Low risk: both statements run in one transaction per `put`. No follow-up needed.
- The 2 GiB default budget (`DEFAULT_MAX_BYTES`) under real data volumes. Only the configured 100-byte budget and the bean construction are exercised. No follow-up needed for this task.
- Backup/restore round-trip and search integration of mail entities (AGENTS.md convention, SPEC.md §14) are not in this task's acceptance criteria, so they were not tested here.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.

TW-1 (Medium) makes the AC3 command and `.taskcheck` fail. TW-2 (Low) is informational.
