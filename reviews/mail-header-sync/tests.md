# Test Report

Date: 2026-10-20

Task: mail-header-sync - Header sync and threading

Scope: TFP task mail-header-sync, final round after fix round 2 (TW-1). Immutable base
7982562bfa14d1979d9b8b2c884ce23f4df04b57 (supplied by the parent), measured HEAD
cde8b0b2f6b4ee3b4e895aed5f4b7c4aa995bbf9 (`git log 7982562b..HEAD`: 0f56963c, 80cd72bc, daa16043, a466ba18, de57773b,
d4b17661, e382e69a, 9b1a5ea9, cde8b0b2). Working tree clean before and after all runs (`git status --short` empty); no
uncommitted inputs. Branch: main. The earlier reports used base c45c6528, from before the branch was rebased onto newer
main; the task's production file list is identical.

Requirements:
- tasks/mail-header-sync.md (acceptance criteria 1-3 and the recorded assumptions, in particular the subject fallback
  for every message without References/In-Reply-To, and loadOlder by arrival date)
- SPEC.md §7.2
- KDoc of `MailThreading` (arrival-order independence)
- reviews/mail-header-sync/review.md (CR-1..CR-6), reviews/mail-header-sync/fixes.md (round 1 and round 2 / TW-1)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md
- Parent instructions: verify the TW-1 fix with the existing tests, add tests only where meaningful gaps remain

## Test files changed

- None in this round. Run and evaluated, unchanged: `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/`
  `MailThreadingTests.kt`, `MailHeaderStoreTests.kt`, `MailHeaderSyncServiceTests.kt`, `ImapHelpersTests.kt`,
  `HeaderSyncGreenMailTests.kt`, `HeaderSyncDovecotTests.kt`.

## Tests added or updated

Count: 0

Reason: the TW-1 fix (9b1a5ea9, `MailThreading.assign`: a message without parents always takes its subject key, else
its own Message-ID; provisional keys merge into it) is covered by the tests that exposed it (6 arrival orders in
`MailThreadingTests`, the 3-folder Postgres case in `MailHeaderStoreTests`), and the fixer added an exhaustive test over
all 5040 arrival orders of a 7-message scenario (root, reply, deep reply, truncated reply, same-subject message, and an
unrelated conversation whose reply subject collides). I traced the remaining cases by hand against the new code (reply
whose own Message-ID is a provisional key and whose parent is cached; root without subject after its replies; the same
message cached twice in two folders): each is either covered by an existing test or keyed identically to its
first-arrival key. Coverage shows no meaningful uncovered behavior in the task's logic (see below). Adding more tests
would only duplicate existing ones.

TW-1 status: fixed and verified. All 4 previously failing cases pass (`MailThreadingTests` 35/35,
`MailHeaderStoreTests` 22/22).

## Requirement coverage

Commands: U = `cd server && ./gradlew ktlintFormat check test --rerun --continue`; I = `cd server && ./gradlew
integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.sync.*'` (GreenMail + Dovecot 2.3.21 Testcontainers, Podman
engine).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 initial sync of the configured window | Service `the first sync fetches the account's window and records the folder state`; GreenMail `the initial sync caches the headers of the window and threads them` | U, I | Pass |
| AC1 older on demand (by arrival date, CR-2/CR-5) | Service `loadOlder ...` tests (incl. not-positive limit, \Noselect, implicit full sync event, unknown folder); GreenMail `older mail is loaded on demand`, `older mail is picked by arrival date, not by UID` | U, I | Pass |
| AC1 incremental via UIDNEXT/flags | Service `without CONDSTORE ...`, `a folder without cached messages only looks for new ones`, `new UIDs already cached are not fetched again`; Store reconcile tests; GreenMail `an incremental sync reconciles new, deleted and flag-changed messages` | U, I | Pass |
| AC1 CONDSTORE/QRESYNC when supported | Service CONDSTORE/QRESYNC branch tests (incl. no cached HIGHESTMODSEQ, NOMODSEQ); Dovecot `with QRESYNC the open reports flag changes and expunges, and new mail is fetched`, `with CONDSTORE only, flags changed since the cached HIGHESTMODSEQ and expunges are reconciled` | U, I | Pass |
| AC1 UIDVALIDITY change -> resync folder | Service `a changed UIDVALIDITY detaches, resyncs and prunes the folder`, `a resync looks up linked rows ...`; GreenMail `a UIDVALIDITY change resyncs the folder and keeps linked rows`, `a resync finds linked messages older than the window again by Message-ID`, `a resync finds many linked old messages again with batched Message-ID searches`, `a resync removes rows that nothing links to` | U, I | Pass |
| AC1 error behavior: unknown folder / account gone -> NotFound, no server contact | Service `syncing or loading older mail of an unknown folder is not found and contacts no server`, `syncing a folder whose account is gone is not found and contacts no server` | U | Pass |
| AC2 threading by References/In-Reply-To | MailThreadingTests (In-Reply-To, References, deep reply, replies before root, merges, CR-1 orders); Store threading tests; GreenMail `a reply that arrives before its parent ...`, `a reply that connects two cached conversations merges them in the database` | U, I | Pass |
| AC2 subject fallback, independent of arrival order (TW-1) | MailThreadingTests `a root, its reply and another message with the root's subject share one conversation in any arrival order` (6 orders), `threading by references and subject gives the same conversations in every arrival order` (5040 orders); MailHeaderStoreTests `a root synced after its reply still shares the subject conversation of other messages without references` | U | Pass |
| AC3 external GreenMail tests: new/deleted/flag-changed reconciled | GreenMail `an incremental sync reconciles new, deleted and flag-changed messages`, `a deleted message that an item links to stays as a detached link target`, `a folder emptied on the server removes every cached message` | I | Pass (GreenMail 14/14, Dovecot 2/2) |
| AC3 unit tests for threading | MailThreadingTests (35 tests) | U | Pass |

## Focused test execution

Command:

`cd server && ./gradlew integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.sync.*'` (22:02, HEAD cde8b0b2)

Result:

Pass: HeaderSyncGreenMailTests 14/14, HeaderSyncDovecotTests 2/2 (0 failures, 0 skipped).

The sync unit suites ran within the full run below: ImapHelpersTests 33/33, MailHeaderStoreTests 22/22,
MailHeaderSyncServiceTests 23/23, MailThreadingTests 35/35.

## Full test execution

Command:

`cd server && ./gradlew ktlintFormat check test --rerun --continue` (finished 22:02:01, HEAD cde8b0b2)

Result:

Pass: BUILD SUCCESSFUL; ktlint checks pass, ktlintFormat changed nothing (`git status` clean afterwards); 1970 tests,
0 failures, 0 errors, 0 skipped (summed from `build/test-results/test/*.xml`).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (22:03-22:04) -> "== all checks passed" (server, web, android).

## Bugs found

None in this round. TW-1 (subject-fallback threading depended on arrival order) from round 2 is fixed in 9b1a5ea9 /
cde8b0b2 and verified by the tests listed above.

## Coverage

Status: verified (unit run and sync integration run both passed; reports generated from their fresh execution data).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately; all six files are new in this task, so whole-file coverage equals
coverage of the task's added lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-header-sync; base
  7982562bfa14d1979d9b8b2c884ce23f4df04b57; HEAD cde8b0b2f6b4ee3b4e895aed5f4b7c4aa995bbf9; base and commits supplied by
  the parent, matched against `git diff --stat 7982562b..HEAD` and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD cde8b0b2, clean tree. sha256 of the sync files
  (prefixes): main ImapRemoteMailbox 3d5df6bcd265, MailHeaderStore 87a52d361e32, MailHeaderSyncService d28b85962596,
  MailSyncRepository 9858358a7afb, MailThreading 3c3fe8cec417, RemoteFolder a86bacaa72c2; tests HeaderSyncDovecotTests
  4fdf6c2b1066, HeaderSyncGreenMailTests 693b0ef1f156, ImapHelpersTests 4119acbf8d4d, MailHeaderStoreTests faa8ff60d017,
  MailHeaderSyncServiceTests 92774dbab590, MailThreadingTests 685054214fbe. Identical before and after the runs.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/{ImapRemoteMailbox,MailHeaderStore,
  MailHeaderSyncService,MailSyncRepository,MailThreading,RemoteFolder}.kt` (all added by the task). No renamed or
  deleted files. `MailSyncRepository.kt` holds only Spring Data repository interfaces / projections: in the report with
  no executable counters (N/A). The report also lists a `Comparisons.kt` source entry in the package with no counters;
  it is not a task file (no such file in the repository, a source-mapped Kotlin inline function) and is excluded.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (project
  configuration, unchanged), Gradle 9.8.1, working directory `server/`.
  1. `./gradlew ktlintFormat check test --rerun --continue` (whole unit suite, writes `build/jacoco/test.exec`);
  2. `./gradlew integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.sync.*'` (writes
     `build/jacoco/integrationTest.exec`);
  3. `./gradlew jacocoTestReport -x test --init-script ../.tmp/jacoco-combined.init.gradle.kts jacocoCombinedReport`.
  `jacocoTestReport` (documented) reads unit-test data only. The supplementary `jacocoCombinedReport` is a JacocoReport
  task defined in a Git-ignored init script under `.tmp/` (no project file changed) reading both execution files with
  the same classes and sources; it is needed because `ImapRemoteMailbox` is exercised by the GreenMail/Dovecot tests.
- Run start/completion time and exit status: (1) ~22:00-22:02:01, BUILD SUCCESSFUL; (2) 22:02:05-22:02:46, 0 failures
  (test results 16/16); (3) 22:02:55-22:02:56, completed without error.
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` (22:02:01),
  `server/build/jacoco/integrationTest.exec` (22:02:45);
  `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 fca989da9a0e56c58b97875f35ac2075d3edf8dc82aec478501e0e3ac60da2aa;
  `server/build/reports/jacoco/combined/combined.xml` sha256 76ba0dc0049546f5ebd8392d79e3dc7a2d9545f64f13d3a8075cc6dd9408553e.
- Evidence outputs are fresh and inputs stayed unchanged: yes; `--rerun` forced both test tasks, both execution files
  and both XML reports were written after the run started, and the snapshot hashes were identical afterwards.
- Final run after latest test additions and production fixes: yes (HEAD cde8b0b2 includes the TW-1 fix; no tests were
  added in this round).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.
JaCoCo counters: Statements = INSTRUCTION, Branches = BRANCH, Functions = METHOD, Lines = LINE.

Unit tests only (documented `jacocoTestReport`):

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `ImapRemoteMailbox.kt` | 584/2202 (26.5%) | 68/282 (24.1%) | 20/53 (37.7%) | 78/242 (32.2%) |
| `MailHeaderStore.kt` | 705/718 (98.2%) | 50/54 (92.6%) | 24/24 (100.0%) | 122/122 (100.0%) |
| `MailHeaderSyncService.kt` | 909/917 (99.1%) | 65/66 (98.5%) | 24/26 (92.3%) | 119/119 (100.0%) |
| `MailSyncRepository.kt` | N/A | N/A | N/A | N/A |
| `MailThreading.kt` | 242/242 (100.0%) | 30/30 (100.0%) | 8/8 (100.0%) | 34/34 (100.0%) |
| `RemoteFolder.kt` | 282/291 (96.9%) | N/A | 40/43 (93.0%) | 43/43 (100.0%) |
| Aggregate (summed, 6 files, none missing) | 2722/4370 (62.3%) | 213/432 (49.3%) | 116/154 (75.3%) | 396/560 (70.7%) |

Unit + sync integration tests (supplementary `jacocoCombinedReport`):

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `ImapRemoteMailbox.kt` | 2024/2202 (91.9%) | 199/282 (70.6%) | 52/53 (98.1%) | 235/242 (97.1%) |
| `MailHeaderStore.kt` | 709/718 (98.7%) | 52/54 (96.3%) | 24/24 (100.0%) | 122/122 (100.0%) |
| `MailHeaderSyncService.kt` | 912/917 (99.5%) | 65/66 (98.5%) | 25/26 (96.2%) | 119/119 (100.0%) |
| `MailSyncRepository.kt` | N/A | N/A | N/A | N/A |
| `MailThreading.kt` | 242/242 (100.0%) | 30/30 (100.0%) | 8/8 (100.0%) | 34/34 (100.0%) |
| `RemoteFolder.kt` | 291/291 (100.0%) | N/A | 43/43 (100.0%) | 43/43 (100.0%) |
| Aggregate (summed, 6 files, none missing) | 4178/4370 (95.6%) | 346/432 (80.1%) | 152/154 (98.7%) | 553/560 (98.8%) |

Change since round 2: only `MailThreading.kt` changed (the TW-1 fix removed two `?:` steps: 260 -> 242 instructions,
34 -> 30 branches, 36 -> 34 lines); it stays fully covered. All other files have the same counters as in round 2.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailThreading.kt` (100%) | AC2 arrival-order independence (TW-1) | Fixed; covered by the 6-order, 5040-order and Postgres store tests, all passing |
| `MailHeaderSyncService.kt:35` | Generated getter of the `OlderMessagesResult.hasMore` data class property (the one missed method) | Value is asserted through the data class equality in the loadOlder tests; the getter itself is not called. Non-material |
| `MailHeaderSyncService.kt:219` (one branch of `folder.uidNext ?: 1`) | Incremental sync of a folder with UIDVALIDITY but no cached UIDNEXT | Not reachable through `finish`, which always stores UIDNEXT with UIDVALIDITY; justified gap |
| `ImapRemoteMailbox.kt:77-78, 83-84` | `MessagingException` during open / inside the sync block becomes `MailConnectionException` | Error mapping only; needs fault injection into a live IMAP connection, not asked by the criteria; justified gap (low risk, short code) |
| `ImapRemoteMailbox.kt:126` | `receivedDates(emptyList())` short-cut | The service only reads dates when more are missing than the limit; defensive, justified gap |
| `ImapRemoteMailbox.kt:171` | `flagsChangedSince` on an empty folder | The service only calls it with cached UIDs; an emptied folder takes the no-cached-messages path (GreenMail `a folder emptied on the server ...`); defensive, justified gap |
| `ImapRemoteMailbox.kt:288, 306-307` | Undecodable header text falls back to the raw value | `MimeUtility.decodeText` rarely throws for header text; defensive, justified gap |
| `ImapRemoteMailbox.kt` partial branches (86-99, 114-140, 151-163, 184-229, 256-258, 313-316, 381) | Kotlin null-safety / `?:` / `when` branches on IMAP response items (missing ENVELOPE parts, absent MODSEQ, null BODYSTRUCTURE pieces) | Main paths run against real GreenMail and Dovecot; the missing sides are null fields real servers rarely send. Parsing helpers are unit-tested in `ImapHelpersTests`; no further test added |
| `MailHeaderStore.kt:82, 226` (one branch each) | `checkNotNull(message.uid)` failure; `modSeq` equal to the cached value | Rows found by `uid IN (...)` always have a UID; equal MODSEQ is the no-change case. Low risk; justified gap |

## Unable to test

- `MailConnectionException` mapping for IMAP failures during open or during a sync (`ImapRemoteMailbox.kt:77-84`):
  needs fault injection into a live IMAP connection; not required by the criteria. No follow-up needed unless a later
  task (mail-read-api / mail-idle) depends on the exact error mapping.
- CR-6 (`isLinked` cost) is a performance concern deferred by the fixer (outside the task's touches); not tested here.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
TW-1 is fixed and verified on the final revision cde8b0b2: the server check (1970 tests, ktlint), the sync
integration tests (GreenMail 14/14, Dovecot CONDSTORE/QRESYNC 2/2) and `.taskcheck` all pass. No tests were added in
this round because no meaningful gap remained.
