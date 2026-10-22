# Code Review

Date: 2026-10-22

Task: and-journal-habits - Android journal and habits

Scope: `git diff f3c2102802292ecc2e011934b7c70f3128d02bb9..9cfeaf64c4acbad44a28f923141d0568a86b98db`, committed changes only (re-review of the final code after the fixes). Reviewed revision: 9cfeaf64c4acbad44a28f923141d0568a86b98db. Coverage of the earlier passes:
- The first review covered the whole task diff.
- The production and test changes since that review (`git diff 0ac22a651..43c5a2346 -- android`) were checked line by line on 43c5a2346b865d449869b3a78ef74a8c721a3033.
- The last commit (`git diff 43c5a2346..9cfeaf64c -- android`) adds two tests and changes no production code. It was checked separately (see "Addendum").

Requirements:
- tasks/and-journal-habits.md (acceptance criteria and recorded assumptions, including the CR-2 assumption)
- SPEC.md §8.5, §9.2 item 8
- AGENTS.md "Project notes", docs/ARCHITECTURE.md
- API contract: api/openapi.yaml (`putJournalEntry`: 409/412/428) and the server implementation (`JournalEntryController`, `core/web/ETags.kt`)
- Previous reports: reviews/and-journal-habits/review.md (first review), tests.md, fixes.md

Git range:
- Branch: main
- HEAD: 9cfeaf64c4acbad44a28f923141d0568a86b98db (reviewed revision)
- Base/start: f3c2102802292ecc2e011934b7c70f3128d02bb9
- Commits reviewed: 6 (eb10d7b8d assume, e495ab72d, 54a47eb10, 0ac22a651, 43c5a2346, 9cfeaf64c)
- Uncommitted changes: none apart from this report

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Not verified by the reviewer: I did not run Gradle. The test results come from the other agents:
- Parent and fixer, on 43c5a2346: `./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*' --tests 'ch.sonensei.cockpit.habits.*'` -> 74 passed, 0 failed.
- Test-writer, on 9cfeaf64c: the same command -> 76 passed. The full `./gradlew createDebugUnitTestCoverageReport` exited 0.

## Disposition of earlier findings

- **CR-1 (P1, 428 not handled as a conflict): fixed and verified.** `JournalRepository.kt:162` is now `CONFLICTS = setOf(NOT_FOUND, 409, 412, 428)`, and the KDoc at `:107-111` says so.
    - A new entry that the server refuses with 428 (the day got an entry elsewhere) now loads the current entry and returns `JournalSaveResult.Conflict`, so the conflict banner appears.
    - The server sends 428 only for a request without `If-Match`, so updates of an existing entry are not affected.
    - `JournalRepositoryTest` now uses 428 for the "entry made elsewhere" case. A separate test keeps 409 for two creates racing on the unique index. Both match the server.
- **CR-2 (P3, device date vs. the server's today): accepted, not fixed.** It is recorded as an assumption in tasks/and-journal-habits.md (2026-10-22 13:46). The assumption names what the user sees: a 400 response is shown as "Not saved: the server refused the change". `JournalScreenTest` ("a save the server refused says so and can be retried") covers that status. Accepting it is the parent's decision and the assumption documents it, so it is no longer an open finding.
- **TW-1 (keep mine stays "Unsaved changes" when the server already has the same text): fixed and verified.** `JournalViewModel.kt:181` now sets the status with `withStatus()`, as `useTheirs()` does.
    - When the text equals the server's entry, the status is SAVED. The `saveSoon(0)` that follows sends nothing, because of the `isSaved()` early return at `:164`.
    - When the text differs, the status is UNSAVED and the save goes out over `conflict.current`.
    - The test-writer's regression test "keeping my text when the server already has the same text ends as saved" checks this end state.

## Review of the added tests (up to 43c5a2346)

- **Route tests.** `JournalRouteInAppTest` and `HabitsRouteInAppTest` follow the existing in-app Hilt tests (`NotesRouteInAppTest`, `ContactsRouteInAppTest`, `FilesRouteInAppTest`): a `@BindValue` mock repository, fictitious tokens, an `ActivityScenario` launch, and navigation through More.
    - They do not depend on the device date: `repo.get(any())` matches any day, and a comment in the test says why.
    - Saves are stubbed only for the exact expected arguments and confirmed with `coVerify(exactly = 1)`. Each test first waits with `waitUntil` for an `AtomicBoolean` that the stub sets. No fixed sleeps are used.
    - The back-arrow test checks behavior that only an in-app test can show: leaving the screen still saves, because `onCleared` launches the save in the app-lifetime `JournalSaveScope`.
- **View model tests.** The new `JournalViewModelTest` cases use a fixed `Clock` and the test dispatcher, and control timing with `CompletableDeferred`. They check real ordering behavior, not implementation details:
    - a save goes out over the version that the previous save returned (the mutex serializes the saves);
    - no autosave goes out while a conflict waits for a decision.
- `HabitsViewModelTest` adds answers that arrive in reverse order and a check-off answered after a reload. Both match `HabitsViewModel.toggle`, which updates the habit by id in the latest state.
- **Screen tests.** The `JournalScreenTest` and `HabitsScreenTest` additions cover these states against the real strings and tags: loading, photo upload, conflict with an entry deleted elsewhere, a save the server refused, and dismissing a message.
- **Test data** is fictitious: "Walked by the lake", "Drink water", `access-fictitious`, `cockpit-file:fictitious`, and made-up ids in UUIDv7 format.
- **No test was weakened.** The only edited test is the 409 create-conflict test. It was renamed and kept, and a 428 test was added next to it.

## Addendum: tests added in 9cfeaf64c

`git diff 43c5a2346..9cfeaf64c -- android` changes only `JournalRepositoryTest.kt`. No production code changed.

- **`a conflict whose current entry cannot be fetched is reported as that problem, never as a conflict`** checks two cases:
    - A 412 followed by a 503 on the refetch gives `Offline`.
    - A 428 followed by a 403 on the refetch gives `Failed(403)`.

  This matches `JournalRepository.conflict()` (`:135-140`): the refetch goes through `load`, where 5xx counts as offline and 4xx as failed. The banner never opens without the server's data, and the save stays retryable. Each case uses exactly the two queued responses, so nothing is left over in the MockWebServer queue.
- **`an unreadable answer is a failure without a status`** checks two cases:
    - A GET body with an invalid id and missing fields gives `JournalResult.Failed(null)`.
    - A PUT answer whose `updatedAt` is not a date gives `JournalSaveResult.Failed(null)`.

  This matches the `SerializationException` / `IllegalArgumentException` / `DateTimeException` branches of `load` (`:151-156`). Both assertions hold whichever of these exceptions the decoder throws. The test pins the behavior that matters (no status, no crash), not the exception type.
- Both tests use the class's existing fictitious fixtures (`id`, `base`, `day`) and contain no timing, so they cannot be flaky.

Verdict on the addendum: correct, meaningful tests. No findings.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Notes for the parent (process, not code findings)

- Commit 9cfeaf64c also updates `reviews/and-journal-habits/tests.md`. Before `done`, check that it now reports the final revision and the final coverage run, not the earlier "partial-failed" state.
- When I checked, `python tasks.py verify and-journal-habits` showed both criteria as not yet verified (`[ ]`). You still have to record the evidence.
- tasks/and-journal-habits.md contains the first assumption line twice (lines 18 and 19). This does not affect the code; a human reviewing the assumptions can ignore the duplicate.

## Review conclusion

No material issues found.
