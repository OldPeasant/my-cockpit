# Test Report

Date: 2026-10-21

Task: p2-smoke - Phase 2 demo script incl. restore on a fresh install

Scope: `git diff 8275cd090f113d81f2ebd8175fd98ff526a4e992..19092c54e76f4a20dfd2c219a910e94d9616b13e` (second pass on
the final code). The base and task commits were supplied by the parent and match the diff and the task's
`touches`/notes: 809cfc69, 1008fecf, 6057656f, f48fdacc (implementation), 0ed7cd0a (first test-writer additions and
reports), 19092c54 (fixer: CR-1/TW-2, CR-2). Branch `main`. No uncommitted source or test changes. The working tree
only has `reviews/p2-smoke/review.md` modified, which comes from the code-reviewer and is not an input to the tests.
Changed files in the task diff: `web/e2e/phase2/demo.spec.ts` (new e2e spec), `web/e2e/phase2/zip.ts` (zip reader
for the spec), `web/e2e/support/run-e2e.mjs` (waits for `server-fresh`, passes `E2E_FRESH_BASE_URL`),
`deploy/docker-compose.e2e.yml` (`server-fresh` + `postgres-fresh`), `web/e2e/README.md`, plus `reviews/p2-smoke/*`.
No production source.

Requirements:
- `tasks/p2-smoke.md` acceptance criteria 1-3 and its recorded assumptions
- `SPEC.md` §13.2 Phase 2 steps 1-5, §10 (backup format: `readable/`, `files/`, secrets without passphrase)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`
- Findings CR-1, CR-2 (`reviews/p2-smoke/review.md`) and TW-2 (first pass), fixed in `reviews/p2-smoke/fixes.md`

## Test files changed

- `web/e2e/phase2/demo.spec.ts` (first pass; committed in 0ed7cd0a. No test changes in this second pass)

## Tests added or updated

Count: 0 in this pass. The first pass added 2 assertion blocks to existing tests (no new test cases):

- Step 4: `expectDemoData(before)` checks that the snapshot that step 5 compares against really holds the phase 1
  and 2 data:
  - the changed settings;
  - the contexts, area, projects and tasks;
  - the Logbook and Trash items;
  - the checklist;
  - the note with its `cockpit-file:` image;
  - the three Documents files, with their sha256;
  - the six link entries.

  So `expect(after).toEqual(before)` can't pass when both sides are empty.
- Step 4: every image link in every `readable/**/*.md` must resolve to a zip entry (`Zip.has`).

Fixer changes reviewed in this pass (19092c54):
- CR-1 / TW-2: step 5 now asserts that `/api/v1/setup/status` answers OK. It then calls
  `test.skip(!setupRequired, ...)` (the message gives the recreate command) instead of asserting `setupRequired: true`. The call sits in a
  `try { } finally { }` with no `catch`, so Playwright's skip is not swallowed and the contexts are still closed.
  The README documents the recreate command.
- CR-2: `report.getByText('not included', { exact: true })` is checked by the run below (step 5 passed).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: phase2 spec covers steps 1-5 | `Phase 2 demo › 0.`-`5.` | `npm --prefix web run e2e -- --project=phase-demos > .tmp/tw2-e2e.log 2>&1` (compose stack via run-e2e.mjs, chromium) | Pass (6/6, none skipped) |
| AC1: step 5 restores the downloaded zip into a fresh stack (new volumes) and compares data via the API | `Phase 2 demo › 5. restored into a fresh install ...`, with `expectDemoData` in step 4 | as above | Pass (step 5 ran, 11.5 s; not skipped) |
| AC2: `readable/` Markdown files exist, image links resolve inside the zip | `Phase 2 demo › 4. a backup zip ...` | as above | Pass |
| AC3: phase 0-1 specs still pass | `Phase 0 demo › 1.`-`5.`, `Phase 1 demo › 0.`-`9.` | as above | Pass (5 + 10) |
| SPEC §13.2 step 5: without a passphrase, TOTP re-enrollment is forced; login works | step 5 (API enrollment, then web login) | as above | Pass (re-enrollment goes through the API, per the task assumption) |
| CR-2: exact "not included" match on the Secrets row | step 5 | as above | Pass |
| CR-1 / TW-2: a second run against a kept stack skips step 5 with the recreate command | step 5, skip branch | `E2E_KEEP=1 npm --prefix web run e2e -- --project=phase-demos`, then a re-run with `E2E_BASE_URL`/`E2E_FRESH_BASE_URL`/`E2E_IMAP_PORT` | Not run: the `E2E_KEEP=1` command was denied by the session's permission system (the fixer hit the same denial). Reviewed statically only, see above |
| Restore via CLI (`--restore`) | none | - | Not run: out of scope for this task per its recorded assumption (UI restore only) |

## Focused test execution

Command:

`npm --prefix web run e2e:typecheck`; `npx --prefix web eslint web/e2e`; `npx --prefix web prettier --check web/e2e web/e2e/README.md`

Result:

Pass (all exit 0, eslint printed nothing, prettier reported "All matched files use Prettier code style!")

Command:

`npm --prefix web run e2e -- --project=phase-demos > .tmp/tw2-e2e.log 2>&1`

- Runs run-e2e.mjs: `compose up --build` of `deploy/docker-compose.e2e.yml` (project `cockpit-e2e-72159f73`,
  including `server-fresh` and `postgres-fresh`), then chromium, then `down -v`.
- 2026-10-21T08:05:25Z - 08:07:33Z, exit 0.

Result:

Pass: 21 passed (1.0 m): phase0 5, phase1 10, phase2 6. None skipped: step 5 ran against a freshly created
`server-fresh`. The stack was torn down afterwards. While stopping, compose logged SIGTERM-to-SIGKILL warnings for the
containers. These have no effect on the result.

## Full test execution

Command:

`npm --prefix web run e2e -- --project=phase-demos` (above)

Result:

Pass. I did not re-run `sh scripts/check.sh` (`.taskcheck`) in this pass. Its inputs (lint and unit tests of
`server/`, `web/src`, `android/`) did not change since the first pass, where it passed, and it doesn't run e2e. The
fixer only changed `web/e2e/`. The parent runs `.taskcheck` before `done`. I didn't run the other Playwright projects
(`chromium`, `inbox-exclusive`): the task doesn't change them.

## .taskcheck

Result:

Not run in this pass. It passed in the first pass on f48fdacc plus the test additions (`sh scripts/check.sh`,
exit 0). Since then only `web/e2e/` and docs have changed, and `.taskcheck` covers neither.

## Bugs found

None. No test exposed a production defect.

Observations for the parent (no production fix required):

- `TW-1` (Low, test strength, unchanged): both servers share `COCKPIT_MASTER_KEY`. That matches a real redeploy, and
  phase 1-2 data holds no secrets encrypted with the master key. A later phase that has IMAP/OAuth secrets may want a
  different key for the fresh server.
- `TW-2`: resolved by the fixer (CR-1). The skip branch was reviewed statically but not run (see above).
- `TW-3` (Low, scope note, unchanged): `web/e2e/support/run-e2e.mjs` and `web/e2e/README.md` are outside the task's
  `touches`. This is recorded in the task's first assumption.
- `TW-4` (Low, test strength, new): the skip in step 5 cannot tell a deliberate re-run against a kept stack from a
  wrongly non-fresh `server-fresh` in the normal `npm run e2e` flow (for example, if a future compose change gives it
  a persistent volume). In that case the run would report "1 skipped" with exit 0, where before it failed. Today
  run-e2e.mjs runs `down -v` before `up`, and the compose file uses its own Postgres on tmpfs, so the normal flow is
  always fresh: this run had 0 skipped. Possible hardening: fail instead of skipping when the stack was started by
  run-e2e.mjs, e.g. with a flag that run-e2e passes. This is optional and not required by the criteria.

## Coverage

Status: not applicable. The task changes no production source. Its code is e2e test code and test infrastructure,
which no configured coverage provider instruments.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source:
  - Task `p2-smoke`, base `8275cd090f113d81f2ebd8175fd98ff526a4e992`, HEAD
    `19092c54e76f4a20dfd2c219a910e94d9616b13e`.
  - Task commits from the parent: 809cfc69, 1008fecf, 6057656f, f48fdacc, 0ed7cd0a, 19092c54. They are consistent with
    `touches` and the task notes.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 19092c54 with no uncommitted inputs. sha256 of
  the inputs, taken before the e2e run and re-checked after it (unchanged):
  - `web/e2e/phase2/demo.spec.ts`: `bc257e8e6c4beb4c8171c2594ae393c6f6fcb234e254163f7ba89a75abb3c9ec`
  - `web/e2e/phase2/zip.ts`: `b146e60fd93629d1f5ed166ca5f994dab5f10a50cce5a8c650ed14ef242c6113`
  - `web/e2e/support/run-e2e.mjs`: `989a592b8dc790e4a8d1e8168152f0f0b879c70796997098e45f696e5f44a6e9`
  - `deploy/docker-compose.e2e.yml`: `083d935c33eba4dfacc73a6a2dd2776ba5aa68e252ffb2838199a2f5a258efed`
  - `web/e2e/README.md`: `3cf08443e73f8d521fb347b8ed3338ff84bb8cbedc71076e1f2744dcf3d4954e`
- Exact included files: none. `git diff --name-only 8275cd09..19092c54 -- server/src/main web/src android/app/src/main`
  (the scope rule of docs/ARCHITECTURE.md "Coverage") is empty. Excluded, with reasons:
  - `web/e2e/phase2/demo.spec.ts`, `web/e2e/phase2/zip.ts`: e2e test code and its helper.
  - `web/e2e/support/run-e2e.mjs`: e2e runner script.
  - `deploy/docker-compose.e2e.yml`: test stack configuration.
  - `web/e2e/README.md`, `reviews/p2-smoke/*`: docs and reports.

  No renames or deletions.
- Provider/version, command, working directory, and test selection: no coverage command was run. The configured web
  provider (`npm run coverage`, Vitest + `@vitest/coverage-v8`) covers only the `web/src` unit tests. Playwright under
  `web/e2e/` has no coverage instrumentation, and JaCoCo and Android coverage don't apply. Measuring the e2e helper
  would need new coverage configuration, which is outside this role.
- Run start/completion time and exit status: not applicable (no coverage run). The e2e run that exercises all task
  code ran 2026-10-21T08:05:25Z - 08:07:33Z, exit 0.
- Generated report paths and report hashes/run identifier:
  - Coverage: none.
  - Playwright log: `.tmp/tw2-e2e.log` (Git-ignored), sha256
    `f056c80118f2eaf79704649caf4fd75e30d8eb6a579f49c325e22c615b28ffa2`.
  - HTML report: `web/playwright-report/`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Fresh compose stack and new log file for this run;
  input hashes are the same before and after.
- Final run after latest test additions and production fixes: yes. It ran on the final reviewed code 19092c54,
  including the fixer's changes. No production code changed.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files in scope) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/e2e/phase2/demo.spec.ts` step 5 `test.skip(!setupRequired, ...)` skip branch | a re-run against a kept stack skips with a recreate hint (CR-1) | Not executed: the `E2E_KEEP=1` run was denied by the session's permissions. Reviewed statically. The normal flow takes the non-skip branch (verified). See TW-4 |
| `web/e2e/phase2/zip.ts` (not instrumented) | correct reading of server zips | exercised against real archives in steps 2 and 4. Its failure modes throw. `web/e2e/` has no unit runner |
| `web/e2e/support/run-e2e.mjs` fresh-server wait/log path on failure | diagnostics when the stack fails to start | the success path runs in every e2e run. The failure path (health timeout) was not triggered and is not material to the criteria |

## Unable to test

- The step 5 skip path on a kept stack (CR-1). The `E2E_KEEP=1` run was not permitted in this session. Follow-up:
  optional. A human or an agent with permission can run `E2E_KEEP=1 npm --prefix web run e2e -- --project=phase-demos`,
  then re-run with `E2E_BASE_URL`, `E2E_FRESH_BASE_URL` and `E2E_IMAP_PORT` set to the kept stack and
  `-- --project=phase-demos e2e/phase2`, and expect step 5 to be skipped with the recreate message. Then run the
  printed `down -v` command.
- The CLI restore path (`--restore`): out of scope per the task's recorded assumption. No follow-up from this task.
- The web TOTP re-enrollment screen: not built yet (task web-totp-reenroll). The spec uses the API, per the
  assumption.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The final code passed typecheck, eslint,
prettier and the real phase-demos e2e (21 passed, step 5 ran). The new step 5 skip branch was not run because the
kept-stack run was denied, and it is documented above.
