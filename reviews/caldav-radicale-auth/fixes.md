# Fix Report

Date: 2026-10-26

Task: caldav-radicale-auth - CalDAV Radicale tests run without authentication

Scope: current task; findings CR-1 and CR-2 from reviews/caldav-radicale-auth/review.md

## Fixed findings

### CR-1 — Anonymous PROPFIND bypasses the connection-drop retry used by every other Radicale request

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavRadicaleSyncTests.kt`

Problem:
The test `Radicale refuses a request without credentials, so the sync runs authenticated` called `http.send(...)` directly. The same applied to the test the test-writer added, `Radicale refuses the right user with a wrong password, so it checks passwords against htpasswd`. Every other Radicale request in the class goes through `dav()`, which uses `http.sendRetryingDrop(..., repeatable = true)`. If Radicale dropped the connection before answering, these two tests would fail with an IOException and would not check the status code.

Fix:
Both tests now call `http.sendRetryingDrop(request, HttpResponse.BodyHandlers.discarding(), repeatable = true)`, the same way `dav()` does. PROPFIND is read-only, so repeating it is safe. `sendRetryingDrop` is `internal` in the same package (`CalDavClient.kt`), so no import is needed. The requests, the assertions and the rest of the file are unchanged. The trailing-comma formatting with tabs follows ktlint.

Validation:
- `grep -n "http.send(" CalDavRadicaleSyncTests.kt` -> no matches. Every Radicale request now goes through `sendRetryingDrop` — Pass
- Compile, ktlint and integration test: not run by the fixer, because sub-agents cannot run Gradle here. The parent worker runs them.

### CR-2 — The anonymous-request test name claims more than the test proves

Files changed:
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavRadicaleSyncTests.kt`

Problem:
The test `Radicale refuses a request without credentials, so the sync runs authenticated` also passes with `[auth] type = none`, because the owner_only rights alone refuse an anonymous request. Its name suggested that it guards authentication, which it does not. The wrong-password test is the real guard.

Fix:
Renamed the test to `Radicale refuses a request without credentials`. The body, the assertions and the rest of the file are unchanged.

Validation:
- `grep -rn "so the sync runs authenticated" server/src docs` -> no matches, so no other reference to the old name remains — Pass
- Compile, ktlint and integration test: not run by the fixer, because sub-agents cannot run Gradle here. The parent worker runs them.

## Not fixed

None.

## Validation summary

Focused tests:
- `./gradlew integrationTest --tests '*CalDavRadicale*'`: Not run by the fixer. The parent worker runs it.

Full test suite:
- Not run by the fixer. The parent worker runs it.

Lint/type-check/build:
- `./gradlew ktlintCheck` / compile: Not run by the fixer. The parent worker runs it.

`.taskcheck`:
- Not run. The parent worker runs it.

Remaining unresolved findings:
0

## Final status

All validated findings fixed. Validation (compile, ktlint, Radicale integration tests) is still pending and the parent worker will run it.
