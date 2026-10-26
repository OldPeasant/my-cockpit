# Test Report

Date: 2026-10-26

Task: caldav-radicale-auth - CalDAV Radicale tests run without authentication

Scope: `git diff 79fa6f3e10e846bd9971b504db84fb1296d67df7..0996f9d4aa4c6afcf9731331306008834228f32b -- server` (base 79fa6f3e1 = claim commit, final revision 0996f9d4a). Task commits touching code, after the rebase onto main: c670728bb (Radicale config under /etc/radicale, anonymous 401 test; was 34f4d9471), d6523fcb1 (test-writer's wrong-password test with htpasswd log check, plus the fixer's switch of both new tests to `http.sendRetryingDrop(..., repeatable = true)` for CR-1; was 4b9699b32) and 0996f9d4a (CR-2: rename of the anonymous-request test, one line). The only changed server file is `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavRadicaleSyncTests.kt`. No production files changed. No uncommitted source or test changes are included.

Requirements:
- `tasks/caldav-radicale-auth.md` (Goal and its single acceptance criterion)
- `docs/QUALITY.md` (report contract)
- `docs/ARCHITECTURE.md` "Coverage", AGENTS.md project notes
- Existing pattern: `CardDavRadicaleSyncTests` (config under /etc/radicale, `--config /etc/radicale/config`)
- `reviews/caldav-radicale-auth/fixes.md` (CR-1 and CR-2 fixes)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/caldav/CalDavRadicaleSyncTests.kt` (blob ace118587e2480637858347a9139d3c78cfccdd6 at 0996f9d4a)

## Tests added or updated

Count: 2 new tests in the task (1 by the implementation, 1 by the test-writer), plus the changed container setup. No test code was edited by the test-writer in this final pass.

- `Radicale refuses a request without credentials` (implementation; renamed for CR-2 from `Radicale refuses a request without credentials, so the sync runs authenticated`): anonymous PROPFIND on `/alice/` gives 401. The new name states only what it checks: on its own this does not prove htpasswd auth, because `owner_only` rights deny anonymous access even with `[auth] type = none`.
- `Radicale refuses the right user with a wrong password, so it checks passwords against htpasswd` (test-writer): PROPFIND (Depth 0) on `/alice/` as `alice` with a wrong password gives 401, and the container log contains `auth type is 'radicale.auth.htpasswd'`. With `type = none`, Radicale trusts the login name and `owner_only` would answer 207, so only a real htpasswd check makes this pass. The log assertion checks the criterion wording ("log shows auth type htpasswd") inside the same Testcontainers run.

Both use `http.sendRetryingDrop(..., repeatable = true)` after the CR-1 fix, matching the other requests in the class (retry on a dropped pooled connection). The retry does not mask an auth failure: a 401 is a response, not a dropped connection, so it is returned and asserted.

Final-pass review: the diff from the previously reported revision (4b9699b32, now d6523fcb1) to 0996f9d4a is a one-line test-name change; test bodies, setup and assertions are unchanged, so no new gap. The setup copies config and users to /etc/radicale and starts Radicale with `--config /etc/radicale/config`, so the image's default /config (no auth) cannot override it, and the existing 10 sync tests still authenticate as `alice` with the right password against that setup.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| CalDAV Radicale tests run with htpasswd auth (log shows auth type htpasswd) | `Radicale refuses the right user with a wrong password, so it checks passwords against htpasswd` (wrong password 401 plus log assertion); `Radicale refuses a request without credentials` (anonymous 401) | `cd server && ./gradlew ktlintCheck integrationTest --tests '*CalDavRadicale*'` (run by the parent on 0996f9d4a) | Pass: BUILD SUCCESSFUL, tests=12 skipped=0 failures=0 errors=0, including the `radicale.logs shouldContain "auth type is 'radicale.auth.htpasswd'"` assertion |
| Log shows auth type htpasswd (manual container check) | none (container run) | `podman run --rm -v .tmp/radcfg:/etc/radicale:ro,Z docker.io/tomsquest/docker-radicale:3.5.4.0 /venv/bin/radicale --config /etc/radicale/config` (run by the parent earlier; config unchanged since) | Pass: "auth type is 'radicale.auth.htpasswd'", "auth htpasswd file: '/etc/radicale/users'" |
| `./gradlew integrationTest --tests '*CalDavRadicale*'` passes, existing sync tests still pass authenticated | all 12 tests in `CalDavRadicaleSyncTests` | same Gradle command as the first row | Pass: tests=12, failures=0, errors=0 |
| Lint on the edited test file | `CalDavRadicaleSyncTests.kt` | `ktlintCheck` in the same Gradle command | Pass (BUILD SUCCESSFUL) |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck integrationTest --tests '*CalDavRadicale*'` (run by the parent on revision 0996f9d4aa4c6afcf9731331306008834228f32b; this sub-agent cannot run Gradle)

Result:

Pass: BUILD SUCCESSFUL, tests=12, skipped=0, failures=0, errors=0.

## Full test execution

Command:

`sh scripts/check.sh`

Result:

Not run by the test-writer (this sub-agent cannot run Gradle; the parent runs it before `done`). The change is limited to an `@ExternalTest` integration test class, which `.taskcheck` does not run, so the focused integration run above is the relevant evidence.

## .taskcheck

Result:

Not run by the test-writer; the parent runs `.taskcheck` before `done`.

## Bugs found

None in production code. One test gap was found and closed earlier in this task: the anonymous-401 test alone could not detect a missing authentication setup (closed by the wrong-password test).

## Coverage

Status: not applicable. The task changes test code only (`CalDavRadicaleSyncTests.kt`). No production file was added or modified, so the task coverage scope is empty. This is not 100%.

Metric: whole-file coverage of task-changed production files (none in scope).
Changed-line coverage: not measured (no changed production lines).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: caldav-radicale-auth, base 79fa6f3e10e846bd9971b504db84fb1296d67df7, final revision 0996f9d4aa4c6afcf9731331306008834228f32b, task code commits c670728bb, d6523fcb1 and 0996f9d4a (from `git log 79fa6f3e1..0996f9d4a -- server`; the other commits are task-state commits), file scope from `git diff --stat 79fa6f3e1..0996f9d4a -- server` (1 server test file).
- Source/test snapshot identifier (including uncommitted inputs): commit 0996f9d4a, test file blob ace118587e2480637858347a9139d3c78cfccdd6; no uncommitted inputs.
- Exact included files; renamed/deleted/excluded files and reasons: no production files. Excluded: `CalDavRadicaleSyncTests.kt` (test code), `tasks/` files (task metadata).
- Provider/version, command, working directory, and test selection: none run (empty production scope).
- Run start/completion time and exit status: N/A.
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: N/A (no coverage run).
- Final run after latest test additions and production fixes: yes for the tests (parent integration run on 0996f9d4a, after the CR-1 and CR-2 fixes); no coverage run is applicable.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files changed) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| none | none | No production code in scope |

## Unable to test

- Nothing remains untested for the acceptance criterion. The test-writer itself cannot run Gradle in this sub-agent; all execution results above come from the parent's run on the final revision.

## Summary

Tests added and all validation passed. The wrong-password test (with the htpasswd log assertion) and the anonymous-401 test pass together with the 10 existing sync tests on the final revision 0996f9d4a (tests=12, failures=0, ktlint clean); no production defects found and task-scoped coverage is not applicable (test code only).
