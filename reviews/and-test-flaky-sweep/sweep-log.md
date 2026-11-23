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
