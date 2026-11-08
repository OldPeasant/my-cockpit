# Test Report

Date: 2026-11-08

Task: fix-mail-images-firefox - Mail images never load in Firefox (frame CSP 'self' in srcdoc)

Scope: `git diff 54f0e79f..9b95dfd5 -- . ':!tasks' ':!reviews'` (base 54f0e79ffb0dd76fe5c439ec4a10e15cb68ea5c7, HEAD 9b95dfd5a48f3d31632ac3dd834f905694212be9, task commits 1c03e70f, 0be1f9b2, 57b04279, fce80bf9, 9b95dfd5). Branch `main`. Changed files: `docs/ARCHITECTURE.md`, `web/e2e/README.md`, `web/e2e/mail-read/read.spec.ts`, `web/playwright.config.ts`, `web/src/app/features/mail/read/mail-frame.spec.ts`, `web/src/app/features/mail/read/mail-frame.ts`. No uncommitted source or test changes (the only working-tree change during this run was `reviews/fix-mail-images-firefox/review.md`, which belongs to the code-reviewer, not the measured inputs).

Requirements:
- `tasks/fix-mail-images-firefox.md` acceptance criteria 1-4
- `SPEC.md` §4.1 (mail frame sandbox/CSP), `docs/ARCHITECTURE.md` "Coverage"
- Parent instructions (the parent ran the e2e suite: 37 passed in chromium and firefox; no docker stack needed here)

## Test files changed

- None. The existing tests already cover the task's behavior (see below).

## Tests added or updated

Count: 0

I reviewed the existing `mail-frame.spec.ts` (16 tests) against criterion 1. It already pins:
- the app origin in `img-src` and `font-src`, next to `'self'` and `data:` (`names the app origin next to self for images and fonts (Firefox srcdoc)`);
- `default-src 'none'`, `script-src 'none'`, no `connect-src`, and exactly one host (the origin) in each of the two directives (`allows no foreign host, no scripts and nothing by default`);
- an exact list of sources for every directive, so no other host or keyword can slip in (`allows exactly the listed sources in every directive (no other host or keyword)`);
- that a dev origin keeps its port, that `location.origin` is the default, that the rendered srcdoc meta CSP names the page origin, and that `mailDocument` uses the origin it is given.

I found no meaningful gap in the changed behavior, so I didn't add redundant tests.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: frame CSP names the app origin in img-src/font-src next to 'self', `default-src 'none'`, no scripts, no other hosts; spec asserts origin present and no foreign host | `MailFrame > the CSP > names the app origin next to self for images and fonts (Firefox srcdoc)`, `... allows no foreign host, no scripts and nothing by default`, `... allows exactly the listed sources in every directive (no other host or keyword)`, `... keeps the port of a development origin`, `... uses the page origin by default`, `... names the page origin in the CSP of the rendered frame`, `... builds the document with a given origin`, `MailFrame > puts the body into a document with the CSP` | `cd web && npx ng test --watch=false --include src/app/features/mail/read/mail-frame.spec.ts` | Pass (16/16) |
| AC2: Playwright Firefox project runs `e2e/mail-read/*`; install documented | `web/playwright.config.ts` project `firefox` | Not run by test-writer: needs the docker e2e stack. The parent ran `cd web && npm run e2e -- e2e/mail-read`, which passed (recorded in the task) | Not run: see command column |
| AC3: `read.spec.ts` asserts the inline `cid:` image loads, passes in Chromium and Firefox | `Mail read view` (`inlineImages` poll before and after "Load images") | Same as AC2. The test-writer ran `npm run e2e:typecheck`, which passed (type-check only, not execution) | Not run: see command column |
| AC4: `npm run check` and `sh scripts/check.sh` pass | Web parts: full unit suite, lint, e2e typecheck | `npm --prefix web run coverage` (the full Vitest suite), `npm --prefix web run lint`, `npm --prefix web run e2e:typecheck` | Pass for these parts. Not run: `ng build`, `api:check`, and `sh scripts/check.sh` (server/android untouched; the parent owns the final `.taskcheck`) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/mail/read/mail-frame.spec.ts`

Result:

Pass (1 file, 16 tests)

## Full test execution

Command:

`npm --prefix web run coverage` (the complete web Vitest suite with v8 coverage), plus `npm --prefix web run lint` and `npm --prefix web run e2e:typecheck`

Result:

Pass: 186 files, 4063 tests. Lint: "All files pass linting". e2e typecheck: no errors.

## .taskcheck

Result:

Not run. `sh scripts/check.sh` includes server and android suites the task doesn't touch, and the parent recorded an earlier server-stage stall. The parent runs `.taskcheck` before `done`.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. I intersected the lcov `DA` lines and the `coverage-final.json` statement/branch/function maps with the lines this task added in `mail-frame.ts` (16-26, 36, 39). The only executable `DA` line in that set is line 21 (the `mailFrameCsp` return), with 132 hits, so 1/1 lines (100%). The changed functions `mailFrameCsp` (line 20, 132 calls) and `mailDocument` (line 36, 125 calls) are both covered: 2/2. Line 39 belongs to the statement that starts at line 37. The default-argument branches on the changed lines 20 and 36 are both taken: 2/2. The non-default path for each is exercised by explicit-origin tests.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: fix-mail-images-firefox; base 54f0e79ffb0dd76fe5c439ec4a10e15cb68ea5c7 (supplied by parent); HEAD 9b95dfd5a48f3d31632ac3dd834f905694212be9; commits 1c03e70f..9b95dfd5 (supplied by parent, matches `git log 54f0e79f..HEAD` and the task's `touches`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 9b95dfd5, clean for the measured inputs; sha256 `mail-frame.ts` 3269344f6935e9bd67220b870da8ea154f553ebfce6d27a75798f9848a7e9a3f, `mail-frame.spec.ts` 4da6ba3d4a1e2d52aad0d232aa3878772cd644c2b6df7327c63d53c202823890 (same before and after the run).
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/mail/read/mail-frame.ts`, the only production file from `git diff --name-only 54f0e79f..HEAD -- server/src/main web/src android/app/src/main` (minus `*.spec.ts`). Excluded because they are test code, config or docs that Vitest doesn't instrument: `web/e2e/mail-read/read.spec.ts`, `web/playwright.config.ts`, `web/e2e/README.md`, `docs/ARCHITECTURE.md`. No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + @vitest/coverage-v8 5.0.5; `npm --prefix web run coverage` from the repo root (runs `ng test --watch=false --coverage ...` in `web/`); full web suite.
- Run start/completion time and exit status: started 2026-11-08T09:03:05Z (build), reports written 2026-11-08 10:05:25 +0100; exit 0 (186 files, 4063 tests passed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` sha256 03d0a8cf6d450547fd97d157002f763e9d52cae85241b41e86e29dc54ac8d802; `web/coverage/web/lcov.info` sha256 f8e7aabf07cff9d3ce0ab91c8f0f20ee274f0d5822ea38c02178f6eb316b40f0; `web/coverage/web/coverage-summary.json`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. All report files were rewritten by this run (mtime 10:05:25 local, after the 10:03:05 start), and the input hashes match before and after.
- Final run after latest test additions and production fixes: yes. No tests were added, and HEAD 9b95dfd5 is the final task code.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/read/mail-frame.ts` | 31/33 (93.93%) | 16/18 (88.88%) | 9/10 (90%) | 25/25 (100%) |

Aggregate (one file in scope, no unmeasured or missing files): same as the row above.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-frame.ts:85` (statement + anonymous function) | `viewChild.required(...)` signal-query glue that the compiler generates; not task code | Not material: generated glue, and the query itself is used by every test |
| `mail-frame.ts:100` (`if (!body) return;` branch) | `fit()` when the frame has no document body; existed before the task (unchanged since 54f0e79f) | Not material for this task: jsdom always provides a body, and the code is outside the task diff |
| `mail-frame.ts:104` (`?? 0` branch) | Fallback height when the body disappears between observer callbacks; existed before the task | Not material for this task: defensive code outside the task diff |

## Unable to test

- AC2/AC3 browser execution (Firefox and Chromium e2e against the docker e2e stack): not run by the test-writer, per the parent's instruction. The parent recorded `cd web && npm run e2e -- e2e/mail-read`, with 37 passed (chromium + firefox). The parent should confirm that this run included the final config commit 9b95dfd5 (`inbox-exclusive` depends on `['chromium', 'firefox']`); if not, it is worth re-running the e2e suite once.
- AC4 `ng build`, `api:check` and `sh scripts/check.sh`: left to the parent's final `.taskcheck` run.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
