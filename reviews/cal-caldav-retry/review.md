# Code Review

Date: 2026-10-26

Task: cal-caldav-retry - CalDAV: survive dropped connections (flaky Radicale integration tests)

Scope: `git diff f21b2bb98..HEAD -- server` at the final revision (re-review). Task and board files outside `server/` were read for context only.

Requirements:
- tasks/cal-caldav-retry.md (goal, acceptance criteria, notes, recorded assumptions)
- AGENTS.md project notes (logging rules, lint, test conventions)
- docs/ARCHITECTURE.md conventions
- Parent instructions: no Gradle runs by the reviewer. The parent reports `./gradlew ktlintFormat ktlintCheck test --tests '*CalDavClientTests'` -> 27 tests, 0 failures, and JaCoCo CalDavClient.kt lines 162/168, branches 112/118. Criterion 3 (10 consecutive `--rerun` runs and the cause note) is still pending parent evidence and is not treated as a code finding.

Git range:
- Branch: main
- HEAD: 49d295b7110dc19a18c198a2438d52b1a31e540b
- Base/start: f21b2bb9801928098c169801b945e1b2efc5a173
- Commits reviewed: 3 code commits (b2afa35f7, 88d807e24, 49d295b71); the other commits in the range only touch `tasks/`
- Uncommitted changes: only the untracked `reviews/cal-caldav-retry/` directory
- Since the previous review (against b4b9a475e): `git diff --stat b4b9a475e HEAD~1 -- server` is empty, so the production code and the Radicale test helper are unchanged. The only new code is 49d295b71, which adds 5 tests and the `ScriptedHttpClient` helper to `CalDavClientTests.kt`.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Disposition of earlier findings

- CR-1 from the previous review (criterion 3: the 10-run `--rerun` evidence and the note on the cause of the drops). The parent says the loop is running and the note will follow. This is task evidence, not code, so it is not repeated here. The criterion must still be verified with the 10 consecutive runs, and the note must be in the task's Notes before `done`.
- CR-2 from the previous review (a retried If-Match PUT/DELETE already applied by the server shows up as a 412 conflict). This is now recorded as an ASSUMPTION in tasks/cal-caldav-retry.md:34, with a follow-up direction. Resolved as a documented known effect.

## Notes on the new tests (not findings)

I checked the new tests in 49d295b71 against `CalDavClient.kt:81-126` and `:201-227`:

- **Backoff + 503** (`CalDavClientTests.kt:304-315`):
    - The elapsed time is checked against `DROPPED_CONNECTION_BACKOFF` as a lower bound only, so the check is deterministic.
    - The second answer still goes through the 5xx check.
    - The response body containing the password does not appear in the message; this is checked by exact message equality.
- **Redirect target drop** (`:317-331`):
    - `sendRetryingDrop` runs per redirect hop, so the retry goes to `/dav/new/`, not back to `/dav/old/`.
    - Both attempts carry the body, Authorization and Depth headers.
- **Unconditional PUT at a redirect target** (`:333-342`): exactly 1 request reaches the target, which matches `repeatable("PUT", emptyMap()) == false`.
- **Interrupt during the backoff** (`:344-366`):
    - Attempt 1 sets the interrupt flag and throws an `EOFException` before the real send, so the BodyHandler never runs and `answered` stays false.
    - `Thread.sleep` then throws at once, and `send()` restores the flag (`:107-109`).
    - The test checks the flag, `sends == 1` and that WireMock got no requests.
    - It clears the flag in `finally` before the WireMock assertions, so it cannot leak into later tests.
- **Retry log line** (`:368-393`):
    - The test checks the INFO line for the method and host.
    - It checks that the client's log lines contain no password, no Basic credentials and no body marker, which matches the AGENTS.md logging rule.
    - The test uses `OutputCaptureExtension` and `.single { ... }`. That is safe because no JUnit parallel execution is configured: there is no `junit-platform.properties` and no parallel settings in `build.gradle.kts`.
- **`ScriptedHttpClient`** (`:511-555`):
    - It delegates to a real `CalDavClient.httpClient()` and counts sends.
    - It is private to the test class.
    - It is a minimal way to inject a failure at a point WireMock cannot reach (interrupt plus drop in the same attempt).
- **Criteria 1 and 2:** the code and test coverage are as described in the previous review. The new tests close the remaining gaps: backoff timing, redirect interaction, the interrupt path and log content.
- **Not run here:** the reviewer did not run Gradle, as instructed. The test and coverage figures above are as reported by the parent.

## Review conclusion

No material issues found. The code at 49d295b71 is ready on the review side. Criterion 3 still needs the parent's evidence before `done`: 10 consecutive `--rerun` passes, plus the investigation note in the task's Notes.
