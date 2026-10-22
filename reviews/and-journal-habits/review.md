# Code Review

Date: 2026-10-22

Task: and-journal-habits - Android journal and habits

Scope: `git diff f3c2102802292ecc2e011934b7c70f3128d02bb9..HEAD` (task commits 3c75b4067, 64762cd29, 629ca2f7a), committed changes only

Requirements:
- tasks/and-journal-habits.md (acceptance criteria and recorded assumptions)
- SPEC.md §8.5, §9.2 item 8
- AGENTS.md "Project notes", docs/ARCHITECTURE.md
- API contract: api/openapi.yaml (`putJournalEntry`, `setHabitCheck`) and the server implementation (`JournalEntryController`, `core/web/ETags.kt`)

Git range:
- Branch: main
- HEAD: 629ca2f7a06ea36369a7a1b5fcd4932a655f2f48
- Base/start: f3c2102802292ecc2e011934b7c70f3128d02bb9
- Commits reviewed: 3

Summary: 0 P0, 1 P1, 0 P2, 1 P3

Not verified by the reviewer: I did not run Gradle. The parent reports that `./gradlew ktlintCheck lintDebug testDebugUnitTest --tests 'ch.sonensei.cockpit.journal.*' --tests 'ch.sonensei.cockpit.habits.*'` passes (50 tests). `tasks.py verify` could not be run here (permission denied), so I did not check the recorded criterion evidence.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Creating today's entry when another device already created it fails permanently (428 is not handled as a conflict)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalRepository.kt:118`, `:123`, `:162`
    - Category: Integration
    - Problem: When the screen loaded today with no entry (`base == null`), `save` sends the PUT without `If-Match`. If the entry was created elsewhere in the meantime (for example in the web app), the server checks the existing version with `ETags.checkIfMatch(null, version)` (`server/.../journal/entries/api/JournalEntryController.kt:114-117`), which throws `PreconditionRequiredException`, so the response is **428**. The contract documents this too (`api/openapi.yaml` putJournalEntry: `"428": If-Match is missing`). The server only answers 409 when the two creates race on the unique index (`JournalEntryService.kt:135`). `CONFLICTS = setOf(404, 409, 412)` leaves out 428, so the result is `JournalSaveResult.Failed(428)`. The view model shows "failed", and every retry (`saveNow`) sends the same request without `If-Match` and gets 428 again. The conflict banner (keep mine / use theirs) never appears. `onCleared` hits the same 428 when the user leaves, so the text written on the phone is silently lost. The repository test `a create refused because the day got an entry elsewhere is a conflict` (`JournalRepositoryTest.kt:134`) mocks a 409, which is not what the server returns in this case. That is why the test suite does not catch the bug.
    - Why it matters: Writing the same day's journal on two devices is the normal multi-device case the ETag handling exists for. Here it leads to a stuck save and lost text, with no way to recover other than leaving and reopening the screen.
    - Suggested fix: Treat 428 as a conflict in `save` when `base == null`, or simply add 428 to `CONFLICTS`. That way the server's current entry is loaded and the conflict banner is shown. Add a repository test with a 428 response, and change the existing create-conflict test to the status the server actually sends.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | Journal date comes from the device clock while the server rejects dates after the user's today**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/journal/JournalViewModel.kt:86` (`LocalDate.now(clock)`)
    - Category: Correctness
    - Problem: The server refuses `PUT /journal/entries/{date}` with 400 when the date is after today in the user's configured time zone (`JournalEntryService.kt:118`). The phone uses the device date, so a device that is ahead of the user's server time zone (travel, just after midnight) gets a permanent "failed" status. The Habits screen avoids this by using the server's `stats.today`. The device-clock choice is a recorded assumption, so this is low priority, but the failure mode is not mentioned in that assumption and gives the user nothing to act on.
    - Suggested fix: Either note the limitation in the assumption, or map a 400 for a future date to a clear message. Another option is to take "today" from the server, as Habits does.

## Review conclusion

Material issues found; fix before completing the task.
