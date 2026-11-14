# Test Report

Date: 2026-11-14

Task: mail-action-settle - Moved, archived or deleted mail must not come back in the source list until the next 5-min sync

Scope: `git diff 6e3d4c64a813c25fd8849684b819166894a71941..d9da02d0895e4d926f1e9727f75094c70cbe0eed` (task commits 18a292d8b, d9da02d08, supplied by the parent worker), branch `main`, plus the uncommitted test change below.

Requirements:
- `tasks/mail-action-settle.md` acceptance criteria 1-3 and 5 (criterion 4, web e2e, is run by the parent)
- Parent instructions (MailActionService settle tests, unit-level race regression)
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage"

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailActionServiceTests.kt`

## Tests added or updated

Count: 5 (added to `MailActionServiceTests`; no existing test changed)

- `a move resyncs the source and the target folder by UID set in the background`
- `a delete outside Trash resyncs the source and Trash by UID set in the background`
- `a delete in Trash resyncs only Trash by UID set in the background`
- `a flag change starts no UID-set resync, and only a plain resync when its message is gone`
- `a move whose cache update gives up after conflicts still resyncs the source by UID set so the row cannot stay`

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC2: after move/archive/delete/spam the source (and move target) is resynced in the background | the 4 new settle tests above; existing `MailFolderResyncTests` `a verified request runs the UID-set sync in the background`, `a plain and a verified request for a pending folder merge into one verified sync, in either order` | `server/gradlew -p server test --tests ...` | Not run: Gradle invocation was refused by the permission system in this session |
| AC2: resync uses vanished detection independent of modseq | existing `MailHeaderSyncServiceTests` `verifyFolder removes cached UIDs missing on the server even when QRESYNC reports nothing vanished`, `verifyFolder of a folder without cached messages reads no UID set` | same | Not run: as above |
| AC1/AC3: race regression (stale row survives a sync) at unit level | existing `MailHeaderSyncServiceTests` `a plain QRESYNC sync keeps a row whose expunge a racing sync already covered by the cached HIGHESTMODSEQ` (documents the race) + `verifyFolder removes cached UIDs ...`; new `a move whose cache update gives up after conflicts still resyncs the source by UID set so the row cannot stay` (cache-conflict candidate) | same | Not run: as above |
| AC1: GreenMail test with latch-controlled interleaving | none | - | Not testable here: no latch hook into a running sync exists without production changes; unit-level regression used instead |
| AC5: GreenMail mail action/sync tests (`MailActionGreenMailTests`, `HeaderSyncGreenMailTests`, `MailIdleGreenMailTests`, `MailIdlePushGreenMailTests`) | existing | - | Not run: Gradle refused |
| AC4: web e2e | - | - | Not run: parent runs it |

## Focused test execution

Command:

`/home/felix/work/my-cockpit-agents/agent-1/server/gradlew -p /home/felix/work/my-cockpit-agents/agent-1/server test --tests 'ch.sonensei.cockpit.mail.api.MailActionServiceTests' --tests 'ch.sonensei.cockpit.mail.api.MailFolderResyncTests' --tests 'ch.sonensei.cockpit.mail.sync.MailHeaderSyncServiceTests'`

Result:

Not run. Gradle could not be started: the permission system answered "This command requires approval" (also with a relative `../server/gradlew`). The new tests are unexecuted and must be run by the parent.

## Full test execution

Command:

`server/gradlew -p server check`

Result:

Not run (same reason).

## .taskcheck

Result:

Not run (same reason).

## Bugs found

No production defect found. One likely test/implementation mismatch, from reading the code (not confirmed by a run):

### Existing MailActionServiceTests expect a plain resync for stale rows of a move

Finding ID:

`TW-1`

Test:

`when no requested message is on the server any more nothing is sent and the cache is left to the next sync` (verifies `resync.request(inbox.id)` exactly 1), `a move with a message gone from its folder moves the present one by its COPYUID and fails the gone one with a resync` (verifies `resync.request(inbox.id)` exactly 1), `one delete of messages gone from Trash and from the Inbox is ok for Trash and NOT_FOUND with a resync for the Inbox` (verifies `resync.request(inbox.id)` exactly 1).

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt:227-240` (`settle`)

Requirement:

AC2 (every move/delete resyncs the source); the previous behavior "stale rows start a resync" must still hold.

Expected behavior:

These tests expect `resync.request(source)` for stale UIDs of a move/delete-to-Trash.

Actual behavior:

For `Operation.Move`, `settle` calls only `resync.requestVerified(source)` and `requestVerified(target)`, never `request(source)`, even when `stale` is non-empty. With `MailFolderResync` mocked, `verify(exactly = 1) { resync.request(inbox.id) }` should fail in these three tests. Functionally the verified resync is a superset of the plain one (it runs the same incremental sync plus the UID-set comparison), so the behavior looks correct. The existing assertions are the likely cause. Note also that the move target gets `requestVerified` even when every UID was missing and nothing was moved; this is harmless but not needed.

Severity:

Medium (`./gradlew check` and `.taskcheck` would fail, if confirmed)

Fix required:

`Yes — the parent worker must run the suite. If these tests fail, decide between updating the three assertions to requestVerified (the stricter call, intended by AC2) or also calling request(source) for stale moves.`

## Coverage

Status: unavailable. The coverage command (`server/gradlew ... test jacocoTestReport`, per docs/ARCHITECTURE.md "Coverage") could not be started because Gradle invocation was refused by the permission system in this session. No fresh partial output exists, and no earlier or repository-wide numbers are substituted.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-action-settle; base 6e3d4c64a813c25fd8849684b819166894a71941; HEAD d9da02d0895e4d926f1e9727f75094c70cbe0eed; commits 18a292d8b, d9da02d08 (from the parent)
- Source/test snapshot identifier (including uncommitted inputs): HEAD d9da02d08 + `MailActionServiceTests.kt` sha256 c441752b270b5e157b7f2f1ca72697837bbc285a21b534d033cc839275fbdb7d
- Exact included files; renamed/deleted/excluded files and reasons: `server/.../mail/api/MailActionService.kt`, `server/.../mail/api/MailFolderResync.kt`, `server/.../mail/sync/MailHeaderSyncService.kt` (all unmeasured); `web/e2e/mail-list/list.spec.ts` excluded (test file)
- Provider/version, command, working directory, and test selection: not run
- Run start/completion time and exit status: not run (refused before starting)
- Generated report paths and report hashes/run identifier: none
- Evidence outputs are fresh and inputs stayed unchanged: not applicable
- Final run after latest test additions and production fixes: no, Gradle could not be run

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionService.kt` | N/A (unmeasured) | N/A (unmeasured) | N/A (unmeasured) | N/A (unmeasured) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailFolderResync.kt` | N/A (unmeasured) | N/A (unmeasured) | N/A (unmeasured) | N/A (unmeasured) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sync/MailHeaderSyncService.kt` | N/A (unmeasured) | N/A (unmeasured) | N/A (unmeasured) | N/A (unmeasured) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailActionService.kt:227-240` (settle, all three branches) | AC2 | 4 new unit tests target every branch; unmeasured |
| `MailFolderResync.kt` enqueue/run verify path | AC2 | covered by the parent's 2 new `MailFolderResyncTests`; unmeasured |
| `MailHeaderSyncService.kt:349` (`gone` with `verifyUids && lowest != null`, both sides) | AC2/AC3 | covered by the parent's `verifyFolder ...` tests; the `full` path (UIDVALIDITY change) ignores `verifyUids`, which is fine since a full sync replaces the cache |

Follow-up: the parent runs `server/gradlew -p server test jacocoTestReport` (or the ARCHITECTURE.md coverage command) and records the numbers.

## Unable to test

- Execution of all server tests and coverage: Gradle refused by the permission system here. Follow-up needed by the parent.
- GreenMail latch-controlled race (AC1 option): would need a hook inside `syncFolder` to pause between reading the remote state and storing it; none exists, and adding one is a production change. The unit-level regression in `MailHeaderSyncServiceTests` covers the race's mechanism instead.
- Web e2e (AC4): left to the parent as instructed.

## Summary

Meaningful task behavior remains untested and requires follow-up: 5 tests were added but could not be run (Gradle refused in this session), coverage is unmeasured, and three existing MailActionServiceTests likely fail against the new `settle` (TW-1). The parent must run the server suite before completion.
