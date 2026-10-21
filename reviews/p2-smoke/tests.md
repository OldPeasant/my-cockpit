# Test Report

Date: 2026-10-21

Task: p2-smoke - Phase 2 demo script incl. restore on a fresh install

Scope: `git diff 8275cd090f113d81f2ebd8175fd98ff526a4e992..f48fdacc23ef040961b8f1589a22207be498917f` (base and task
commits 809cfc69, 1008fecf, 6057656f, f48fdacc supplied by the parent, matched against the diff and the task's
`touches`/notes), branch `main`, plus the uncommitted test change of this report in `web/e2e/phase2/demo.spec.ts`.
Changed files in the task diff: `web/e2e/phase2/demo.spec.ts` (new e2e spec), `web/e2e/phase2/zip.ts` (zip reader
for the spec), `web/e2e/support/run-e2e.mjs` (waits for `server-fresh`, passes `E2E_FRESH_BASE_URL`),
`deploy/docker-compose.e2e.yml` (`server-fresh` + `postgres-fresh`), `web/e2e/README.md`. No production source.

Requirements:
- `tasks/p2-smoke.md` acceptance criteria 1-3 and its four recorded assumptions
- `SPEC.md` §13.2 Phase 2 steps 1-5, §10 (backup format: `readable/`, `files/`, secrets without passphrase)
- `docs/ARCHITECTURE.md` "Coverage", `AGENTS.md`

## Test files changed

- `web/e2e/phase2/demo.spec.ts` (uncommitted, this run)

## Tests added or updated

Count: 2 assertion blocks added to existing tests (no new test cases)

- Step 4: `expectDemoData(before)` - new helper asserting that the snapshot step 5 compares actually holds the
  phase 1 and 2 data: changed settings (dark, SUNDAY, energy labels), both contexts, the area, both projects, the
  seven open tasks, the Logbook task, the Trash item, the "Book flights" checklist (2 items in order), a note body
  with a `cockpit-file:` image, the three Documents files, every downloaded file content matching its recorded
  sha256, and exactly the three links of steps 0 and 3 seen from both ends (TASK-TASK, NOTE-PROJECT, FILE-TASK, 6
  entries). Before this, `expect(after).toEqual(before)` would also have passed if both sides were empty lists
  (e.g. after an API filter or paging change); now the equality is provably non-trivial.
- Step 4: every image link in every `readable/**/*.md` must resolve (relative to the Markdown file, URL-decoded) to an
  entry of the zip (`Zip.has`). The existing check covered only the one image of the "Trip to Zanzibar" note.

Reviewed and kept as is (they assert what the criteria say):
- Step 5 restores into `server-fresh` (own Postgres on tmpfs, own `/data`, `setupRequired: true` asserted first, so a
  reused, non-fresh server fails loudly), through the web restore form; compares a full API snapshot of the original
  (taken in step 4, right before the backup) with the restored server's, keyed and sorted by id, including ids,
  versions, note bodies, drive tree, file sha256 and downloaded-content hashes, per-item links, settings, Logbook,
  Trash. Different servers (`fresh.request` uses the `server-fresh` base URL), so the comparison is not self-equal.
- Step 5 TOTP: recovery-code login -> `totpEnrollmentRequired`, 403 on `/api/v1/tasks`, the old TOTP secret gives 401,
  enrollment + confirm, then a browser login with the new secret and the note image renders.
- Step 4 `readable/`: tasks per list, logbook, projects by area as Markdown, the note's Markdown contains the toggled
  checklist, exactly one image link which resolves under `files/` to the pasted PNG bytes; `files/` holds the moved
  and renamed tree with original bytes (60 MB file by sha256).
- Step 2: a chunk request is awaited (chunked upload), the 60 MB sha256 is checked on the server and in the folder zip.

Zip reader (`web/e2e/phase2/zip.ts`): no separate unit check added. `web/e2e/` has no unit runner (Vitest runs only
`web/src` through `ng test`; Playwright projects need the e2e stack and `E2E_BASE_URL`), and adding one would mean a
config change outside this role. The reader is exercised end-to-end against the server's real archives in steps 2
(folder zip, deflated, 60 MB entry, nested folder) and 4 (backup zip). Its failure modes are loud: an unsupported
method, a missing local header or EOCD, or a missing entry throws; Zip64 sizes (`0xFFFFFFFF`) would make the inflate
or the byte comparison fail rather than pass. Non-UTF-8-flagged names are read as latin1 (documented in the code).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: phase2 spec covers steps 1-5 | `Phase 2 demo › 0.`-`5.` | `npm --prefix web run e2e -- --project=phase-demos` (compose stack via run-e2e.mjs, podman, chromium) | Pass (6/6) |
| AC1: step 5 restores the downloaded zip into a fresh stack (new volumes) and compares data via the API | `Phase 2 demo › 5. restored into a fresh install ...` with `expectDemoData` in step 4 | as above | Pass |
| AC2: `readable/` Markdown files exist, image links resolve inside the zip | `Phase 2 demo › 4. a backup zip ...` (note image -> PNG bytes; all readable image links via `Zip.has`) | as above | Pass |
| AC3: phase 0-1 specs still pass | `Phase 0 demo › 1.`-`5.`, `Phase 1 demo › 0.`-`9.` | as above | Pass (5 + 10) |
| SPEC §13.2 step 5: without a passphrase, TOTP re-enrollment forced; login works | step 5 (API enrollment, then web login) | as above | Pass (re-enrollment through the API per the task assumption; the web enrollment screen is task web-totp-reenroll) |
| Restore via CLI (`--restore`) | none | - | Not run: out of this task per its recorded assumption (UI restore only) |

## Focused test execution

Command:

`npm --prefix web run e2e:typecheck`; `npx --prefix web eslint web/e2e`; `npx --prefix web prettier --check web/e2e/phase2`

Result:

Pass (all exit 0)

Command:

`npm --prefix web run e2e -- --project=phase-demos > .tmp/tw-p2-e2e.log` (run-e2e.mjs: compose up --build of
deploy/docker-compose.e2e.yml incl. `server-fresh`/`postgres-fresh`, chromium, then down -v), 2026-10-21T07:57:09Z -
07:59:11Z, exit 0

Result:

Pass: 21 passed (50.1 s): phase0 5, phase1 10, phase2 6; none skipped (step 5 ran with `E2E_FRESH_BASE_URL`).

## Full test execution

Command:

`sh scripts/check.sh` (= `.taskcheck`: lint + unit tests of server, web, android)

Result:

Pass (exit 0, "all checks passed"). The other Playwright projects (`chromium`, `inbox-exclusive`) were not run: the
task does not change them and the criteria name only the phase demos.

## .taskcheck

Result:

Pass (`sh scripts/check.sh`, exit 0, log `.tmp/tw-p2-check.log`)

## Bugs found

None. No test exposed a production defect.

Observations for the parent (no production fix required):

- `TW-1` (Low, test strength): both servers share `COCKPIT_MASTER_KEY`. That matches a real redeploy (the key is
  environment, not a volume), and phase 1-2 data holds no master-key-encrypted secrets, so it does not weaken this
  task's round trip; a later phase with IMAP/OAuth secrets may want a different key for the fresh server.
- `TW-2` (Low, usability): with `E2E_BASE_URL` + `E2E_FRESH_BASE_URL` against a kept stack (`E2E_KEEP=1`), step 5
  passes only once: the fresh server is no longer fresh afterwards and the `setupRequired: true` assertion fails.
  This failure is loud and correct; the README could mention it.
- `TW-3` (Low, scope note): `web/e2e/support/run-e2e.mjs` and `web/e2e/README.md` are outside the task's `touches`;
  recorded in the task's first assumption.

## Coverage

Status: not applicable - the task changes no production source; its code is e2e test code and test infrastructure
that no configured coverage provider instruments.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `p2-smoke`; base
  `8275cd090f113d81f2ebd8175fd98ff526a4e992`; HEAD `f48fdacc23ef040961b8f1589a22207be498917f`; task commits
  809cfc69, 1008fecf, 6057656f, f48fdacc from the parent, consistent with `touches` and the task notes.
- Source/test snapshot identifier (including uncommitted inputs): HEAD f48fdacc plus uncommitted
  `web/e2e/phase2/demo.spec.ts` sha256 `40fabead7052d82f081a4dd0bac4c3731974e732fc2efb3e8b68d56fc6f41d1e`;
  `web/e2e/phase2/zip.ts` `b146e60fd93629d1f5ed166ca5f994dab5f10a50cce5a8c650ed14ef242c6113`,
  `web/e2e/support/run-e2e.mjs` `989a592b8dc790e4a8d1e8168152f0f0b879c70796997098e45f696e5f44a6e9`,
  `deploy/docker-compose.e2e.yml` `083d935c33eba4dfacc73a6a2dd2776ba5aa68e252ffb2838199a2f5a258efed` (taken before
  the e2e run and re-checked after it: unchanged).
- Exact included files: none. `git diff --name-only 8275cd09..HEAD -- server/src/main web/src android/app/src/main`
  (the scope rule of docs/ARCHITECTURE.md "Coverage") is empty. Excluded: `web/e2e/phase2/demo.spec.ts`,
  `web/e2e/phase2/zip.ts` (e2e test code and its helper), `web/e2e/support/run-e2e.mjs` (e2e runner script),
  `deploy/docker-compose.e2e.yml` (test stack configuration), `web/e2e/README.md` (docs). No renames or deletions.
- Provider/version, command, working directory, and test selection: no coverage command run. The configured web
  provider (`npm run coverage`, Vitest + `@vitest/coverage-v8`) covers only `web/src` unit tests; Playwright under
  `web/e2e/` has no coverage instrumentation, and JaCoCo/Android coverage do not apply. Measuring the e2e helper
  would need new coverage configuration, outside this role.
- Run start/completion time and exit status: not applicable (no coverage run); the e2e run that exercises all task
  code: 2026-10-21T07:57:09Z - 07:59:11Z, exit 0.
- Generated report paths and report hashes/run identifier: none for coverage; Playwright log `.tmp/tw-p2-e2e.log`
  (Git-ignored), HTML report `web/playwright-report/`.
- Evidence outputs are fresh and inputs stayed unchanged: yes for the e2e run (hashes above before and after).
- Final run after latest test additions and production fixes: yes (includes this run's additions; no production code
  changed).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| (no production files in scope) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/e2e/phase2/zip.ts` (not instrumented) | correct reading of server zips | exercised against real archives in steps 2 and 4; failure modes throw; no unit runner for `web/e2e/` |
| `web/e2e/support/run-e2e.mjs` fresh-server wait/log path on failure | stack start-up diagnostics | success path exercised by every e2e run; failure path (health timeout) not triggered, not material to the criteria |

## Unable to test

- The CLI restore path (`--restore`): out of scope per the task's recorded assumption; no follow-up from this task.
- The web TOTP re-enrollment screen: not built yet (task web-totp-reenroll); the spec uses the API, per assumption.

## Summary

Tests added and all validation passed.
