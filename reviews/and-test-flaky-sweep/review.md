# Code Review

Date: 2026-11-23

Task: and-test-flaky-sweep - Android: fix remaining flaky unit tests and sweep for more

Scope: `git diff 00d946c3b..HEAD -- android` (code commit e2b7c9074; the other commits in the range only add
`reviews/and-test-flaky-sweep/sweep-log.md` and a merge of the task's wip branch). Static review only: by request of
the parent worker, Gradle was not run (the parent runs the full Android suites in the same build directory); suite
results are in the parent's `reviews/and-test-flaky-sweep/sweep-log.md`.

Requirements:
- `tasks/and-test-flaky-sweep.md` acceptance criteria and notes
- `AGENTS.md` project notes (lint is part of the contract, test code only)
- `android/.editorconfig` (ktlint_official)
- Parent notes: `reviews/and-test-flaky-sweep/sweep-log.md`

Git range:
- Branch: main
- HEAD: ced62d6a3
- Base/start: 00d946c3bab6a54f93e261d35ded870a6098420d
- Commits reviewed: 4 (one with Android code changes: e2b7c9074)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## What was checked

FilePreviewScreenTest.kt
- `images()` counter: was a plain captured `var` incremented inside the Coil `Fetcher` (runs on Coil's background
  dispatcher) and read from the test thread. Now an `AtomicInteger` with `incrementAndGet()` in the fetcher and
  `get()` in the reader lambda. This removes both the lost-update and the visibility race. All readers of the counter
  (lines 282, 299, 308, 311, 326, 329) go through the lambda, so none bypass it.
- Retry test (lines 301-311): previously waited only for the problem notice to disappear, which happens when the retry
  starts, then asserted 2 requests; this is the CI failure `expected:<2> but was:<1>`. It now waits on the effect being
  asserted (`requests() >= 2`), so the race between Coil's fetch thread and the test thread is gone. The sibling test
  (lines 315-330) had the same latent race (the `AsyncImage` node is named while still loading, as the sweep log
  notes) and got the same wait, which covers criterion 1's "other tests in the file with the same pattern".
- Waits: every new wait is `composeRule.waitUntil(WAIT_MS)` with `WAIT_MS = 5_000L`, followed by `waitForIdle()`
  (bounded by the Compose idling timeout). Nothing can hang without a bound. With `failures = 1` the second fetch
  always succeeds, so `assertEquals(2, requests())` cannot see an extra failure-driven fetch. A third fetch would
  have failed the old assertion as well, so no new failure mode is added.
- Note (not a finding): the `assertDoesNotExist()` on the problem notice after `waitForIdle()` does not wait for
  Coil to deliver the second result on the main thread, so it is a weak check of "no failure again". It cannot
  fail falsely, because the notice is already gone when the retry starts and the second fetch succeeds. The
  behavior under test ("retry loads it again") is proven by the request count, so this does not reduce coverage of
  the criterion.

SearchRouteInAppTest.kt
- `requests` was `Collections.synchronizedList`. Its `add` and `contains` are synchronized, but `filter` (in
  `searchRequests()`, polled inside `waitUntil` at line 156) iterates through the list's iterator without holding the
  lock, while MockWebServer's dispatcher thread appends (line 90). This can throw `ConcurrentModificationException`
  in the poll lambda, which fits the single CI failure of "a filter chip searches for its types right away". A
  `CopyOnWriteArrayList` iterates a snapshot, so `filter`, `contains`, `+=` and string templating
  (`"$requests"`, line 144) are all safe across threads. Write volume is tiny, so copy-on-write cost does not matter.
  The unused `java.util.Collections` import was removed.
- Line 158 asserts the combined `types=FILE&types=FOLDER` after waiting for `types=FILE`. The path and query are
  appended as one string per request, so the assertion sees the complete entry once the wait succeeds. No
  remaining race.

General
- No production code changed: `git diff 00d946c3b..HEAD --stat` touches only the two test files and the sweep log.
- ktlint style: import order (`java.util.UUID` before `java.util.concurrent.*`, ASCII order) and the new
  `AtomicInteger`/`CopyOnWriteArrayList` imports are correctly placed. All lines in both files are at most
  140 characters (ktlint_official limit). Comments are concise and explain the threading reason. ktlint was not run
  here (Gradle excluded by request); the parent's final `ktlintCheck` run is the evidence for that part of
  criterion 6.
- Out of this review's scope, and recorded by the parent as still open: sweep runs under load and around midnight,
  the grep check of view-model counters, the 3 final runs, and `tests.md` (criteria 2-4 and 6 evidence). These
  are not reported as missing code.

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

## Delta review of 2f2c522c9

Reviewed revision: 2f2c522c9ed4e3fe651891a46b19c4e4a21a64c9 (`git show 2f2c522c9`).

- Change: one blank line inserted in
  `android/app/src/test/java/ch/sonensei/cockpit/search/SearchRouteInAppTest.kt` between the `server` property
  and the comment above `requests` (line 77), as ktlint_official requires before a commented declaration. No other
  file or line changed; no behavior, import or test logic changed.
- Gradle was not run here by request; the parent reports `cd android && ./gradlew ktlintCheck testDebugUnitTest
  lintDebug` on this revision -> BUILD SUCCESSFUL.
- Findings: none. The conclusion above (no material issues found) still holds for the final revision.
