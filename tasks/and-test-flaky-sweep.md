---
title: Android: fix remaining flaky unit tests and sweep for more
status: todo
owner: 
deps: []
touches: [android/app/src/test/java]
prio: 2
lease_until: 
releases: 1
extended: 
---
## Goal
CI on `main` keeps failing in "Fast check (android)" because of flaky unit tests, a different one each time. Two were
fixed already (`and-test-sessioncache-flaky`: Room lock file in cacheDir; `and-test-calendar-clock`: real time of
day). Fix the known remaining ones and look for more, so the Android check is reliable. Test code only, unless a real
app bug turns up.

Known from the CI history (last 40 runs of `ci.yml`, `gh run view <id> -R OldPeasant/my-cockpit --log-failed`):
- `FilePreviewScreenTest > an image that failed to load says so, and retry loads it again`: failed 3 times (runs
  42583525057, 43586380730, 45249977806) with `expected:<2> but was:<1>` at line 305. Cause: after Retry the test waits
  only until the error notice is gone, which happens as soon as the retry starts. Coil runs the second fetch on a
  background thread, so the count may still be 1. The counter in `images()` is also a plain `var` changed on another
  thread. The sibling test right below it does it right (it waits for the image to show).
- `SearchRouteInAppTest > a filter chip searches for its types right away`: failed once (run 45023496219); cause not
  analysed yet.
- Not in scope: the many `> classMethod FAILED` in runs 45008992077/45018745005 (Robolectric runtime jars could not be
  downloaded; fixed by `ci-robolectric-cache`).

## Acceptance criteria
- [ ] `FilePreviewScreenTest` retry test fixed: after Retry it waits for the loaded image (or the request count), and the
      counter is thread-safe (e.g. `AtomicInteger`); other tests in the file with the same pattern too
- [ ] `SearchRouteInAppTest > a filter chip searches for its types right away`: cause found and fixed (or, if it can't be
      reproduced or explained, the analysis and what was ruled out recorded in `reviews/<id>/tests.md`)
- [ ] Sweep for more: the full Android unit suite (`./gradlew testDebugUnitTest --rerun-tasks`) is run at least 3 times,
      once with the JVM under load (e.g. `--max-workers` raised or another build in parallel, to mimic the slower CI
      runner), and once with the system clock or the tests' clocks late in the evening/around midnight UTC where that can
      be done without changing production code. Every failure that appears is analysed and fixed the same way;
      `reviews/<id>/tests.md` lists each run with its result and each flaky test with its cause and fix
- [ ] A grep-based check of the test sources for the patterns found (waiting for a UI change instead of the effect
      checked; counters/lists changed on background threads without synchronization; real `Clock`/`LocalTime.now()`/
      `LocalDate.now()` in Compose tests that render time-dependent UI; asserting an exact directory listing) is done,
      and suspicious hits are fixed or explained in `tests.md`
- [ ] Test code only; production code unchanged unless a real bug is found (then record it with `tasks.py assume`)
- [ ] After all fixes, the full Android unit suite passes locally 3 times in a row
      (`./gradlew testDebugUnitTest --rerun-tasks`, final code), and `cd android && ./gradlew ktlintCheck
      testDebugUnitTest lintDebug` passes

## Notes
- If the sweep finds more than fits in one session, fix the known ones first, then split the rest into subtasks
  (`tasks.py split`).
- Local runs need `ANDROID_HOME=$HOME/Android/Sdk` if `android/local.properties` is missing.
- release by dell26/agent-1: Done: both known flakes fixed in e2b7c9074 (FilePreviewScreenTest: AtomicInteger counter, both retry/next-image tests wait for requests()>=2 since the AsyncImage node is named while loading; SearchRouteInAppTest: CME from iterating Collections.synchronizedList while MockWebServer appends -> CopyOnWriteArrayList). First grep sweep + sweep run 1 (passed, 4m30s) in reviews/and-test-flaky-sweep/sweep-log.md. Left: sweep run under load (e.g. --max-workers raised / parallel build) and run with clock near midnight UTC (e.g. TZ/-Duser.timezone or faketime if available), finish grep check of view-model counters, test-writer + code-reviewer (give them sweep-log.md for tests.md), 3 final full runs + ktlintCheck testDebugUnitTest lintDebug, verify, quality.json, done.
