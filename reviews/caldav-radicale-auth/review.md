# Code Review

Date: 2026-10-26

Task: caldav-radicale-auth - CalDAV Radicale tests run without authentication

Scope: final revision 0996f9d4aa4c6afcf9731331306008834228f32b against base 79fa6f3e1 (`git diff 79fa6f3e1..0996f9d4a -- server`). This is the third review. The first review covered 5aac914a8 (rebased as 34f4d9471). The second covered 4b9699b32. Since then 0996f9d4a only renames one test in `CalDavRadicaleSyncTests.kt` (`git diff 4b9699b32..0996f9d4a -- server`, one line).

Requirements:
- tasks/caldav-radicale-auth.md (goal and the single acceptance criterion)
- AGENTS.md project notes (lint, fictitious test data, Testcontainers suites outside `.taskcheck`)
- Reference implementation: server/src/test/kotlin/ch/sonensei/cockpit/contacts/carddav/CardDavRadicaleSyncTests.kt
- reviews/caldav-radicale-auth/fixes.md (fixes for CR-1 and CR-2)

Git range:
- Branch: main
- HEAD: 0996f9d4aa4c6afcf9731331306008834228f32b
- Base/start: 79fa6f3e10e846bd9971b504db84fb1296d67df7
- Commits reviewed: 3 code commits (34f4d9471, 4b9699b32, 0996f9d4a). The claim, heartbeat and verify board commits were not reviewed as code.

Summary: 0 P0, 0 P1, 0 P2, 0 P3 open (2 P3 from earlier reviews, both resolved)

## What was checked

- The configuration change from the first review is unchanged. The config and htpasswd file are in `/etc/radicale`, and the container starts with `--config /etc/radicale/config`, as in CardDavRadicaleSyncTests.
- CR-1 (P3) stays resolved. Both auth tests use `http.sendRetryingDrop(..., HttpResponse.BodyHandlers.discarding(), repeatable = true)`. No direct `http.send(` call remains in the class.
- The wrong-password test (`Radicale refuses the right user with a wrong password, so it checks passwords against htpasswd`) is unchanged. It is the real guard for the acceptance criterion: with `[auth] type = none` and owner_only rights, only a password check can refuse this request. Its assertion that `radicale.logs` contains `auth type is 'radicale.auth.htpasswd'` covers the "log shows auth type htpasswd" part of the criterion.
- CR-2 (P3) is resolved in 0996f9d4a. The anonymous test is now named `Radicale refuses a request without credentials`, which states only what it checks. It no longer claims to prove that authentication is active. Its body is unchanged. No other reference to the old name remains in `server/` or `docs/`.
- Scope: only test code inside the task's `touches`. No production code changed and no secrets were added. The rename is a code change, so this re-review is needed for freshness.
- Evidence: the parent reports `cd server && ./gradlew ktlintCheck integrationTest --tests '*CalDavRadicale*'` on 0996f9d4a -> BUILD SUCCESSFUL, 12 tests, 0 failures/errors/skipped. I could not rerun Gradle myself. The verify entry on the task cites the 12-test run on rev 4b9699b32 with Podman. Between that revision and 0996f9d4a only a test name changed, so the recorded evidence still fits the final code.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [x] **CR-1 | Anonymous PROPFIND bypasses the connection-drop retry used by every other Radicale request**
    - Status: resolved in 4b9699b32 (both auth tests use `sendRetryingDrop` with `repeatable = true`).

- [x] **CR-2 | The anonymous-request test name claims more than the test proves**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavRadicaleSyncTests.kt:367`
    - Status: resolved in 0996f9d4a. The test was renamed to `Radicale refuses a request without credentials`.

## Review conclusion

No material issues found.
