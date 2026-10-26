# Test Report

Date: 2026-10-26

Task: itip-invitations - Send invitations for local events (iTIP)

Scope: `git diff 35a771019003fe38dfb83cb9726695d905e419fe..0df4bd143b24c205a78725be8f124872fa610696 -- server` (task commits a7a704ab, 2ed127d1, 7df31077, 091d29b6, 800c4e03, 0df4bd14; supplied by the parent worker). While this run was in progress, HEAD moved to d363170a5400f1ac29e96aa5bbd713201a0a1f28 ("new task itip-reply-ordering"). That commit only adds `tasks/itip-reply-ordering.md`, and `git diff 0df4bd14..HEAD -- server` is empty, so the production code under test is the same. This is a re-run on the final code after the fixer (CR-1, CR-2, CR-4, partial CR-3). The earlier test-writer run never wrote this report.

Requirements:
- `tasks/itip-invitations.md` acceptance criteria: (1) REQUEST/CANCEL as text/calendar mails; incoming REPLY updates attendee status; (2) `external` GreenMail tests
- Task assumptions recorded in `tasks/itip-invitations.md` (Inbox only, LOCAL calendars, From must match the attendee, no override created for an occurrence REPLY, CR-3 partial: newest answer per attendee within one mail, a mixed-recipient uninvite CANCEL is rejected with 400)
- SPEC.md §6.2 (invitations), `docs/ARCHITECTURE.md` "Coverage" and "Code conventions" (modules talk through services)
- `reviews/itip-invitations/review.md` and `reviews/itip-invitations/fixes.md` (the fixer's changes to cover)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/itip/ItipReplyProcessorTests.kt` (extended: 4 -> 11 tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailReadServiceInInboxTests.kt` (new, 4 tests, for the fixer's `MailReadService.inInbox`)

`ItipMessagesTests.kt` (26 tests) and `ItipGreenMailTests.kt` (32 tests) were reviewed and left unchanged. They already cover the fixer's CR-1 behavior (an uninvite CANCEL has no STATUS; mixed recipients for a live event give 400 and nothing is sent; mixed recipients for a trashed event get a full CANCEL) and the CR-3 ordering in `ItipReply.ORDER`.

## Tests added or updated

Count: 11 (7 in `ItipReplyProcessorTests`, 4 in `MailReadServiceInInboxTests`)

Added to `ItipReplyProcessorTests`:
- `answers for different events in one message are each applied, not merged as one attendee's answers`: CR-3 "newest per attendee" grouping must not collapse answers for different UIDs
- `a failure that is no optimistic-locking conflict is not retried`: CR-4 retry is limited to optimistic-lock conflicts
- `a REPLY whose answer is already stored changes nothing`: no save and no change event
- `a message without a sender is not read`: anti-spoofing guard (no From address means the message is not loaded)
- `a series REPLY updates the master and its live overrides, not an override in the Trash`
- `a calendar part with an unknown charset is read as UTF-8, not dropped`
- `only newly created mail messages are queued for checking`: `onChange` filter. The test is deterministic: the processor's executor has a single thread and works in FIFO order.

Added in `MailReadServiceInInboxTests` (CR-2 `MailReadService.inInbox`):
- `a message located in the Inbox is returned`
- `a message in another folder, or in a folder without a role, is not` (ARCHIVE, SENT, no role)
- `a message that is no longer located (moved away or its folder deleted) is not, without looking up a folder`
- `an unknown message or a folder that is gone gives null`

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: REQUEST sent as text/calendar mail (method, UID, SEQUENCE, organizer, RSVP, overrides, all-day, no VALARM) | `ItipMessagesTests` (REQUEST/series/override/all-day cases); `ItipGreenMailTests`: `a REQUEST arrives at every attendee as a text calendar part with method REQUEST`, `a REQUEST to chosen attendees only goes to them, ...`, `the account's default identity is the sender and the organizer`, `a REQUEST for a series carries its overrides with the series UID`, `the text part of an all-day invitation ...`, `a title with a line break cannot inject mail headers or extra recipients` | `cd server && ./gradlew test --tests '*itip*' --tests '*MailReadServiceInInboxTests' jacocoTestReport ktlintCheck`; `cd server && ./gradlew integrationTest --tests '*ItipGreenMail*'` | Pass |
| AC1: CANCEL sent as text/calendar mail (full cancel, trashed event, single override) | `ItipMessagesTests`: `a CANCEL marks the event cancelled, ...`; `ItipGreenMailTests`: `a CANCEL arrives as a text calendar part with method CANCEL ...`, `a CANCEL is sent for an event in the Trash, a REQUEST is not`, `a CANCEL for one override goes out as just that occurrence, ...` | same two commands | Pass |
| CR-1 (fixer): an uninvite CANCEL has no STATUS and lists only the uninvited; a mixed live-event CANCEL gets 400 and nothing is sent; a mixed trashed-event CANCEL is a full cancel | `ItipMessagesTests`: `a CANCEL to people no longer invited lists only them as attendees and does not cancel the event`; `ItipGreenMailTests`: `a CANCEL to someone no longer invited lists only them and does not cancel the event`, `a CANCEL for a live event to attendees and people no longer invited at once is a bad request (400)`, `a CANCEL for an event in the Trash may go to attendees and people no longer invited, cancelled for all` | same two commands | Pass |
| AC1: send errors (synced calendar 409, no attendees 409, unknown account 400, non-attendee REQUEST or invalid address 400, unknown event 404) | `ItipGreenMailTests` "send errors" section (5 tests) | `cd server && ./gradlew integrationTest --tests '*ItipGreenMail*'` | Pass |
| AC1: an incoming REPLY updates the attendee status (Inbox, header sync trigger, case-insensitive address, single part or .ics attachment, series vs occurrence, TZID RECURRENCE-ID) | `ItipGreenMailTests`: `a REPLY mailed by the attendee into the Inbox updates the attendee status after header sync`, `process applies a REPLY directly ...`, `a REPLY as a single text calendar part or an ics attachment is read too`, `a series REPLY updates the master and its overrides, an occurrence REPLY only its override`, `an occurrence REPLY with the RECURRENCE-ID as the cockpit sent it (TZID) updates its override`; `ItipReplyProcessorTests`: `a series REPLY updates the master and its live overrides, not an override in the Trash`, `a calendar part with an unknown charset is read as UTF-8, not dropped`; `ItipMessagesTests` REPLY parsing cases | same two commands | Pass |
| AC1 security/robustness: a REPLY is ignored when the sender is not the attendee, the display name is spoofed, there is no sender, the calendar is synced, the attendee is not invited, the event is unknown/trashed/an override id, the mail is outside the Inbox, the mail is not a REPLY, or a calendar part is broken | `ItipGreenMailTests` (sender, display-name spoof, synced, not invited/unknown/trashed, override UID, Archive, non-REPLY/broken part, multi-attendee REPLY); `ItipReplyProcessorTests`: `a message without a sender is not read`, `a REPLY whose answer is already stored changes nothing` | same two commands | Pass |
| CR-2 (fixer): the processor reads mail only through `MailReadService.inInbox` (Inbox only; moved, deleted-folder, other-role and unknown messages give null) | `MailReadServiceInInboxTests` (4 tests); `ItipReplyProcessorTests`: `a message that is not in an Inbox is not read`; `ItipGreenMailTests`: `a REPLY outside the Inbox is not applied`, `a message that is gone from the cache gives no answers` | same two commands | Pass |
| CR-4 (fixer): a REPLY that hits an optimistic-lock conflict is retried on the reloaded event, at most 3 attempts; other failures are not retried | `ItipReplyProcessorTests`: `a REPLY that loses an optimistic-locking race is applied again on the reloaded event`, `a REPLY that keeps losing the race is given up after three attempts`, `a failure that is no optimistic-locking conflict is not retried` | `cd server && ./gradlew test --tests '*itip*' --tests '*MailReadServiceInInboxTests' jacocoTestReport ktlintCheck` | Pass |
| CR-3 partial (fixer): within one mail only the newest answer per attendee and event counts (SEQUENCE, then DTSTAMP); answers for different events are all applied | `ItipReplyProcessorTests`: `of several answers of one attendee in a message, only the newest is applied`, `answers for different events in one message are each applied, ...`; `ItipMessagesTests`: `answers are ordered by SEQUENCE, then DTSTAMP, unknown values first`, `a missing or unreadable SEQUENCE or DTSTAMP is read as unknown` | `cd server && ./gradlew test --tests '*itip*' --tests '*MailReadServiceInInboxTests' jacocoTestReport ktlintCheck` | Pass |
| CR-3 remainder: ignoring a REPLY that is older than one applied from an earlier mail | none | n/a | Not testable: not implemented by design (assumption recorded; follow-up task `itip-reply-ordering`) |
| New mail triggers checking: only EMAIL/CREATED change events are queued | `ItipReplyProcessorTests`: `only newly created mail messages are queued for checking`; GreenMail header-sync tests (positive path) | both commands | Pass |
| AC2: `external` GreenMail tests | `ItipGreenMailTests` (`@ExternalTest`, 32 tests, real GreenMail SMTP+IMAP, real header sync, Postgres Testcontainer) | `cd server && ./gradlew integrationTest --tests '*ItipGreenMail*'` | Pass (32/32) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*itip*' --tests '*MailReadServiceInInboxTests' jacocoTestReport ktlintCheck` (run as `server/gradlew -p server ...` from the clone root)

Result:

Pass. ItipMessagesTests 26/26, ItipReplyProcessorTests 11/11, MailReadServiceInInboxTests 4/4, 0 skipped, ktlint clean. The first attempt failed only on a ktlint style issue in the new test file; that was fixed and the command passed.

Command:

`cd server && ./gradlew integrationTest --tests '*ItipGreenMail*'`

Result:

Pass. ItipGreenMailTests 32/32, 0 skipped (GreenMail plus the Postgres test container).

## Full test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.calendar.*' --tests 'ch.sonensei.cockpit.mail.*'` (the calendar and mail modules, which hold all code this task touches)

Result:

Pass. 124 test classes, 2335 tests, 0 failures, 0 errors, 1 skipped (the skip is not in a file this run touched).

The full server `./gradlew check` was not run because the parent worker said it takes more than 10 minutes here.

## .taskcheck

Result:

Not run. `.taskcheck` is `sh scripts/check.sh`, and it includes the full server `./gradlew check` (more than 10 minutes here). As agreed with the parent worker, the parent runs it before `done`.

## Bugs found

None. Every new test passed against the current production code.

## Coverage

Status: verified (task-scoped, from fresh runs). Two numbers are given: the configured `jacocoTestReport`, which only reads `test.exec`, and a merged unit+integration report. The merged report is the meaningful task number, because `ItipService` is exercised only by the `external` GreenMail tests that criterion 2 requires.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: derived from the JaCoCo line counters of the merged report. `ItipMessages.kt`, `ItipReplyProcessor.kt` and `ItipService.kt` are new files (status `A`), so every executable line in them is a changed line. In `MailReadService.kt` the changed executable lines are exactly `inInbox` (lines 96-100; the import is not executable). Result: lines 251/264 (95.1%), instructions 2041/2125 (96.0%), branches 213/226 (94.2%) (3 new files plus the `inInbox` method counters).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: itip-invitations; base 35a771019003fe38dfb83cb9726695d905e419fe; code revision 0df4bd143b24c205a78725be8f124872fa610696 (HEAD later d363170a5400f1ac29e96aa5bbd713201a0a1f28, a tasks-only commit with no server diff); commits and base were supplied by the parent and checked with `git log`/`git diff --name-status 35a77101..HEAD -- server/src/main`
- Source/test snapshot identifier (including uncommitted inputs): SHA-256, unchanged from before to after the measured runs. Production: ItipMessages.kt 47dcf3d6083b5a1b4ccd50865ca6b2f7e224af30b0819772389348991a8e0a2a, ItipReplyProcessor.kt f60de7c1834944f9a4425745d8229c22ad0ef7efd98c60cd7d183d5c39f2c386, ItipService.kt a8103788064faf0e2f11d5b6f1ecd2cdec0bf5ebd4647d21db76cae5446fbbff, MailReadService.kt 2f5a94d97b8c4707f71375369b87bc95ac0598092cf58c10ba338c5e2c16f64f. Tests: ItipGreenMailTests.kt b30e7acff6e2ae9d0c97858d44cc33d7fb170f234a489f46d1dd1ec51922bc36, ItipMessagesTests.kt 78102c47ea88f3dd772fbf9e0d0c18a63cae94a21c897ba74ac61d2b4d7d2793, ItipReplyProcessorTests.kt (uncommitted) 3a05b2557260531f213d438d1ff93b50d2ac5558b2291f1464c8ac32a376e31a, MailReadServiceInInboxTests.kt (untracked) b053ef521f48f8f74d2b9db8577a0651a6007e5bff14de9db9b4b72392e3c179
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipMessages.kt`, `.../calendar/itip/ItipReplyProcessor.kt`, `.../calendar/itip/ItipService.kt`, `.../mail/api/MailReadService.kt`. No renames or deletions. Excluded: `tasks/*.md`, `reviews/**` (not code). All 4 files are in the report; none are missing.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (default tool version of Gradle 9.8.1; the build does not pin it), working directory `server/`. (1) `./gradlew test --tests '*itip*' --tests '*MailReadServiceInInboxTests' jacocoTestReport ktlintCheck` (the configured command, filtered to the task's tests). (2) `./gradlew integrationTest --tests '*ItipGreenMail*'` (the jacoco plugin writes `build/jacoco/integrationTest.exec`). (3) `./gradlew -I ../.tmp/itip-coverage.init.gradle.kts itipMergedCoverageReport`: a temporary init script in the Git-ignored `.tmp/` that registers a JacocoReport over the two fresh exec files and the main source set. No project build or coverage configuration was changed.
- Run start/completion time and exit status: unit run 2026-10-26T14:24:53Z (suite start), BUILD SUCCESSFUL, exit 0; integration run suite start 2026-10-26T14:25:16Z, BUILD SUCCESSFUL, exit 0; merged report generated about 14:25:30Z, exit 0; integrity check 14:25:39Z
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` 18d5acd4772239f1254a9408932eaaeca0492b5978c9aaea61ae01187f5671dc; `server/build/jacoco/integrationTest.exec` 93cb3699e8f7cf25fd9eef3185468099b3ceece808e899b5c127343ab0f12645; `server/build/reports/jacoco/test/jacocoTestReport.xml` 33cba98cc59d8b0a0633e573f13e81d415d229b9a82c08cbdf145097a1e5f258; `server/build/reports/jacoco/itipMerged/itipMerged.xml` b2eb2e1152ba4676b97dce2c220afe09d27426f9687381923f0f906f4f771d7b. Copies are in `.tmp/itip-coverage-evidence/`, because the later broader `test` run overwrote `build/jacoco/test.exec`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Both exec files and both XML reports were rewritten by these runs (mtimes 15:24/15:25 local), and `shasum -c` of the snapshot after the runs reported OK for all 8 files.
- Final run after latest test additions and production fixes: yes (no production changes after 0df4bd14; the measured runs came after the last test edit)

Values below use covered/total (percentage) from the merged unit+integration report (JaCoCo counters: Statements = INSTRUCTION, Branches = BRANCH, Functions = METHOD, Lines = LINE).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipMessages.kt` | 603/606 (99.5%) | 62/62 (100.0%) | 25/25 (100.0%) | 74/76 (97.4%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipReplyProcessor.kt` | 580/641 (90.5%) | 61/68 (89.7%) | 15/15 (100.0%) | 78/89 (87.6%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/calendar/itip/ItipService.kt` | 810/830 (97.6%) | 79/84 (94.0%) | 18/21 (85.7%) | 95/95 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt` (whole file) | 72/924 (7.8%) | 11/74 (14.9%) | 2/38 (5.3%) | 13/140 (9.3%) |
| `MailReadService.inInbox` only (the task's change) | 48/48 (100.0%) | 11/12 (91.7%) | 1/1 (100.0%) | 4/4 (100.0%) |
| **Aggregate, 4 whole files (summed counters)** | 2065/3001 (68.8%) | 213/288 (74.0%) | 60/99 (60.6%) | 260/400 (65.0%) |

The whole-file aggregate is pulled down by `MailReadService.kt`. That file is pre-existing (only `inInbox` is new), and this run deliberately executed only the task's tests, not the mail module's other tests. Without it, the 3 new itip files give 1993/2077 instructions (96.0%), 202/214 branches (94.4%), 247/260 lines (95.0%). No files are excluded or unmeasured.

Configured `jacocoTestReport` (unit `test` task only, same selection): ItipMessages 603/606 instr, 62/62 branches, 74/76 lines; ItipReplyProcessor 534/641, 42/68, 76/89; ItipService 0/830, 0/84, 0/95 (it is only exercised by the `external` GreenMail tests, which `test` excludes); MailReadService 72/924, 11/74, 13/140; aggregate 1209/3001 instr (40.3%), 115/288 branches (39.9%), 163/400 lines (40.8%).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ItipReplyProcessor.kt:72-73` (`RejectedExecutionException`, queue full / shut down) | Defensive: drop with a WARN when 1000 messages are queued | Left uncovered. Not reachable without flooding the queue; logging only. |
| `ItipReplyProcessor.kt:80-86` (`safely`: NotFound / MailConnection / Runtime catch and log) | A failing message must not break the background worker | Left uncovered. Logging-only branches, and the `ThreadPoolExecutor` replaces a dead worker anyway; the retry give-up path is covered directly via `process`. |
| `ItipReplyProcessor.kt:146` (occurrence REPLY whose override is in the Trash) | A trashed override is not updated | Partial branch. The series case for a trashed override is now tested; the occurrence case uses the same `!it.isDeleted` filter. Low risk. |
| `ItipReplyProcessor.kt:168-169` (calendar or source row missing) | Defensive (FK-guaranteed) | Left uncovered: not reachable with the schema's foreign keys |
| `ItipReplyProcessor.kt:175` (multipart content that is not a `Multipart`) | Defensive against a broken MIME tree | Left uncovered: JavaMail always gives a `Multipart` for `multipart/*` |
| `ItipReplyProcessor.kt:179, 186-187` (exception edge of `use`, unparsable Content-Type) | Charset fallback | The unknown-charset fallback is tested (`a calendar part with an unknown charset is read as UTF-8, not dropped`); the `ParseException` arm needs a Content-Type that JavaMail already accepted as `text/calendar`, so it is effectively unreachable |
| `ItipMessages.kt:138-139` (`DateTimeException` reading RECURRENCE-ID) | Skip an unreadable RECURRENCE-ID | The `IllegalArgumentException` arm is tested (`a VEVENT with an unreadable RECURRENCE-ID is skipped, ...`); the `DateTimeException` arm is the same behavior for a different parser failure. Low risk. |
| `ItipService.kt:120` (override whose master is trashed) | A REQUEST for an override of a trashed series gives 404, a CANCEL is still allowed | Partial branch. Not reachable through the current API, which trashes the series with its overrides. Low risk. |
| `ItipService.kt:172` (trashed override excluded from a series REQUEST) | A series REQUEST must not carry trashed overrides | Partial branch. The same filter pattern is covered in the processor; low risk. |
| `ItipService.kt:196` (multi-day all-day range in the plain-text part) | Text summary formatting only | Left uncovered: cosmetic text part, not the iTIP object |
| `ItipService.kt:202` (`isAddress` exception arm) | Invalid recipient gives 400 | The invalid-address case is tested in GreenMail (`... or to an invalid address, is a bad request (400)`); the remaining arm is the other way `InternetAddress` can reject |
| `MailReadService.kt:99` (1 of 12 branches) | `folderId != null` after `isLocated` | Not reachable: `isLocated` already implies `folderId != null` (it is a smart-cast guard) |
| `MailReadService.kt` (all other lines) | Pre-existing code, not changed by this task | Out of scope; covered by the mail module's own tests (the broader run passed) |

## Unable to test

- Cross-message REPLY ordering (an older REPLY from an earlier mail must not override a newer one). It is not implemented by design (CR-3 partial, assumption recorded; follow-up task `itip-reply-ordering`). Needs follow-up only if that task is accepted.
- Sending invitations through a REST endpoint or the UI: not in scope (assumption: `ItipService.send` is the server API; follow-up task `itip-api`).
- Real mail clients (Outlook, Gmail, Apple Calendar) rendering the REQUEST/CANCEL: not possible here. GreenMail checks the MIME structure and the iCalendar content, and one test (`a REPLY written by a client from a cockpit REQUEST is read back`) checks the round trip. No follow-up required for this task.

## Summary

Tests added and all validation passed. Seven new tests in `ItipReplyProcessorTests` and four in the new `MailReadServiceInInboxTests` cover the fixer's changes (CR-2 `inInbox`, CR-4 retry scope, CR-3 grouping) and REPLY edge cases. The focused unit tests (41/41), ktlint, the `external` GreenMail suite (32/32) and the calendar+mail unit packages (2335 tests) pass. No production defects were found. Task-scoped coverage on the merged unit+integration report: 96.0% instructions, 94.4% branches and 95.0% lines across the three new itip files; `MailReadService.inInbox` has 100% of its lines covered. `.taskcheck` and the full `./gradlew check` were not run and are left to the parent worker.
