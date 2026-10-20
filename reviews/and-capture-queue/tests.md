# Test Report

Date: 2026-10-21

Task: and-capture-queue - Android offline capture queue

Scope: base 9a9b449fa95b41c2ae399b7d4923d3601fb57f10 .. HEAD b3fcc20ea0db3deda25fb4d520edb517bc5694d9 on `main`; task commits 3f9f2f44 (implementation) and b3fcc20e (tests), as given by the parent worker and confirmed with `git log`/`git show --stat`. Plus uncommitted test changes from this run (listed below). No uncommitted production changes.

Requirements:
- `tasks/and-capture-queue.md` acceptance criteria and recorded assumptions #1-#4 (`python tasks.py assumptions`)
- `SPEC.md` §9.3 Offline capture (Android)
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`

## Test files changed

- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/CaptureQueueTestSupport.kt` (harness takes an optional `clock`; new `TickingClock`)
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/CaptureUploaderFailureTest.kt` (new)
- `android/app/src/test/java/ch/sonensei/cockpit/capture/queue/AttachmentStoreTest.kt` (new)

No existing test was changed, weakened, skipped or removed.

## Tests added or updated

Count: 18 new tests (10 in `CaptureUploaderFailureTest`, 8 in `AttachmentStoreTest`); 1 test-support file extended. Package total: 33 tests (was 15).

## Requirement coverage

Command for all rows: `cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.queue.*' createDebugUnitTestCoverageReport` (Robolectric + MockWebServer).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Room `pending_capture` (UUIDv7 id, text, notes, attachment copies in app storage, createdAt); capture always writes locally first | `CaptureQueueTest`: `capture is written locally with attachment copies before any network call`, `an explicit name overrides...`, `an unreadable attachment queues nothing...`; `AttachmentStoreTest`: `a content uri is copied with the provider's display name and mime type`, `without provider metadata the name falls back...`, `a blank display name, an empty or missing cursor or a failing query fall back...`, `a queued content attachment keeps the provider name and mime`, `a content uri that cannot be opened queues nothing`, `a failing database write removes the copies and schedules nothing`, `an unusable storage folder queues nothing`, `deleting copies of an unknown capture is fine`; `UuidV7Test` (2) | above | Pass |
| AC2: WorkManager job (network constraint) uploads attachments then calls `/capture` with the id | `CaptureUploaderTest`: `attachments are uploaded in order before the capture...`; `CaptureUploaderFailureTest`: `captures are sent oldest first`; `CaptureSyncTest` (2) | above | Pass |
| AC2: retries with backoff (incl. failure mid-attachments, 401/408/429/5xx retry, other 4xx rejected per assumption #2) | `CaptureUploaderFailureTest`: `a failure after the first of two uploads resumes with the second one on retry`, `an unreachable server during an upload asks for a retry`, `a too large attachment rejects the capture without a capture call and the next one is still sent`, `401 on the capture call retries as not logged in and stops before later captures`, `401 on an upload retries as not logged in`, `408, 429 and 5xx are retried, other 4xx are rejected`, `an upload answer without a file id is retried as a server error`, `a capture whose attachment copy vanished is rejected without any request`; `CaptureUploaderTest`: `an unreachable server...`, `a rejected capture is kept...`, `nothing is sent while logged out`; `CaptureSyncTest`: `the job succeeds when the queue is sent and retries otherwise` | above | Pass |
| AC2: duplicate id treated as success (200 repeat, 409) | `CaptureUploaderTest`: `a retry after a failed capture call does not upload the attachments again`, `a duplicate id the server already trashed counts as sent` | above | Pass |
| AC2: backoff policy (EXPONENTIAL 30 s) actually applied by WorkManager | `CaptureSyncTest`: `scheduling enqueues one unique job that needs a network` checks the network constraint only | above | Pass for the constraint; backoff criteria not asserted (see Unable to test) |
| AC3: pending count + last error exposed as a Flow (rejected captures counted; most recent error wins; falls back to older rejection) | `CaptureQueueTest`: `status flow counts pending captures and reports the latest error`; `CaptureUploaderFailureTest`: `the status reports the most recent error and falls back to an older rejection once the newer capture is sent`, `a too large attachment rejects...`, `408, 429 and 5xx are retried...` (4 rejected captures counted) | above | Pass |
| AC4: unit tests cover offline write, upload ordering, idempotent retry (MockWebServer), attachment cleanup | all of the above; cleanup: `a sent capture is removed with its attachment copies`, `a failure after the first of two uploads...` (folder removed after send), `a content uri that cannot be opened queues nothing`, `a failing database write removes the copies...` | above | Pass |

## Focused test execution

Command:

`cd android && ./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.queue.*' createDebugUnitTestCoverageReport`

Result:

Pass - 33 tests, 0 failures, 0 skipped (JUnit XML: AttachmentStoreTest 8, CaptureQueueTest 4, CaptureSyncTest 2, CaptureUploaderFailureTest 10, CaptureUploaderTest 7, UuidV7Test 2). Run 2026-10-21T01:20:45+02:00 to ~01:21:26+02:00, exit 0.

During development two new tests failed because of test mistakes, both fixed in the tests: OkHttp itself repeats a request once after HTTP 408 (the test now enqueues two 408 answers), and closing an in-memory Room database did not make the insert fail (the test now uses a DAO stub that throws `SQLiteFullException`).

## Full test execution

Command:

`sh scripts/check.sh` (= `.taskcheck`: server `./gradlew check`, web `npm run check`, android `ktlintCheck testDebugUnitTest lintDebug`)

Result:

Pass (exit 0, "== all checks passed"), 2026-10-21 ~01:22+02:00, on the working tree with the new tests.

## .taskcheck

Result:

Pass (same run as above).

## Bugs found

No production defects found. All new tests pass against the implementation at b3fcc20e.

Observation (not a failing test, no fix demanded): `sendableCaptures()` orders by `createdAtEpochMs, id`, and `UuidV7.generate` uses random bits below the millisecond with no monotonic counter. Two captures created in the same millisecond can therefore be sent in either order, although the KDoc of `UuidV7` says "ids of later captures sort later" and `CaptureQueueDao.sendable()` says "so the Inbox gets them in capture order". SPEC.md §9.3 does not require an order, and same-millisecond user captures are unlikely; the parent may decide whether the KDoc or the code should change. `captures are sent oldest first` covers distinct timestamps only.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (all six files are new in this task, so whole-file coverage equals changed-file coverage here; no separate changed-line intersection was computed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-capture-queue`; base 9a9b449fa95b41c2ae399b7d4923d3601fb57f10; HEAD b3fcc20ea0db3deda25fb4d520edb517bc5694d9; task commits 3f9f2f44 + b3fcc20e from the parent, scope from `git diff --name-only 9a9b449f..HEAD -- android/app/src/main android/app/schemas`, matching the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD b3fcc20e (production unchanged, `git diff --quiet HEAD` on scoped files) plus test files (sha256): `AttachmentStoreTest.kt` 29cc79da262bae2d00556de62736c1c4adba18ecf5bd5274ffcff5330eda220e, `CaptureQueueTestSupport.kt` ebfa57bc2468d947965cce22ee843eb84bcf3b3ddf8645cbe79a936af2ec8a2f, `CaptureUploaderFailureTest.kt` fab2709bbb57b016cf9ae9ff94e00217a43a91315e73d6726523afe7227d04bc; unchanged committed tests: `CaptureQueueTest.kt` 7631a32e..., `CaptureSyncTest.kt` 000eb1fa..., `CaptureUploaderTest.kt` 5dbae06e..., `UuidV7Test.kt` 488b7bc9....
- Exact included files: `android/app/src/main/java/ch/sonensei/cockpit/capture/queue/{CaptureQueue,CaptureQueueDatabase,CaptureQueueModule,CaptureSync,CaptureUploader,UuidV7}.kt`. Excluded: `android/app/schemas/ch.sonensei.cockpit.capture.queue.CaptureQueueDatabase/1.json` (generated Room schema, not executable); KSP/Hilt generated classes in the same package (`CaptureQueueDao_Impl.kt`, `CaptureQueueDatabase_Impl.kt`, `*_Factory.java`) are generated, not task source. No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via AGP `enableUnitTestCoverage` (`createDebugUnitTestCoverageReport`), working dir `android/`, `./gradlew ktlintFormat testDebugUnitTest --tests 'ch.sonensei.cockpit.capture.queue.*' createDebugUnitTestCoverageReport`; only the capture.queue tests ran.
- Run start/completion time and exit status: 2026-10-21T01:20:45+02:00 to 01:21:26+02:00 (report mtime), exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml`, sha256 baef0c31b8802757c4bf30a2cfeadac5de464a6786ebbe2ce3cd55c9551177d0.
- Evidence outputs are fresh and inputs stayed unchanged: yes - report and JUnit XML rewritten during this run (test sources had changed, so Gradle re-ran the tests); no source/test edits during or after the run.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. "Statements" = JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `capture/queue/CaptureQueue.kt` | 697/748 (93.2%) | 31/32 (96.9%) | 19/23 (82.6%) | 78/80 (97.5%) |
| `capture/queue/CaptureUploader.kt` | 566/579 (97.8%) | 39/40 (97.5%) | 10/11 (90.9%) | 60/61 (98.4%) |
| `capture/queue/CaptureQueueDatabase.kt` | 265/265 (100%) | N/A (0/0) | 27/27 (100%) | 31/31 (100%) |
| `capture/queue/CaptureSync.kt` | 68/81 (84.0%) | 2/2 (100%) | 4/6 (66.7%) | 17/20 (85.0%) |
| `capture/queue/CaptureQueueModule.kt` | 0/29 (0.0%) | N/A (0/0) | 0/5 (0.0%) | 0/5 (0.0%) |
| `capture/queue/UuidV7.kt` | 36/36 (100%) | N/A (0/0) | 2/2 (100%) | 5/5 (100%) |
| **Aggregate (6 files, summed counters)** | 1632/1738 (93.9%) | 72/74 (97.3%) | 62/74 (83.8%) | 191/202 (94.6%) |

All six scoped files are measured; none missing. Excluded: the Room schema JSON and generated KSP/Hilt classes (see above). No baseline was measured with only the original 15 tests; an intermediate run (16 of the 18 new tests) showed CaptureQueue.kt at 77/80 lines and 29/32 branches, and the last two tests closed the mkdirs-failure and null-cursor branches. No coverage threshold is defined (ARCHITECTURE.md: coverage informs, not a gate).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CaptureQueueModule.kt:16,30,33,36,39` | Hilt module: Room builder and Retrofit API bindings | Justified gap: DI glue, no Hilt test application in the unit-test setup; exercised only when the app runs (device check). |
| `CaptureQueue.kt:49`, `:121`; `CaptureUploader.kt:46` | `@Inject` constructors that pass `Clock.systemUTC()` / `context.filesDir` | Justified gap: Hilt-only constructors; tests use the internal constructors with identical logic. |
| `CaptureSync.kt:58-60` | Worker's default constructor fetching the uploader via `EntryPointAccessors` | Justified gap: needs a Hilt application; worker logic itself tested via the injectable constructor. |
| `CaptureQueue.kt:132` (`openInputStream` returns null) | Provider returning a null stream | Justified gap: Robolectric's resolver throws `FileNotFoundException` instead (covered by `a content uri that cannot be opened queues nothing`); the null branch is defensive. |
| `CaptureQueue.kt:133` (partial), `:147` (partial) | Exception paths inside Kotlin `use {}` / cursor `?.use` | Non-material: compiler-generated close/exception bytecode; the behaviors (failing open, null/empty/failing cursor) are tested. |
| `CaptureQueue.kt:22-23` (partial) | `CaptureQueueStatus` data-class members | Non-material generated accessors. |
| `CaptureUploader.kt:102` (1 branch) | `code in 200..299` with code < 200 | Not reachable: Retrofit never returns a final 1xx response. |

## Unable to test

- WorkManager backoff criteria (EXPONENTIAL, 30 s) are not asserted: `WorkInfo` does not expose backoff policy in the test API used here; the real retry timing on a device needs the on-device check (h-android-device). Follow-up optional.
- Hilt wiring (`CaptureQueueModule`, `CaptureQueueEntryPoint`, `@Inject` constructors) and the real `capture_queue.db` file on disk: no Hilt test application in unit tests; verified only by an app build (`lintDebug`/compile in `.taskcheck`) and a device run. Follow-up: on-device check.
- No UI exists in this task, so no Compose UI tests apply.

## Summary

Tests added and all validation passed. Tests and task-scoped coverage are sufficient; no production defects found (one low-risk ordering observation for the parent to decide on).
