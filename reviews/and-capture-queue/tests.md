# Test Report

Date: 2026-10-21

Task: and-capture-queue - Android offline capture queue

Scope: base e1aa2ae18e6e82e2a6e54e94884fd064b9e1d889 .. HEAD f0c029fad6057c51590825e50431f46b25f6a186 on `main` (task commits 0e9097b6..f0c029fa, as given by the parent worker; `git diff --stat` of the range shows only the task's capture/queue files, the Room schema JSON and `reviews/and-capture-queue/`). Plus the uncommitted test changes of this run listed below. No uncommitted production changes. Focus of this run: the latest commit f0c029fa (cleanup under NonCancellable after the row delete, schedule() failures swallowed after commit, `MAX_TEXT_LENGTH` check after pinning).

Requirements:
- `tasks/and-capture-queue.md` acceptance criteria #1-#4 and the recorded assumptions (Notes)
- `SPEC.md` §9.3 Offline capture (Android)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- Parent instructions: review P3s CR-1 (schedule failure after commit), CR-3 (text limit after pinning), the KDoc contract of `CaptureQueue.capture`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/CaptureQueueContractTest.kt` (new, untracked)
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/CaptureQueueConsistencyTest.kt` (one test added, nothing removed or changed)

## Tests added or updated

Count: 10 added (9 in `CaptureQueueContractTest`, 1 in `CaptureQueueConsistencyTest`). No existing test changed.

`CaptureQueueContractTest`:
- `capture returns the stored id without an exception when scheduling the job fails` (IllegalStateException, RuntimeException, IOException from `schedule()`; returned id matches the stored row, copies kept)
- `after a failed schedule the next capture schedules the job again and both stay queued`
- `a cancellation thrown by the scheduler still propagates and the stored capture keeps its copies`
- `a caller cancelled while the job is being scheduled keeps the capture queued and the schedule finishes`
- `text of exactly the maximum length is queued, one character more is refused` (100000 accepted, 100001 refused with an attachment: nothing copied/queued/scheduled)
- `pinning today that pushes the text over the limit refuses it before anything is copied or queued` (99996 chars with `!today` -> 100001 after pinning, two attachments)
- `pinning that lands exactly on the limit is queued with the pinned date` (`!today` +5, `!tomorrow` +2 -> exactly 100000)
- `pinning tomorrow one character over the limit is refused`
- `a today token outside the title line is not pinned, so a text at the limit stays accepted`

`CaptureQueueConsistencyTest`:
- `a sent capture whose row cannot be removed keeps its copies and is cleaned up by the next run` (covers the false branch of the new cleanup in `CaptureUploader.sendAll`: a failing `delete` must not remove copies that a queued row still references; the next run re-sends idempotently without re-uploading)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Room `pending_capture` (UUIDv7 id, text, notes, attachment copies, createdAt); capture always writes locally first | `CaptureQueueTest` (6), `AttachmentStoreTest` (10), `UuidV7Test` (4), `RelativeDueDatesTest` (5), new `CaptureQueueContractTest` (9) | `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.queue.*'` | Pass |
| AC1 / CR-1: once stored, capture returns its id even if scheduling fails; cancellation still propagates | the 4 scheduling tests in `CaptureQueueContractTest`, `CaptureQueueConsistencyTest.a capture stays queued with its copies when scheduling the job fails` | same | Pass |
| CR-3: text over `MAX_TEXT_LENGTH` after pinning is refused, nothing copied/queued | the 5 limit tests in `CaptureQueueContractTest` | same | Pass |
| AC2 WorkManager job (network constraint) uploads attachments then calls `/capture` with the id; retries with backoff; duplicate id = success | `CaptureUploaderTest` (7), `CaptureUploaderFailureTest` (12), `CaptureSyncTest` (5), `CaptureQueueConsistencyTest` (5) | same | Pass |
| AC3 Pending count + last error as a Flow | `CaptureQueueTest.status flow counts pending captures and reports the latest error`, `CaptureUploaderFailureTest.the status reports the most recent error ...` | same | Pass |
| AC4 the focused suite passes, covering offline write, upload ordering, idempotent retry (MockWebServer), attachment cleanup | whole package, 63 tests in 9 classes | same | Pass (63 tests, 0 failures, 0 errors, 0 skipped) |
| AC4 "Robolectric/Compose UI tests for screens" | Not testable in this task: the task has no screens (touches only `capture/queue`) | - | Not testable |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.queue.*'` (2026-10-21T00:37:08Z - 00:38:10Z, exit 0)

Result:

Pass. JUnit XML: AttachmentStoreTest 10, CaptureQueueConsistencyTest 5, CaptureQueueContractTest 9, CaptureQueueTest 6, CaptureSyncTest 5, CaptureUploaderFailureTest 12, CaptureUploaderTest 7, RelativeDueDatesTest 5, UuidV7Test 4 = 63 tests, 0 failures/errors/skipped. ktlint clean (new test file formatted with `./gradlew ktlintTestSourceSetFormat`, which changed only my new file).

## Full test execution

Command:

`sh scripts/check.sh` (lint + unit tests of server, web and android; 2026-10-21T00:38:21Z - 00:42:56Z)

Result:

Pass ("== all checks passed", exit 0). It took about 4.5 min, above the 2 min warning in AGENTS.md (whole repository, not caused by this task's tests, which run in seconds).

## .taskcheck

Result:

Pass (`.taskcheck` is `sh scripts/check.sh`, run above).

## Bugs found

None. All new tests pass against f0c029fa. The scheduling and limit tests would fail without the f0c029fa changes (capture would throw on a schedule failure; over-long text would be queued).

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to the whole-file line coverage below, because every scoped file was added by the task (the range diff has only additions for them); no separate intersection was computed.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: and-capture-queue; base e1aa2ae18e6e82e2a6e54e94884fd064b9e1d889; HEAD f0c029fad6057c51590825e50431f46b25f6a186; commits 0e9097b6..f0c029fa and scope from the parent, checked with `git diff --name-only e1aa2ae1..HEAD -- android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 of the concatenation of `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/*.kt` + `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/*.kt` = `bbd46b516e6a541b62c98c1fd812ecda857e2a884a243e27c8f6d40a5110c694`, identical before and after the coverage run. `CaptureQueueContractTest.kt` sha256 `d9999f784e29ab21b9f15ea243385b8d1a1282934c542436b5e1c4477c85bf4b`, `CaptureQueueConsistencyTest.kt` sha256 `5e72aa109d94599e9188579088c3fd0f33d2b3dca1d51ddd9509968f3d9541ef`.
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/{CaptureQueue,CaptureQueueDatabase,CaptureQueueModule,CaptureSync,CaptureUploader,RelativeDueDates,UuidV7}.kt`. Excluded: the Room schema JSON `android/app/schemas/.../1.json` (generated, not executable). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP 9.4.1 `enableUnitTestCoverage` (JaCoCo version not pinned in the build file, AGP default); `./gradlew createDebugUnitTestCoverageReport` in `android/`; all Android debug unit tests.
- Run start/completion time and exit status: report task 2026-10-21T00:43:13Z - 00:43:32Z, exit 0. `testDebugUnitTest` was UP-TO-DATE: its exec data is from the `.taskcheck` run on the same snapshot (JaCoCo session `dell26-c25ec945`, start 00:41:00Z, dump 00:42:52Z, after the last test edit at about 00:37Z).
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (deleted before the run, regenerated), sha256 `8bade627b7326c9238e5bbf5483dc470e5ee07511142baa490a287057eeeeb6a`, session id above.
- Evidence outputs are fresh and inputs stayed unchanged: yes (report dir removed first, snapshot hash unchanged, exec session after the last test edit).
- Final run after latest test additions and production fixes: yes (no production change after f0c029fa).

Values below use covered/total (percentage); zero-denominator metrics are N/A. Statements = JaCoCo INSTRUCTION.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `capture/queue/CaptureQueue.kt` | 845/948 (89.1%) | 46/48 (95.8%) | 22/26 (84.6%) | 91/94 (96.8%) |
| `capture/queue/CaptureQueueDatabase.kt` | 265/265 (100.0%) | N/A | 27/27 (100.0%) | 31/31 (100.0%) |
| `capture/queue/CaptureQueueModule.kt` | 0/29 (0.0%) | N/A | 0/5 (0.0%) | 0/5 (0.0%) |
| `capture/queue/CaptureSync.kt` | 117/130 (90.0%) | 4/4 (100.0%) | 5/7 (71.4%) | 26/29 (89.7%) |
| `capture/queue/CaptureUploader.kt` | 736/749 (98.3%) | 43/44 (97.7%) | 14/15 (93.3%) | 75/76 (98.7%) |
| `capture/queue/RelativeDueDates.kt` | 180/180 (100.0%) | 22/22 (100.0%) | 3/3 (100.0%) | 24/24 (100.0%) |
| `capture/queue/UuidV7.kt` | 108/108 (100.0%) | 4/4 (100.0%) | 5/5 (100.0%) | 17/17 (100.0%) |
| **Aggregate (summed counters, all 7 files, none missing)** | 2251/2409 (93.4%) | 119/122 (97.5%) | 76/88 (86.4%) | 264/276 (95.7%) |

Before the new `CaptureQueueConsistencyTest` case, `CaptureUploader.kt` was 699/749 instructions and 42/44 branches (line 63 cleanup branch uncovered).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CaptureUploader.kt:63` (false branch: attachments still present after the delete) | AC1/AC4 cleanup: copies of a capture still in the queue must not be deleted | Covered now by `a sent capture whose row cannot be removed keeps its copies and is cleaned up by the next run` |
| `CaptureQueueModule.kt:16-39` | Hilt `@Provides` for the DAO, database and Retrofit APIs | Not covered: DI glue, needs a Hilt test application; non-material |
| `CaptureSync.kt:73-75` | Worker's public constructor resolving the uploader through the Hilt entry point | Not covered: DI glue; `doWork` itself is tested with an injected uploader |
| `CaptureQueue.kt:55`, `:157`; `CaptureUploader.kt:49` | `@Inject` constructors with system clock / app context | Not covered: DI glue; the internal constructors they delegate to are tested |
| `CaptureQueue.kt:127-128` (1 branch, finally block) | Copy cleanup on failure | Behavior covered (unreadable attachment, failed insert, cancellation while copying, limit rejection); the missing branch/instructions are the compiler's duplicated finally path |
| `CaptureQueue.kt:168` (1 branch), `:169`, `:183` | `AttachmentStore.copy` folder creation / stream close / cursor close paths | Behavior covered (`an unusable storage folder queues nothing`, cursor fallbacks); the rest are bytecode exception paths of `use`; non-material |
| `CaptureQueue.kt:26-27` | `CaptureQueueStatus` data class members | Partially generated code; non-material |
| `CaptureUploader.kt:125` (1 branch: code < 200) | HTTP 1xx final status | Not reachable: OkHttp never returns a 1xx final response; non-material |

## Unable to test

- AC4 "Robolectric/Compose UI tests for screens": the task has no screens; nothing to test here. No follow-up inside this task.
- Real WorkManager network-constraint behavior on a device (job really waiting for connectivity) is only checked through the enqueued `WorkRequest` constraints in `CaptureSyncTest`, not on a device. No device/emulator was used.
- Hilt wiring (`CaptureQueueModule`, worker entry point) is not exercised by unit tests; it would need an instrumented or Hilt test. Optional follow-up.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found.
