# Test Report

Date: 2026-10-20

Task: mail-header-sync - Header sync and threading

Scope: TFP task mail-header-sync, final round after fixes. Immutable base c45c65281cf540a170dd087018cd73ecf7294794,
measured HEAD a616da678070fa3304852f2e8f0daf32b977028e (`git log c45c6528..HEAD`; code commits 3658b4d8, e84ef42d,
7a1c626e, 51aa4c2a, ae6401ea, a616da67), plus one uncommitted test file change made in this round
(`MailHeaderSyncServiceTests.kt`, blob 7ce0f11efeb465c427d8a322cb5b1766b2141675). No uncommitted production changes.
Branch: main.

Requirements:
- tasks/mail-header-sync.md (acceptance criteria 1-3 and the recorded assumptions, notably the subject-fallback
  assumption "every message without References/In-Reply-To is threaded by normalized subject")
- SPEC.md §7.2
- KDoc of `MailThreading` ("Whatever the arrival order, a message joins the conversation of every cached message it
  refers to and of every cached message that refers to it")
- reviews/mail-header-sync/review.md (CR-1..CR-6), reviews/mail-header-sync/fixes.md
- docs/ARCHITECTURE.md "Coverage", AGENTS.md
- Parent instructions: run the never-run tests of a616da67 first, then fill only missing gaps

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt` (this round, uncommitted)
- Run and evaluated in this round, unchanged (committed in a616da67 by the interrupted earlier round):
  `HeaderSyncGreenMailTests.kt`, `MailHeaderStoreTests.kt`, `MailHeaderSyncServiceTests.kt`, `MailThreadingTests.kt`

## Tests added or updated

Count: 2 added in this round (`syncing or loading older mail of an unknown folder is not found and contacts no
server`, `syncing a folder whose account is gone is not found and contacts no server`). The 6 tests of a616da67 (one of
them parameterized with 6 cases) were run for the first time; none needed fixing as tests. 4 of their cases fail
because of a production defect (TW-1), they are kept as is.

Results of the a616da67 tests:

| Test | Result |
|---|---|
| HeaderSyncGreenMailTests `a resync finds many linked old messages again with batched Message-ID searches` | Pass |
| MailHeaderStoreTests `answered alone and draft alone are flag changes` | Pass |
| MailHeaderStoreTests `a root synced after its reply still shares the subject conversation of other messages without references` | **Fail (TW-1)** |
| MailHeaderSyncServiceTests `with CONDSTORE but no cached HIGHESTMODSEQ the flags of every cached UID are fetched` | Pass |
| MailHeaderSyncServiceTests `with CONDSTORE on a folder without MODSEQ support (NOMODSEQ) the flags of every cached UID are fetched` | Pass |
| MailHeaderSyncServiceTests `loadOlder rejects a limit that is not positive without opening the folder` | Pass |
| MailThreadingTests `a root, its reply and another message with the root's subject share one conversation in any arrival order` | 3 of 6 cases **Fail (TW-1)**: `b,a,d`, `b,d,a`, `d,b,a`; `a,b,d`, `a,d,b`, `d,a,b` pass |

## Requirement coverage

Commands: U = `cd server && ./gradlew ktlintFormat check --continue` (unit suites); I = `cd server && ./gradlew
integrationTest --tests 'ch.sonensei.cockpit.mail.sync.*'` (GreenMail + Dovecot 2.3.21 Testcontainers, Podman engine).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 initial sync of the configured window | Service `the first sync fetches the account's window and records the folder state`; GreenMail `the initial sync caches the headers of the window and threads them` | U, I | Pass |
| AC1 older on demand (by arrival date, CR-2/CR-5) | Service `loadOlder ...` (7 tests incl. not-positive limit, \Noselect, implicit full sync event); GreenMail `older mail is loaded on demand`, `older mail is picked by arrival date, not by UID` | U, I | Pass |
| AC1 incremental via UIDNEXT/flags | Service `without CONDSTORE ...`, `a folder without cached messages only looks for new ones`, `new UIDs already cached are not fetched again`; Store reconcile tests; GreenMail `an incremental sync reconciles new, deleted and flag-changed messages` | U, I | Pass |
| AC1 CONDSTORE/QRESYNC when supported | Service CONDSTORE/QRESYNC branch tests (incl. no cached HIGHESTMODSEQ, NOMODSEQ); Dovecot `with QRESYNC the open reports flag changes and expunges, and new mail is fetched`, `with CONDSTORE only, flags changed since the cached HIGHESTMODSEQ and expunges are reconciled` | U, I | Pass |
| AC1 UIDVALIDITY change -> resync folder | Service `a changed UIDVALIDITY detaches, resyncs and prunes the folder`, `a resync looks up linked rows ...`; GreenMail `a UIDVALIDITY change resyncs the folder and keeps linked rows`, `a resync finds linked messages older than the window again by Message-ID`, `a resync finds many linked old messages again with batched Message-ID searches`, `a resync removes rows that nothing links to` | U, I | Pass |
| AC1 error behavior: unknown folder / account gone -> NotFound, no server contact | Service `syncing or loading older mail of an unknown folder is not found and contacts no server`, `syncing a folder whose account is gone is not found and contacts no server` (added) | U | Pass |
| AC2 threading by References/In-Reply-To | MailThreadingTests (reply by In-Reply-To, by References, deep reply, replies before root, merges, CR-1 orders); Store threading tests; GreenMail `a reply that arrives before its parent ...`, `a reply that connects two cached conversations merges them in the database` | U, I | Pass |
| AC2 subject fallback, independent of arrival order | MailThreadingTests `a root, its reply and another message with the root's subject share one conversation in any arrival order` (6 orders); MailHeaderStoreTests `a root synced after its reply still shares the subject conversation of other messages without references` | U | **Fail (TW-1)** |
| AC3 external GreenMail tests: new/deleted/flag-changed reconciled | GreenMail `an incremental sync reconciles new, deleted and flag-changed messages`, `a deleted message that an item links to stays as a detached link target`, `a folder emptied on the server removes every cached message` | I | Pass (14/14 GreenMail, 2/2 Dovecot) |
| AC3 unit tests for threading | MailThreadingTests (34 cases) | U | 31 Pass, 3 Fail (TW-1) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sync.*'` (first, on a616da67 as committed, 21:16)

`cd server && ./gradlew ktlintFormat test --tests 'ch.sonensei.cockpit.mail.sync.*'` (after adding the 2 tests)

Result:

Fail: 112 tests, 4 failed (all TW-1: MailThreadingTests 3 cases, MailHeaderStoreTests 1). Per suite: ImapHelpersTests
33/33, MailHeaderSyncServiceTests 23/23, MailThreadingTests 31/34, MailHeaderStoreTests 21/22.

Command:

`cd server && ./gradlew integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.sync.*'` (21:17, on a616da67) and
`cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.sync.*'` (21:27-21:28, final snapshot)

Result:

Pass: HeaderSyncGreenMailTests 14/14, HeaderSyncDovecotTests 2/2, both runs.

## Full test execution

Command:

`cd server && ./gradlew ktlintFormat check --continue` (final snapshot, 21:24-21:27)

Result:

Fail: ktlint checks pass (ktlintFormat changed nothing); 1937 tests, 5 failed: the 4 TW-1 cases plus
`ClockConfigurationTests > the application clock is the system clock in UTC` (core/persistence, outside this task:
`2026-10-20T19:26:35.580261Z should be >= 2026-10-20T19:26:35.580261021Z`, a timing/precision flake; it passed in the
run at 21:18 and in the `.taskcheck` run below). An earlier run of the same command at 21:18 (before the 2 added
tests): 1935 tests, 4 failed (TW-1 only).

## .taskcheck

Result:

Fail: `sh scripts/check.sh` (21:28:59-21:33:19, 4m20s) -> server `:test` 1937 tests, 4 failed (the TW-1 cases only).
It will pass only after TW-1 is fixed.

## Bugs found

### Subject-fallback threading depends on arrival order when a root arrives after its reply

Finding ID:

`TW-1`

Test:

`MailThreadingTests > a root, its reply and another message with the root's subject share one conversation in any
arrival order` cases `b,a,d`, `b,d,a`, `d,b,a`; `MailHeaderStoreTests > a root synced after its reply still shares
the subject conversation of other messages without references`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailThreading.kt:94-98` (key selection for a message without
parents: `provisional.firstOrNull { it in provisionalExisting }` is taken before the subject `default`)

Requirement:

AC2 "Threading by References/In-Reply-To with subject fallback" with the recorded assumption "every message without
References/In-Reply-To is threaded by normalized subject"; MailThreading KDoc "Whatever the arrival order ...".

Expected behavior:

a (`Budget`, no references), b (`Re: Budget`, In-Reply-To a) and d (`Budget`, no references) end up in one
conversation whatever order they are cached in (UID order within a folder, folder order in `syncAccount`, e.g. the
reply in INBOX before the root in Sent).

Actual behavior:

Order a,b,d: all keyed `subject:budget`. Order b,a,d: b is keyed `<a>` (its root); a has no parents and finds the
existing key `<a>`, so it keeps `<a>` instead of its subject key; d gets `subject:budget` and stays apart from a and
b. Same for b,d,a and d,b,a. The store test shows the same split against Postgres (reply in INBOX, root in a second
folder, a same-subject reminder in a third).

Severity:

Medium (conversations split depending on sync order; the c,b,a fix of CR-1 does not cover a root that has a subject
shared with other reference-less mail).

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.` A possible direction: a message without parents
whose own Message-ID is an existing provisional key should still take the subject key (when its subject is not empty)
and merge the provisional `<own id>` key into it. Note that the existing test `replies that arrive before their root
end up in the root's conversation` asserts the literal key `<a@example.test>`; the fixer has to decide which key the
merged conversation should have, the tests of a616da67 only require that it is one conversation.

## Coverage

Status: partial-failed. The documented command `./gradlew test jacocoTestReport` cannot produce a report while `:test`
fails (TW-1), so the report task was run separately on the fresh execution data of the failed run. Numbers are
diagnostic for the final snapshot; the 4 failing tests ran to completion and their hits are included.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (all six files are new in this task, so whole-file coverage equals coverage of the
task's added lines here).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-header-sync; base
  c45c65281cf540a170dd087018cd73ecf7294794; HEAD a616da678070fa3304852f2e8f0daf32b977028e; commits and base supplied by
  the parent and matched against `git diff --name-only c45c6528..HEAD -- server/src/main` and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD a616da67 + modified
  `server/src/test/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncServiceTests.kt` (git blob
  7ce0f11efeb465c427d8a322cb5b1766b2141675); no other changes (`git status --short` before and after the runs).
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/{ImapRemoteMailbox,MailHeaderStore,
  MailHeaderSyncService,MailSyncRepository,MailThreading,RemoteFolder}.kt` (all added by the task). No renamed or
  deleted files. `MailSyncRepository.kt` holds only Spring Data repository interfaces / projections: present in the
  report with no executable counters (N/A), exercised through the Store and GreenMail tests.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (project
  configuration, unchanged), working directory `server/`.
  1. `./gradlew ktlintFormat check --continue` (whole unit suite, produces `build/jacoco/test.exec`);
  2. `./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.sync.*'` (produces `build/jacoco/integrationTest.exec`);
  3. `./gradlew jacocoTestReport -x test --init-script ../.tmp/jacoco-combined.init.gradle.kts jacocoCombinedReport`.
  The documented report (`jacocoTestReport`) only reads unit-test data. The supplementary `jacocoCombinedReport` is a
  JacocoReport task defined in a Git-ignored init script under `.tmp/` (no project file changed) that reads both
  execution files with the same classes and sources; it is needed because `ImapRemoteMailbox` is only exercised by the
  GreenMail/Dovecot tests.
- Run start/completion time and exit status: (1) 21:24:01-21:27:31, exit 1 (TW-1 + Clock flake, see above);
  (2) 21:27:31-21:28:42, exit 0; (3) 21:28:42-21:28:46, exit 0.
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` (21:27:30),
  `server/build/jacoco/integrationTest.exec` (21:28:40);
  `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 6f7ad9d46e0427290009577b9fc9af560f6ad323292c0e2a6954b1d0c5c6f64f;
  `server/build/reports/jacoco/combined/combined.xml` sha256 e80d6dca4f7c8922e398772965a7b1ca5d076fd15faee4af0c90a611108adb81.
- Evidence outputs are fresh and inputs stayed unchanged: yes; both execution files and both XML reports were written
  by the runs above (timestamps after the run start), and no source or test changed between 21:24 and 21:33.
- Final run after latest test additions and production fixes: yes for the tests of this round; the TW-1 fix is still
  pending, so coverage must be re-measured after it.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.
JaCoCo counters: Statements = INSTRUCTION, Branches = BRANCH, Functions = METHOD, Lines = LINE.

Unit tests only (documented `jacocoTestReport`):

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `ImapRemoteMailbox.kt` | 584/2202 (26.5%) | 68/282 (24.1%) | 20/53 (37.7%) | 78/242 (32.2%) |
| `MailHeaderStore.kt` | 705/718 (98.2%) | 50/54 (92.6%) | 24/24 (100.0%) | 122/122 (100.0%) |
| `MailHeaderSyncService.kt` | 909/917 (99.1%) | 65/66 (98.5%) | 24/26 (92.3%) | 119/119 (100.0%) |
| `MailSyncRepository.kt` | N/A | N/A | N/A | N/A |
| `MailThreading.kt` | 260/260 (100.0%) | 34/34 (100.0%) | 8/8 (100.0%) | 36/36 (100.0%) |
| `RemoteFolder.kt` | 282/291 (96.9%) | N/A | 40/43 (93.0%) | 43/43 (100.0%) |
| Aggregate (summed, 6 files, none missing) | 2740/4388 (62.4%) | 217/436 (49.8%) | 116/154 (75.3%) | 398/562 (70.8%) |

Unit + sync integration tests (supplementary `jacocoCombinedReport`):

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `ImapRemoteMailbox.kt` | 2024/2202 (91.9%) | 199/282 (70.6%) | 52/53 (98.1%) | 235/242 (97.1%) |
| `MailHeaderStore.kt` | 709/718 (98.7%) | 52/54 (96.3%) | 24/24 (100.0%) | 122/122 (100.0%) |
| `MailHeaderSyncService.kt` | 912/917 (99.5%) | 65/66 (98.5%) | 25/26 (96.2%) | 119/119 (100.0%) |
| `MailSyncRepository.kt` | N/A | N/A | N/A | N/A |
| `MailThreading.kt` | 260/260 (100.0%) | 34/34 (100.0%) | 8/8 (100.0%) | 36/36 (100.0%) |
| `RemoteFolder.kt` | 291/291 (100.0%) | N/A | 43/43 (100.0%) | 43/43 (100.0%) |
| Aggregate (summed, 6 files, none missing) | 4196/4388 (95.6%) | 350/436 (80.3%) | 152/154 (98.7%) | 555/562 (98.8%) |

Effect of this round: `MailHeaderSyncService.kt` branches 63/66 -> 65/66 (lines 239 and 242, the NotFound paths).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailThreading.kt` (100% covered) | AC2 arrival-order independence | Fully executed, yet wrong for some orders: TW-1. Coverage does not reflect the defect. |
| `MailHeaderSyncService.kt:239,242` | Unknown folder / gone account must be NotFound, not a server call | Added the 2 service tests; covered now |
| `MailHeaderSyncService.kt:219` (one branch of `folder.uidNext ?: 1`) | Incremental sync of a folder with UIDVALIDITY but no cached UIDNEXT | Not reachable through `finish`, which always stores UIDNEXT with UIDVALIDITY; justified gap |
| `ImapRemoteMailbox.kt:77-78` | A `MessagingException` on open becomes `MailConnectionException` | Error mapping only; needs a broken server connection mid-open, not asked by the criteria; justified gap (low risk, short code) |
| `ImapRemoteMailbox.kt:83-84` | A `MessagingException` inside the sync block becomes `MailConnectionException` | Same as above; a server dropping the connection mid-sync is not reproducible with GreenMail/Dovecot without fault injection; justified gap |
| `ImapRemoteMailbox.kt:126` | `receivedDates(emptyList())` short-cut | The service never calls it with an empty list (it only reads dates when more are missing than the limit); defensive, justified gap |
| `ImapRemoteMailbox.kt:171` | `flagsChangedSince` on an empty folder | The service only calls it with cached UIDs; an emptied folder goes through the no-cached-messages path (covered by GreenMail `a folder emptied on the server ...`); defensive, justified gap |
| `ImapRemoteMailbox.kt:306` | Undecodable encoded-word subject falls back to the raw value | `MimeUtility.decodeText` rarely throws `IOException` for header text; defensive, justified gap |
| `ImapRemoteMailbox.kt` partial branches (e.g. 86-99, 114-140, 196-229, 256-258, 313-316) | Kotlin null-safety / `?:` / `when` branches on IMAP response items (missing ENVELOPE parts, absent MODSEQ, null BODYSTRUCTURE pieces) | Main paths exercised against real GreenMail and Dovecot; the missing sides are null fields real servers send rarely. Parsing helpers are unit-tested in `ImapHelpersTests`; no further test added |
| `MailHeaderStore.kt:82,226` (one branch each) | `checkNotNull(message.uid)` failure; `modSeq` equal to the cached value | Rows found by `uid IN (...)` always have a UID; equal MODSEQ is the no-change case, tests use differing MODSEQs. Low risk; justified gap |

## Unable to test

- `MailConnectionException` mapping for IMAP failures during open or during a sync (`ImapRemoteMailbox.kt:77-84`):
  needs fault injection into a live IMAP connection; not required by the criteria. No follow-up needed unless a later
  task (mail-read-api / mail-idle) depends on the exact error mapping.
- CR-6 (`isLinked` cost) is a performance concern deferred by the fixer; not tested here.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.
TW-1 (subject-fallback threading depends on arrival order, found by the previously unrun tests of a616da67) makes 4
unit test cases fail and blocks `./gradlew check` and `.taskcheck`. All other criteria are covered and pass,
including the GreenMail (14/14) and Dovecot CONDSTORE/QRESYNC (2/2) external tests. After the TW-1 fix, the sync
unit suites, `integrationTest --tests 'ch.sonensei.cockpit.mail.sync.*'` and coverage must be re-run.
