# Code Review

Date: 2026-10-26

Task: fix-flaky-backup-sse-test - BackupEventsStreamTests flakes under full-suite load (step was 'bookmarks: data', expected 'controllable: data')

Scope: task commit 5de93ecb1 on top of base f0e674df6817710eb3098f35eefd7185c72661cf (single file: server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupEventsStreamTests.kt)

Requirements:
- tasks/fix-flaky-backup-sse-test.md acceptance criteria
- AGENTS.md project notes and conventions
- Production behavior the test relies on: BackupService.export() and BackupJobService.run()/update()/publish()

Git range:
- Branch: main
- HEAD: 5de93ecb1
- Base/start: f0e674df6817710eb3098f35eefd7185c72661cf
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Analysis

Ordering guarantees the new test relies on, confirmed in the production code:

- BackupService.export() calls `step(name)` (progress with `done++`) before each contributor's `export`, so while
  the controllable contributor is blocked on its gate, the job holds `step = "controllable: data"` and
  `stepsDone = k`, where k is that contributor's position among the data steps. Its UPDATED event is the
  (k + 1)th UPDATED event, which matches the comment in the test.
- BackupJobService.update() writes the job map before it queues the publish, and every event goes through
  the single-threaded `backup-events` executor, so SSE events arrive in step order and the job state is
  never behind an event the test has already read.
- While the gate is held, the job state is frozen. The condition `step == GATED_STEP && stepsDone + 1 == updates`
  is therefore true only for the gated step's own event. If the test reads an earlier contributor's event after
  the job has already reached the gated step, `updates` is still smaller than `stepsDone + 1`, so the loop
  correctly keeps reading. This holds for any contributor order (gated contributor first, last or in between),
  which fixes the reported failure (a `bookmarks` step announced first).
- Remaining events after the gate: export emits `stepsTotal` step events plus one "done" event, and run() emits
  one COMPLETED update. That makes `stepsTotal + 2` UPDATED events in total, so `stepsTotal + 2 - updates`
  remaining is correct. It is read after `awaitFinished`, when `stepsTotal` is stable. `awaitLine` waits for
  events that have not been published yet, so the asynchronous `backup-events` executor does not cause a race.
- Kotlin do-while may read a `val` declared in its body, so `gated` in the `while` condition is valid.
- Every read is still bounded: `awaitLine` has a 5 s timeout and the gate's `await` has a 10 s timeout, so a
  regression fails the test instead of hanging it. `cleanUp` still releases the gate and waits for the job.

No remaining order dependency or race was found in the changed test. The old hard-coded count (`repeat(4)`,
which assumed a single contributor) is also gone. That count would have been wrong as soon as more contributors
were registered.

Criterion 1 (no dependency on contributor order): met, by the analysis above.
Criterion 2 (focused test and full `./gradlew check` pass): the reviewer cannot run Gradle. The parent reported
`./gradlew ktlintFormat test --tests '*BackupEventsStreamTests'` -> 1 test, 0 failures. The full check was still
running at review time. The parent must record its result before verifying this criterion.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
