# and-test-flaky-sweep: parent worker's sweep log

Working notes of the parent worker (not a helper report); the test-writer's `tests.md` is the authoritative report.

## Known flakes, fixed in e2b7c9074

- `FilePreviewScreenTest` retry test: counter in `images()` was a plain `var` incremented on Coil's fetch thread
  -> `AtomicInteger`. After Retry the test waited for the error notice to vanish, which happens when the retry
  *starts*. Note: the image node carries `contentDescription = file.name` while it is still loading
  (`FilePreviewView.ImagePreview` -> `AsyncImage`), so waiting for the named node does not prove the fetch either
  (the sibling test "does not keep the next image from loading" had the same latent race). Both now wait for
  `requests() >= 2`, then assert no problem notice and exactly 2 requests.
- `SearchRouteInAppTest > a filter chip searches for its types right away`: `requests` was
  `Collections.synchronizedList`; `searchRequests()` calls `filter`, which iterates without holding the list's
  lock while MockWebServer's dispatcher thread appends -> `ConcurrentModificationException` (seen locally on
  2026-11-02 in a full check run). Now a `CopyOnWriteArrayList` (iteration on a snapshot).

## Grep sweep (first pass)

- `synchronizedList`: also `KeystoreTokenCipherTest.FakeKeys.specs`; only `clear`/`+=`/`single()` on the test
  thread, all synchronized calls, no iteration racing a writer -> fine.
- `var x = 0` counters: the hits in Compose screen tests (`LoginScreenTest`, `PasskeyScreensTest`,
  `LogoutButtonTest`, ...) are changed by click callbacks on the main/test thread -> fine; view-model tests run on
  `runTest` dispatchers -> to be confirmed by the test-writer.
- `LocalDate.now()`: `TaskDetailViewModelRelistTest.today` (view-model test, not Compose; only relative dates
  `today.plusDays(n)`; would only matter across midnight between class init and assertion) -> low risk.
- Exact directory listings: `AttachmentStoreTest` asserts empty `listFiles()` on its own queue root (not
  `cacheDir`) -> fine.

## Suite runs

(see below; appended as runs finish)
- Run 1 (2026-11-23, code at e2b7c9074, normal load): `cd android && ./gradlew testDebugUnitTest --rerun-tasks --continue` -> BUILD SUCCESSFUL in 4m 30s, no failures.
- Run 2 (2026-11-23, same test code, under load): `sh .tmp/load-run.sh` = 12 busy-loop shell processes on the 16-core
  machine for the whole run, plus `./gradlew testDebugUnitTest --rerun-tasks --continue --max-workers=16` (the
  test-writer/code-reviewer helpers were also running at the same time) -> BUILD SUCCESSFUL in 12m 46s, 0 FAILED.
- Run 3 (2026-11-23, same test code, clock around midnight): `sh .tmp/midnight-run.sh` = JVM default time zone set
  via `JAVA_TOOL_OPTIONS=-Duser.timezone=GMT+03:41` so that local time was 23:57 at the start (20:16 UTC) and 00:02 at
  the end, then `./gradlew testDebugUnitTest --rerun-tasks --continue` -> BUILD SUCCESSFUL in 4m 38s, 0 FAILED.
  Caveat: "Picked up JAVA_TOOL_OPTIONS" appears for the Gradle client and daemon; the forked test workers inherit the
  daemon environment by default, but their startup line is not in the test-result XMLs, so it is not directly proven
  that every worker used the shifted zone.
- Runs 1-3 are three full passes in a row on the final test code (`e2b7c9074`; no Android change since).
