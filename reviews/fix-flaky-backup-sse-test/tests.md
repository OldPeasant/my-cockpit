# Test Report

Date: 2026-10-26

Task: fix-flaky-backup-sse-test - BackupEventsStreamTests flakes under full-suite load (step was 'bookmarks: data', expected 'controllable: data')

Scope: f0e674df6817710eb3098f35eefd7185c72661cf..5de93ecb1e7c40455973bab7f5494afa4d7bd123 (task commit 5de93ecb1, supplied by the parent worker). The only changed file is `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupEventsStreamTests.kt` (15 insertions, 5 deletions). Working tree clean at review time.

Requirements:
- `tasks/fix-flaky-backup-sse-test.md` acceptance criteria
- Production semantics read from `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupJobService.kt` and `BackupService.kt` (export progress steps)
- AGENTS.md and the parent worker's instructions

## Test files changed

- None by the test-writer. The task commit itself changes `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupEventsStreamTests.kt`.

## Tests added or updated

Count: 0 (by the test-writer). The task's change is itself a test rewrite; no further tests were needed.

## Soundness review of the rewritten test

Production behavior (BackupService.export, BackupJobService.run/update):
- `progress` is called before each step with `done` = 0-based step index, then once with step "done" and `done == total`; each call updates the job map and then publishes one UPDATED event (asynchronously, in order, on the `backup-events` thread). Completion publishes one more UPDATED event.
- So one backup yields exactly 1 CREATED + `stepsTotal + 2` UPDATED events, and step n is announced by the (n + 1)th UPDATED event, as the test's comment states.

Loop `do { awaitUpdate; updates++; gated = step == GATED_STEP && stepsDone + 1 == updates } while (!gated)`:
- The job map is updated before the event is published, so when an earlier event is read the job may already be at the gated step; then `stepsDone + 1 > updates` and the loop correctly keeps reading. It stops exactly on the gated step's own event, regardless of how many contributors are ordered before `controllable`. This removes the contributor-order dependency (criterion 1).
- The gated contributor blocks (up to 10 s) before any later step, so no further event can arrive before the loop exits; the loop cannot overshoot.
- It cannot spin forever: each `awaitUpdate` times out after 5 s (`Future.get`), failing the test instead of hanging `.taskcheck`.
- The key property is preserved: after the gated step's event has been delivered over SSE, the job is still RUNNING at `controllable: data`, i.e. progress reaches the stream before the export finishes.

Drain `repeat(stepsTotal + 2 - updates)`:
- `updates` is at most `stepsTotal` (the gated data step precedes at least the readable steps and the manifest), so the count is always at least 2 and equals the exact number of remaining UPDATED events (remaining steps, "done", COMPLETED). With the previous fixed contributor order this reproduces the old `repeat(4)`; it now also adapts to the number of registered contributors.
- Each drained line is still asserted to be an UPDATED event of this job, so the assertion strength of the original test is kept.

No defects or weaknesses found. Minor observation (not a finding): the test still depends on the `ControllableBackupContributor` gate's 10 s timeout and 5 s per-line timeouts; under extreme load these are unchanged from before and not part of this task.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| The test waits for the gated contributor's step (no longer depends on contributor order) | `progress of a running backup reaches the SSE stream before the export finishes` | Code review against BackupService/BackupJobService semantics (above); run by parent: `cd server && ./gradlew ktlintFormat test --tests '*BackupEventsStreamTests'` | Pass (parent run: 1 test, 0 failures); reasoning verified by test-writer |
| `./gradlew test --tests '*BackupEventsStreamTests'` passes | same | `cd server && ./gradlew ktlintFormat test --tests '*BackupEventsStreamTests'` (run by the parent worker) | Pass (1 test, 0 failures, reported by parent) |
| Full `./gradlew check` passes | whole server suite | `cd server && ./gradlew check` | Not run by test-writer: sub-agents cannot run Gradle here; the parent is running it and must record its result |

## Focused test execution

Command:

`cd server && ./gradlew ktlintFormat test --tests '*BackupEventsStreamTests'` (executed by the parent worker; the test-writer cannot run Gradle in this environment)

Result:

Pass (1 test, 0 failures, as reported by the parent)

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run by the test-writer (Gradle unavailable to sub-agents); in progress in the parent worker, which must record the outcome.

## .taskcheck

Result:

Not run by the test-writer (it runs Gradle); the parent worker runs it before `done`.

## Bugs found

None.

## Coverage

Status: not applicable - the task changes test code only; no production file is in the task coverage scope.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: fix-flaky-backup-sse-test; base f0e674df6817710eb3098f35eefd7185c72661cf; HEAD 5de93ecb1e7c40455973bab7f5494afa4d7bd123; task commit 5de93ecb1 supplied by the parent and confirmed with `git diff --stat f0e674df6..HEAD` (one test file only).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 5de93ecb1, clean working tree; sha256 of BackupEventsStreamTests.kt f4a9046d3db16a17802c1f4a537296efc62c6c06f53ff51aca6d436a9a9be654.
- Exact included files; renamed/deleted/excluded files and reasons: no production files included; `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupEventsStreamTests.kt` excluded as a test file.
- Provider/version, command, working directory, and test selection: none run (nothing to measure).
- Run start/completion time and exit status: N/A.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: N/A (no coverage output used).
- Final run after latest test additions and production fixes: N/A (no production changes, no tests added by the test-writer).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files changed) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| N/A | N/A | No production code in scope |

## Unable to test

- Running the test and the full `./gradlew check` myself: Gradle cannot be run by sub-agents in this environment; the parent ran the focused test and is running the full check. Follow-up: parent records the full `check` result.
- Reproducing the original flake deterministically would require forcing a different contributor order; the reasoning above shows the new loop is order-independent, so no extra test was added.

## Summary

No additional tests were necessary for the reviewed scope. The rewritten test is sound: it is order-independent, cannot hang, and keeps the original assertions; no production defects found.
