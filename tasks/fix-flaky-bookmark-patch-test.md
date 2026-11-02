---
title: Flaky test: BookmarkWebTests PATCH races the background metadata fetch (409)
status: in_progress
owner: dell26/agent-1
deps: [bookmarks-api]
touches: [server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/BookmarkWebTests.kt]
prio: 1
lease_until: 2026-11-02T08:06:34Z
extended: 
releases: 1
---
## Goal
The rerun of CI run 41355157150 (2026-11-02, server fast check) failed on `BookmarkWebTests > PATCH changes only the
sent fields, needs a current If-Match and rejects unknown fields and nulls()` with `Status expected:<200> but
was:<409>` (BookmarkWebTests.kt:129, the `change(current, ...)` call). The first run of the same commit failed on a
different flaky test (BackupWebTests, task fix-flaky-backup-progress-and-settings-tests), so main stays red.
Likely cause: creating a bookmark starts the background metadata fetch (here for `https://unreachable.invalid/a`).
When it records its result between the test's `fetch()` and `change()`, the bookmark's version moves on and the
PATCH with the fetched ETag fails the optimistic lock (`OptimisticLockingFailureException` -> 409 in
`ProblemDetailsAdvice`). Other tests in the file already wait with `settled(id)`.

## Acceptance criteria
- [x] The cause is confirmed (e.g. by delaying the metadata fetch in a local experiment, or from the code path) and
      recorded in the notes; if it is not the metadata fetch, fix the actual cause
  - verified (2026-11-02 05:27+0100 by dell26/agent-2): Code path: BookmarkMetadataWorker stores the fetch result via BookmarkService.applyMetadata on a background thread, which bumps bookmark.version; PATCH checks If-Match in update() (412) and saveAndFlush hits the @Version lock (OptimisticLockingFailureException -> 409 via ProblemDetailsAdvice) if the store commits between load and flush. Experiment: original test with 'current = bookmark; Thread.sleep(500)' before change() (no other request in between) -> FAILED 'Status expected:<200> but was:<412>' (version moved by the background fetch only); same experiment on the fixed test (settled first) -> passes. Cause confirmed: metadata fetch.
- [x] The PATCH test (and any other test in `BookmarkWebTests` that changes a freshly created bookmark with its
      ETag) waits for `settled(id)` first, or creates the bookmark so that no background fetch runs; the test still
      checks the same behavior (428, 412, 400 cases and the partial update)
  - verified (2026-11-02 05:57+0100 by dell26/agent-2): Read BookmarkWebTests.kt (code-reviewer + test-writer reports): PATCH test now does settled(newBookmark(...).id()) before the 428/412/400/partial-update requests, assertions unchanged; every other If-Match change on a fresh bookmark already waits for settled(), except the deliberate slow-ordering race (now 2s delay vs 5s timeout) -> met
- [ ] `cd server && ./gradlew test --tests '*BookmarkWebTests*'` passes 20 times in a row, also under load
      (parallel `stress-ng` or a second Gradle build); record the load
- [ ] `sh scripts/check.sh` passes

## Notes
Found while checking the failing CI runs (2026-11-02). Test-only fix. A user editing a bookmark right after saving
it can get the same 409; the web client handles 409 by reloading, so that is not changed here.
- ASSUMPTION (2026-11-02 05:29+0100 by dell26/agent-2): BookmarkWebTests fetch timeout raised 2s->5s and the slow ordering test's WireMock delay 700ms->2000ms, so its deliberate PATCH-before-fetch race keeps a wide margin under load while the slow fetch still succeeds; no test in the class depends on the 2s value.
- extended by dell26/agent-2: Fix + both helper reports (tests.md, review.md, review-r1.md, fixes.md) committed at aa64d98e4; criteria 1-2 verified. Left: run './gradlew test --tests *BookmarkWebTests*' 20x under load (stress-ng or parallel gradle) and verify #3, run sh scripts/check.sh and verify #4, write+commit reviews/fix-flaky-bookmark-patch-test/quality.json (reviewed_revision = HEAD code), then done.
- release by dell26/agent-2: Done: fix (a153063eb settled() before PATCH; 11e5aa16c fetch timeout 5s, slow-ordering delay 2s), test-writer + code-reviewer + fixer reports committed and current (review.md/tests.md on the final code), criteria 1-2 verified. Left (no code work): (1) run .tmp-style stress: 16 busy-loop CPU burners + 'cd server && ./gradlew test --tests *BookmarkWebTests* --rerun' 20x in a row (~1m40s each); last attempt passed 16/16 then the session ended; then verify #3 with the load. (2) sh scripts/check.sh, verify #4. (3) update validation results in reviews/fix-flaky-bookmark-patch-test/quality.json (currently marked INCOMPLETE/NOT RUN; reviewed_revision 11e5aa16c is correct), commit, tasks.py done.
