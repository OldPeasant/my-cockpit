# Test Report

Date: 2026-11-23

Task: and-test-flaky-sweep - Android: fix remaining flaky unit tests and sweep for more

Scope: task base `00d946c3bab6a54f93e261d35ded870a6098420d` (the claim commit) to measured HEAD
`ced62d6a358cfbc048932d3c9c34008b5242721b`. Task-owned commits (given by the parent worker):
`e2b7c9074` (the test fix), `db80647c6` and `8df9f40d9` (sweep log). `git diff --stat 00d946c3b..HEAD` lists
exactly `FilePreviewScreenTest.kt`, `SearchRouteInAppTest.kt` and `reviews/and-test-flaky-sweep/sweep-log.md`.
Working tree clean apart from the untracked helper reports in `reviews/and-test-flaky-sweep/`.

Requirements:
- `tasks/and-test-flaky-sweep.md` (goal and acceptance criteria)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`
- parent worker's notes `reviews/and-test-flaky-sweep/sweep-log.md`

Method note: the parent worker was running the full Android suite in the same build directory, so on its
instruction this review ran no Gradle command at all. Everything below is static analysis (reading the code and
`grep` over the test sources); the suite runs are the parent's.

## Test files changed

- None by the test-writer. The task's own test changes (commit `e2b7c9074`) are:
  - `android/app/src/test/java/ch/sonensei/cockpit/files/FilePreviewScreenTest.kt`
  - `android/app/src/test/java/ch/sonensei/cockpit/search/SearchRouteInAppTest.kt`

## Tests added or updated

Count: 0 by the test-writer (3 tests changed in `e2b7c9074`, reviewed below).

## Known flake 1: FilePreviewScreenTest retry

Test: `an image that failed to load says so, and retry loads it again` (CI: expected 2 requests but was 1, at line 305).

Cause (confirmed by reading the code):
- The request counter in `images()` was a plain local `var` captured by the Coil `Fetcher`, and Coil runs the
  fetcher on its own background dispatcher: unsynchronized writes on one thread, reads on the test thread.
- After Retry the test only waited until the problem notice was gone. The notice goes as soon as the retry
  starts (the preview recomposes into the loading image), before Coil's second fetch has run. Waiting for the
  named image node does not help either: `AsyncImage` carries `contentDescription = file.name` while it is still
  loading.

Fix in `e2b7c9074`:
- Counter is an `AtomicInteger` (`incrementAndGet` in the fetcher, `get` in the reader).
- Both the retry test and its sibling `an image that failed to load does not keep the next image from loading`
  (same latent race) now wait with `waitUntil(WAIT_MS)` for the request count itself to reach 2, then
  `waitForIdle`, then assert that no problem notice exists and that exactly 2 requests were made.

Review for remaining races: none found.
- The counter is incremented at the start of `fetch`, before the result or the failure, so "count reached 2"
  means the second fetch really ran; with `failures = 1` the second fetch succeeds, so no notice can appear
  after the wait.
- `assertEquals(1, requests())` before Retry is safe: the notice (`waitForTag(PROBLEM)`) appears only after the
  first fetch threw, which is after its increment.
- The exact `assertEquals(2, ...)` cannot be overshot: `state.value` is set to a structurally equal preview, the
  loader has `memoryCache(null)` but nothing triggers a third load, and Coil does not retry by itself.
- The other image test in the file (`previous and next name the neighbouring files and step to them`) already
  waited for `requests() > 0` and only asserts that the image exists and no notice shows; with no failures the
  notice cannot appear, so it is not order-dependent.
- `FakePdf.rendered` (written on `Dispatchers.IO` by `produceState` in `FilePreviewView`) is guarded by
  `synchronized(rendered)` on both sides, and `closed` is `@Volatile`: fine.

## Known flake 2: SearchRouteInAppTest filter chip

Test: `a filter chip searches for its types right away` (failed once in CI run 45023496219).

Cause: `requests` was `Collections.synchronizedList(...)`. The MockWebServer dispatcher thread appends to it,
while `searchRequests()` calls `requests.filter { ... }` on the test thread inside `waitUntil`. Iterating a
synchronized list without holding its lock is not safe: a concurrent append makes the iterator throw
`ConcurrentModificationException`. In this test the app keeps talking to the server in the background (GTD sync,
other screens' requests answered with 404), so appends while the chip test polls are likely. The parent saw this
CME locally on 2026-11-02 (sweep-log.md).

Fix in `e2b7c9074`: `requests` is a `CopyOnWriteArrayList`. Its iterators work on a snapshot, so `filter`,
`any`, `contains` and the `toString()` in the assertion messages never throw and never see a half-written list.

Review for remaining races: none found. Every read of `requests` (`filter`, `contains`, string templates) goes
through the copy-on-write list; the waits are on the effect itself (a search request containing
`types=FILE`), and the final assertion reads the same list after that request is recorded.

## Grep sweep

All commands run from the repository root over `android/app/src/test/java`, `android/app/src/androidTest` and
`android/app/src/sharedTest` (484 + 4 Kotlin files). Only `grep`; no file was changed.

### Pattern A: waiting for a UI change instead of the effect that is asserted

Command: `grep -rnE "waitUntil.*isEmpty\(\)|waitUntilDoesNotExist|waitUntilNodeCount\(.*, 0" android/app/src/test/java android/app/src/androidTest`

| Hit | Verdict |
|---|---|
| `files/FilePreviewScreenTest.kt:323` | Fixed in `e2b7c9074`: now followed by a wait on the request count. |
| `files/FilesRouteInAppTest.kt:455`, `:594`, `:614`, `:641` | Fine: back/close navigation; the following assertions are on UI state that is already settled (nodes that exist), no counter. |
| `files/FilesRouteInAppTest.kt:475` (delete then `coVerify(exactly = 1) { repo.delete(pdf) }`) | Fine: the test first waits for the toast, which the view model posts only after `delete` returned; the entry disappearing is a later step of the same effect. MockK call recording is thread-safe. |
| `files/FilesRouteInAppTest.kt:546` (selection delete) | Fine: same as above, toast first; `coVerify(exactly = 0) { repo.list(docs.id) }` is a negative check. |
| `mail/read/MailReadNextActionRouteTest.kt:82` (`waitGone(SUBJECT)`, then `events` and `coVerify`) | Fine: the subject goes only when the task opens, which happens after `toNextActions` answered; `events` is written by UI callbacks on the main thread. |

Also reviewed: the `waitUntil { ... }` sites in the in-app tests that read MockWebServer request logs
(`SearchRouteInAppTest`, `NewScreenInAppTest`, `InAppPasskeyTest`, `NotesRouteInAppTest`,
`NoteEditorLinksRouteInAppTest`): all wait on the request (or event) they then assert.

### Pattern B: counters and lists changed on background threads without synchronization

Commands:
- `grep -rnE "synchronizedList|Collections\.synchronized" android/app/src/test/java android/app/src/androidTest android/app/src/sharedTest`
- `grep -rlE "import (okhttp3\.mockwebserver|mockwebserver3)\.Dispatcher" android/app/src/test/java ...` (12 files), then
  `grep -nE "mutableListOf|ArrayList|AtomicInteger|CopyOnWrite|synchronized|Concurrent|var .. = 0|\+= |\+\+|override fun dispatch"` over them
- `grep -rlE "Interceptor|Fetcher\.Factory|Fetcher \{|Dispatchers\.IO|thread \{|Executors|newSingleThread" android/app/src/test/java` (19 files), then the same mutation grep
- `grep -rnE "var [a-zA-Z]+ *(: *Int)? *= *0\b" ... --include=*ViewModel*Test.kt` (38 hits) and the same with `--exclude=*ViewModel*Test.kt` (75 hits)
- `grep -rln "Dispatchers.IO\|Dispatchers.Default\|ioDispatcher" android/app/src/main/java --include=*ViewModel.kt`
- `grep -rnE "Dispatchers\.(IO|Default)|newSingleThread|thread \{|Executors" android/app/src/test/java --include=*ViewModel*Test.kt` (no hits)

Synchronized collections:

| Hit | Verdict |
|---|---|
| `search/SearchRouteInAppTest.kt` | Fixed in `e2b7c9074` (copy-on-write list). |
| `newitems/NewScreenInAppTest.kt:61` `paths` (synchronized list, appended by the MockWebServer dispatcher) | Fine: only read with `paths.contains(...)` (lines 101, 112), which the synchronized wrapper locks; no unlocked iteration, no string template of the list. |
| `dashboard/DashboardSaveLayoutTest.kt:48-49` `answers`, `requests` | Fine: the only read, `sent()`, iterates inside `synchronized(requests)`; `answers` is read with a locked `get`. |
| `dashboard/DashboardRepositoryTest.kt:44-45` | Fine: `paths()` iterates inside `synchronized(requests)`. Lines 206 and 227 (`requests.single { ... }`, `requests.single()`) iterate without the lock, but only after `runBlocking { dashboard.load(...) }` returned; every request of that load was recorded before its response was sent, and nothing else talks to the server then, so no writer can run during the iteration. |
| `auth/KeystoreTokenCipherTest.kt:169-170` `FakeKeys.keys`, `specs` | Fine: written and read on the test thread only (the fake Keystore provider is called synchronously by the cipher). |

MockWebServer dispatchers and other background writers:

| Hit | Verdict |
|---|---|
| `core/network/NetworkModuleGraphTest.kt:55` `refreshAuthorizations` (plain list) | Fine: written inside `synchronized(refreshAuthorizations)` by the dispatcher; read once (line 111) after `authApi.getCurrentUser()` returned, when the single refresh request is long finished. |
| `gtd/data/GtdDataGraphTest.kt:72` | Fine: written and read under `synchronized`. |
| `gtd/data/GtdTestSupport.kt:78-79` | Fine: `ConcurrentLinkedQueue` per route under a lock, `CopyOnWriteArrayList` for requests. |
| `gtd/data/GtdTestSupport.kt:143-152` `FakeScheduler.scheduled/cancelled` (`@Volatile` with `++`) | Fine: `++` on a volatile is not atomic, but every assertion is `> 0` or `== 0`, which a lost update cannot flip; `ReadLaterQueueTest`'s `assertEquals(1, ...)` follows a single call. |
| `gtd/data/GtdChangeStreamTest.kt:79` `calls` (`var`, changed in the server's `events` lambda) | Fine: the client opens the stream connections one after the other (a reconnect only after the previous answer), so the writes are ordered; the test asserts on the collected signals, not on `calls`. |
| `core/network/TokenAuthenticatorTest.kt`, `auth/InAppPasskeyTest.kt`, `capture/queue/CaptureQueueConsistencyTest.kt:187`, `notes/MarkdownViewTest.kt`, `core/network/ServerUrlTest.kt`, `auth/AuthRepositoryLogoutHookTest.kt`, `gtd/data/GtdCacheConsistencyTest.kt`, `notes/NotesRouteInAppTest.kt`, `notes/NoteEditorLinksRouteInAppTest.kt`, `mail/list/FollowMailChangesTest.kt` | Fine: already `AtomicInteger` / `CopyOnWriteArrayList`. |
| `capture/queue/CaptureQueueConsistencyTest.kt:40` `StopAfterDeleteDao.deleted` | Fine: `delete` resumes in the caller's coroutine (the `runBlocking` thread) after Room's suspend call. |
| `auth/AuthRepositoryTest.kt` `cachesCleared`, `appCachesCleared`, `cleared` (ints and lists, some caches cleared from the app-scope collector on another thread) | Fine: `AuthRepository.clearCaches` is serialized by a mutex (documented "never concurrently"), which orders every write; the logout's own clear runs on the caller, and the assertions run after `logout()` returned. Where both clears may run, the test asserts `cleared >= 1`. `dispatched++` (line 866) is only checked with `> 0`. |
| `capture/queue/CaptureQueueContractTest.kt:141` `scheduled` (changed on `Dispatchers.IO`) | Fine: read after `job.join()`, which orders it. |
| `mail/read/MailAttachmentRepositoryTest.kt:237` `reads`, `bodyClosed`, `targetClosed` (written on `Dispatchers.Default`) | Fine: read after `download.await()` (in `runCatching`), which orders them. |
| `files/FilesRepositoryTest.kt:192` `writes` | Fine: read after `job.join()`. |
| `reminders/ReminderSyncTest.kt:226` interceptor sets `loggedIn = false` on the OkHttp thread | Fine: the sync reads it only after the response came back through OkHttp's callback, which hands over to the caller. |
| `mail/compose/DeviceFilesTest.kt:155` | Fine: already `@Volatile`, single writer. |

View-model counters (the parent's open question): confirmed fine. Only three production view models leave the
main dispatcher: `FilesViewModel` and `MailAttachmentsViewModel` take their `io` dispatcher as a constructor
parameter, and every test builds them with the test dispatcher (`FilesViewModel(repo, dispatcher)`,
`MailAttachmentsViewModel(repository, files, dispatcher)`); `ShareViewModel` uses `Dispatchers.IO` but its two
test classes have no counters and wait on `uiState` with a timeout. No view model uses `flowOn`, `GlobalScope`
or its own threads, and no `*ViewModel*Test.kt` starts `Dispatchers.IO`/`Default` or a thread. So every
`var x = 0` in the 38 view-model test hits (for example `BookmarksAddReaderViewModelTest`, `FilesViewModelTest`,
`HabitsViewModelManageTest`, `SettingsViewModelTest`, `NewViewModelTest`, `FakeTaskDetailSource`) is changed by
fakes or MockK answers called from `viewModelScope` on the `setMain` test dispatcher and read on the same
thread. The other non-view-model hits (`CaptureWidgetUpdaterTest` on `backgroundScope` of `runTest`,
`GtdRefreshSupportTest`, `ReminderShowFiredByServerTest`, `PushMessageHandlerTest`, synchronous handlers) and
the Compose screen tests (`LoginScreenTest`, `PasskeyScreensTest`, `SettingsScreenTest`, `CaptureSheetTest`,
the `calls` lists, ...) are changed by click callbacks on the main thread: fine.

### Pattern C: real clock in Compose tests that render time-dependent UI

Commands:
- `grep -rnE "(LocalTime|LocalDate|LocalDateTime|Instant|ZonedDateTime|OffsetDateTime|YearMonth)\.now\(|Clock\.system|System\.currentTimeMillis\(\)" android/app/src/test/java android/app/src/androidTest android/app/src/sharedTest`
- `grep -rnE "(LocalTime|LocalDate|LocalDateTime|Instant|ZonedDateTime)\.now\(\)|Clock\.systemDefaultZone\(\)|System\.currentTimeMillis\(\)" android/app/src/main/java` restricted to composable files, to see which screens read the clock themselves

| Hit | Verdict |
|---|---|
| `calendar/agenda/CalendarScreensTest.kt:61` default `Clock.systemUTC()` for `LocalNowClock` | Fine: the clock only positions the now line and, for a grid with no timed entry on today, the first visible hour. The tests that use the default clock either have timed entries (scroll goes to the earliest one, not to the time of day) or assert only `assertExists` / the empty notice, which do not depend on the scroll position. The test about the now line uses its own `MovableClock`. All other calendar screen tests provide a fixed `nowClock`. |
| `dashboard/DashboardScreenTest.kt:57, 191, 239, 250, 329` and `dashboard/DashboardTaskOpenTest.kt:29-30` (`LocalDate.now()`) | Low risk, not changed: `DashboardScreen.Tasks` itself uses `LocalDate.now()` in the same default zone, so test and screen agree on "today" at any time of day. Only `overdue tasks show their due date, tasks due today do not` could fail, and only if the wall clock crosses midnight in the fraction of a second between building the data (line 250) and the first composition. The tasks overdue by 2, 3 or 9 days stay overdue across midnight. A deterministic fix needs an injectable clock in `DashboardScreen` (production change, outside this task's test-only scope). |
| `gtd/task/TaskDetailListFieldsTest.kt:51` `today = LocalDate.now()` | Low risk, not changed: `TaskDetailListFields` computes its quick picks and the picker default with `LocalDate.now()` in the same zone. Only a midnight crossing during one test (a second or two) could make them disagree. Same remedy as above if ever needed. |
| `gtd/task/TaskDetailViewModelRelistTest.kt:33` | Low risk (view-model test, not Compose): same midnight-crossing-only window; noted by the parent. |
| `gtd/task/TaskRemindersContentTest.kt` vs `TaskRemindersSection(now = Instant.now())` | Fine: the test passes a fixed `now`. |
| `MailListContent` / `NewContent` / `TrashContent` default clocks | Fine: every test that renders them passes a fixed clock/now, except `MailFolderManage*Test` and `MailComposeWiringTest`, which make no date-text assertions (`MailComposeWiringTest` uses a fixed 2026-10-21 mail date, shown as a date at any time of day). |
| `reminders/ReminderReceiversTest.kt:83` | Fine: not Compose; computes a reminder 30 minutes after the real now on purpose. |
| `System.currentTimeMillis()` deadlines in `CaptureEntrySurfacesTest`, `CaptureEntryQueueStatesTest`, `CaptureEntryPointsTest`, `ReminderLogoutTest`, `PushInAppTest` | Fine: polling timeouts, not time-dependent UI. |
| `androidTest/.../Phase3DemoEmulatorTest.kt`, `sharedTest/.../Phase3Demo.kt`, `E2eStack.kt` | Out of scope: emulator/e2e demo code against a real stack, not part of `testDebugUnitTest`. |

### Pattern D: asserting an exact directory listing

Command: `grep -rnE "listFiles\(|\.list\(\)|walk\(|walkTopDown|Files\.list|cacheDir|filesDir|noBackupFilesDir" android/app/src/test/java android/app/src/androidTest`

| Hit | Verdict |
|---|---|
| `capture/queue/AttachmentStoreTest.kt:190, 208, 251, 272-280`, `CaptureQueueContractTest.kt:66, 70`, `CaptureQueueTest.kt:88, 124` | Fine: list `q.root`, a per-test directory `capture-test-` plus `System.nanoTime()` under `filesDir` that only the queue under test writes to; the parent's first pass saw the same. |
| `notes/NoteEditorRouteTest.kt:143` `File(cacheDir, "notes-photos").listFiles()!!.single()` | Fine: `NotePhotoFiles.clear` then `create` run synchronously in the camera click handler, and nothing else writes to `notes-photos`; Robolectric gives each test a fresh app data directory. |
| `auth/SessionCacheTest.kt:61-106` | Fine: the Room-lock fix from `and-test-sessioncache-flaky`; asserts named files and `isDirectory`, not a full listing. |
| `mail/list/MailFolderManageModelsTest.kt:191-193` | Fine: lists the checked-in `res/` folders (static). |
| `files/DeviceFilesTest.kt:49`, `files/PreviewFilesTest.kt:26`, `notes/NotePhotoFilesTest.kt:97` | Fine: compare one expected path, no listing. |
| the `*.list()` hits in view-model/repository tests | Not directory listings (repository `list()` calls). |

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: FilePreviewScreenTest retry waits for the effect, thread-safe counter, siblings too | `an image that failed to load says so, and retry loads it again`, `an image that failed to load does not keep the next image from loading` | Static review of `e2b7c9074` (no Gradle, by instruction) | Pass (review): fix is correct, no remaining race found. Execution: Not run here, see "Suite runs". |
| AC2: SearchRouteInAppTest chip test cause found and fixed | `a filter chip searches for its types right away` | Static review of `e2b7c9074` | Pass (review): CME from unlocked iteration of a synchronized list; copy-on-write fix is correct. Execution: Not run here. |
| AC3: three suite runs (one under load, one near midnight), failures analysed | full `testDebugUnitTest` | Run by the parent worker | Not run here: the parent forbade Gradle during its own runs; recorded in sweep-log.md. |
| AC4: grep check for the four patterns, hits fixed or explained | all test sources | the `grep` commands above | Pass: every hit explained; no new real race needing a code change; two midnight-crossing-only residual risks documented. |
| AC5: test code only | n/a | `git diff --name-only 00d946c3b..HEAD -- server/src/main web/src android/app/src/main` | Pass: empty. |
| AC6: three green runs in a row and `ktlintCheck testDebugUnitTest lintDebug` | full suite | Run by the parent worker | Not run here (same reason as AC3). |

## Focused test execution

Command:

None run by the test-writer (no Gradle allowed while the parent's suite runs use the same build directory).

Result:

Not run

## Full test execution

Command:

`cd android && ./gradlew testDebugUnitTest --rerun-tasks` (parent worker)

Result:

Not run by the test-writer; see "Suite runs".

## Suite runs

Recorded by the parent worker in sweep-log.md (`reviews/and-test-flaky-sweep/sweep-log.md`). At the time of this
review it holds run 1: 2026-11-23, code at `e2b7c9074`, normal load,
`cd android && ./gradlew testDebugUnitTest --rerun-tasks --continue`, BUILD SUCCESSFUL in 4m 30s, no failures.

## .taskcheck

Result:

Not run (it runs Gradle for the Android part, which the parent asked not to start in parallel).

## Bugs found

None. No production defect was found; both flakes are test-side races.

## Coverage

Status: not applicable. The task changes test code only; production code is unchanged.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not applicable (no changed production lines).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-test-flaky-sweep`, base
  `00d946c3bab6a54f93e261d35ded870a6098420d`, HEAD `ced62d6a358cfbc048932d3c9c34008b5242721b`, task commits
  `e2b7c9074`, `db80647c6`, `8df9f40d9` as given by the parent worker and confirmed by `git diff --stat`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `ced62d6a3`; no uncommitted source or
  test changes (only the untracked helper reports).
- Exact included files; renamed/deleted/excluded files and reasons:
  `git diff --name-only 00d946c3b..HEAD -- server/src/main web/src android/app/src/main` printed nothing, so the
  task coverage scope is empty. Excluded: the two test files (test code is not measured) and
  `reviews/and-test-flaky-sweep/sweep-log.md` (documentation).
- Provider/version, command, working directory, and test selection: none run. The Android provider
  (`./gradlew createDebugUnitTestCoverageReport`, JaCoCo) would add no information, because there is no
  production file in scope, and running Gradle was not allowed during the parent's suite runs.
- Run start/completion time and exit status: no run.
- Generated report paths and report hashes/run identifier: none.
- Evidence outputs are fresh and inputs stayed unchanged: n/a, no measurement.
- Final run after latest test additions and production fixes: n/a (no production fixes, no tests added by
  the test-writer).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production file changed) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none | n/a | Test-only change; nothing to measure. |

## Unable to test

- Executing the changed tests and the suite runs: not done by the test-writer because the parent was running the
  Android suite in the same build directory and asked for no parallel Gradle. The parent records those runs.
- The two midnight-crossing residual risks (`DashboardScreenTest` "tasks due today", `TaskDetailListFieldsTest`)
  cannot be made deterministic without an injectable clock in `DashboardScreen` / `TaskDetailListFields`
  (production code). Follow-up only if such a failure is ever seen; the window is a fraction of a second to a
  few seconds per day.

## Summary

No additional tests were necessary for the reviewed scope. Both known flakes are fixed correctly in
`e2b7c9074` with no remaining race found; the grep sweep of all four patterns found no further real race that
needs a code change (two midnight-crossing-only risks are documented above). Suite execution evidence is the
parent worker's (sweep-log.md).

## Delta for 2f2c522c9

Date: 2026-11-23

Scope: commit `2f2c522c9ed4e3fe651891a46b19c4e4a21a64c9` (since the reviewed `e2b7c9074`), one file:
`android/app/src/test/java/ch/sonensei/cockpit/search/SearchRouteInAppTest.kt`, one inserted blank line between
the `server` property and the comment above `requests` (ktlint formatting). Inspected with `git show 2f2c522c9`.

- Tests added or updated in this delta: 0. Whitespace only in a test file; no behavior and no production code
  changed, so no new tests are needed and the earlier analysis and coverage section (test-only change, nothing to
  measure) still apply unchanged.
- Gradle was not run by the test-writer (parent's instruction: no parallel Gradle). The suite results below are
  the parent worker's runs, cited from `reviews/and-test-flaky-sweep/sweep-log.md` and the parent's report:

| Run | Code | Command | Result |
|---|---|---|---|
| 1, normal load | `e2b7c9074` | `cd android && ./gradlew testDebugUnitTest --rerun-tasks --continue` | BUILD SUCCESSFUL in 4m 30s, no failures |
| 2, under load (12 busy loops, max-workers 16) | `e2b7c9074` | `sh .tmp/load-run.sh` (wraps `./gradlew testDebugUnitTest --rerun-tasks --continue --max-workers=16`) | BUILD SUCCESSFUL in 12m 46s, 0 failed |
| 3, clock crossing midnight (time zone GMT+03:41) | `e2b7c9074` | `sh .tmp/midnight-run.sh` (wraps `./gradlew testDebugUnitTest --rerun-tasks --continue`) | BUILD SUCCESSFUL in 4m 38s, 0 failed; caveat from sweep-log: shifted zone in forked test workers not directly proven |
| Final, full Android check | `2f2c522c9` | `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` | BUILD SUCCESSFUL in 4m 7s (reported by parent) |

Runs 1-3 used test code identical to `2f2c522c9` except for the blank line; the final run covers the exact
revision including ktlint. Summary unchanged: no additional tests were necessary; no production defects found.
