# Test Report

Date: 2026-11-01

Task: web-tests-load-robust - Web unit tests: no cascade failures and fewer timeouts on a busy machine

Scope: `git diff ddfb9f64946f1093861e8bf28d25dafe643da201..0f363f9d5e4e03af33bc88cd358d66a1047d0539`
(branch main, HEAD 0f363f9d5; task commits f2d06b54c, c0d0713d3, 0f363f9d5, rebased copies of
e45d0c6bc, 3661279cc, daee8b31d with the same patches). Changed: `web/vitest.config.mts` (new),
`web/src/test-setup.ts` (new), `web/angular.json` (test options), `web/tsconfig.app.json`,
`web/tsconfig.spec.json`, `web/src/app/features/files/upload/upload.service.spec.ts`,
`docs/ARCHITECTURE.md`. No production source changed. Uncommitted at measurement: only the new
`web/src/test-setup.spec.ts` (this report's addition) and this report.

Requirements:
- `tasks/web-tests-load-robust.md` acceptance criteria and notes (incl. recorded assumptions)
- `docs/ARCHITECTURE.md` (web testing rules, Coverage)
- `AGENTS.md`; parent's evidence (idle timings, demo spec, wrong-assertion run, runs under load)

## Test files changed

- `web/src/test-setup.spec.ts` (new)

## Tests added or updated

Count: 4 (one ordered spec file; each odd test leaves state behind, the following test checks it)

- `leaves an instantiated TestBed and fake timers behind`: from `onTestFinished` (which Vitest 5.0.5
  runs after all `afterEach` hooks, so after Angular's own TestBed reset) it re-instantiates the
  TestBed and switches fake timers on, like a test whose `afterEach` failed or that timed out.
- `starts with real timers and a TestBed that can be configured again`: asserts `vi.isFakeTimers()` is
  false and that `TestBed.configureTestingModule` works (would throw "Cannot configure the test module
  when the test module has already been instantiated" without the global hook, since Angular's own
  `beforeEach` cleanup only resets when `destroyAfterEach` is false).
- `leaves a fixture behind whose teardown throws`: leaves a fixture of a component whose
  `ngOnDestroy` throws.
- `reports the failed teardown as a warning and still runs with a fresh TestBed`: covers the `catch`
  branch of `test-setup.ts`: exactly one `console.warn` with the documented message and Angular's
  "1 fixture threw errors during cleanup" error, and the TestBed is usable.

This is a permanent regression test of the setup file without a deliberately failing test. The
"timeout in the first test of a file" demonstration itself needs a failing test and was done by the
parent with a temporary spec (old config 5 of 5 failed, new config 1 failed, 4 passed).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: a timed-out/failed test does not break following tests (TestBed reset, timers cleaned up) | the 4 tests of `test-setup.spec.ts` | `npm --prefix web exec -- ng test --watch=false --include src/test-setup.spec.ts` | Pass (4/4) |
| AC1: demo with a test that times out first | parent's temporary demo spec | parent run, see task evidence | Not run by test-writer (parent: Pass) |
| AC2: 20 s test/hook timeout in builder config | config review of `web/vitest.config.mts`; builder logs "Using Vitest configuration file: .../web/vitest.config.mts" | focused run above | Pass (config loaded); value review only |
| AC3: real SHA-256 test made cheaper | `UploadService > chunked files > creates a resumable upload, sends every chunk and completes with the real SHA-256` | `npm --prefix web exec -- ng test --watch=false --include src/app/features/files/upload/upload.service.spec.ts --include src/test-setup.spec.ts --include src/app/core/events/change-events.service.spec.ts` | Pass (83/83 over 3 files) |
| AC4: suite passes under load | none (parent ran it under 32/64 busy loops) | not re-run (instructed not to load the machine) | Not run by test-writer |
| AC5: real failures still fail; idle time | parent's wrong-assertion run; idle full run here | `npm --prefix web run test:ci` | Pass: 186 files, 4057 tests, 40.5 s (36.5 s on a first run; load average 14.8 at the time) |
| AC6: ARCHITECTURE.md note | doc review | n/a | Present (3 lines in the web testing rules) |

## Focused test execution

Command:

`npm --prefix web exec -- ng test --watch=false --include src/test-setup.spec.ts`

Result:

Pass (4 passed). First attempt failed because my assertion expected the inner error text; Angular
wraps it as "1 fixture threw errors during cleanup", so the test was corrected (test bug, not a
production bug). Also `npx eslint --max-warnings=0` and `npx prettier --check` on the new spec: pass.

## Full test execution

Command:

`npm --prefix web run test:ci`

Result:

Pass: 186 test files, 4057 tests (4053 existing + 4 new), 40.48 s, machine not artificially loaded.
`npm run check` (api check, lint, e2e typecheck, build) was not run by the test-writer.

## .taskcheck

Result:

Not run (parent runs `.taskcheck`; sub-agents do not run the Gradle parts reliably here).

## Bugs found

None.

Review notes (no fix required):

- Could the setup hide failures? The `catch` only swallows an error from destroying what the previous
  test left behind. `resetTestingModule` clears the module ref in `finally`, so the next test starts
  clean either way. With the default teardown (`destroyAfterEach: true`, `rethrowErrors` defaulting to
  it) a teardown error is already reported by Angular's own `afterEach` on the test that caused it, and
  a skipped Angular `afterEach` only happens when an earlier `afterEach` of that test failed, which is
  also reported. No passing test can turn into a hidden failure.
- Could it break existing specs? The hook is registered at file root from `setupFiles`, so it runs
  before every spec's own `beforeEach`. Grep: the only `beforeAll` in `web/src` is in
  `upload.service.spec.ts` and only computes payload data (no TestBed, no timers). No spec calls
  `vi.useFakeTimers` at file or describe level or in `beforeAll`; all 22 files using it do so in
  `beforeEach` or inside tests (e.g. `change-events.service.spec.ts:120`, which passes). No spec
  configures TestBed in `beforeAll` or relies on TestBed state across tests (this was already
  impossible with Angular's default per-test teardown). Full suite passes.
- The shrunk upload test (5 MB + 1 byte in 2 MB chunks, size reported as 50 MB + 1) still checks: the
  create request body with the 50 MB + 1 size, 3 chunks via `session().chunkCount`, chunk slicing and
  length of chunk 1 (first and last byte compared), progress accounting across chunks
  (`2 * CHUNK + MB`), and the completed digest against WebCrypto over all bytes read in multi-MB slices,
  including a last chunk that is not 64-byte aligned. Lost relative to the old version: hashing a real
  50 MB input (no new code path; both sizes stay below 2^32 bits, so the length encoding is the same
  path) and the explicit 60 s per-test timeout (the global 20 s now applies; the test takes about 0.2 s).
  The hash implementation itself is covered by `sha256.spec.ts` (FIPS vectors, a megabyte in prime-sized
  parts vs WebCrypto). Minor wording point: the `REAL` doc comment says "every byte of every chunk is
  real", which is true of the data, but `acceptChunk` compares only the first and last byte of large
  chunks (as the `Payload.real` doc says); the SHA-256 check is what verifies all bytes.
- Residual limitation (not a bug): a timed-out test whose async work keeps running can still switch
  fake timers on or touch the TestBed in the middle of a later test; the setup only cleans up at test
  start. Acceptable for the task's goal.

## Coverage

Status: not applicable - the task changed no production code; the changed code is test infrastructure,
which the configured provider does not instrument.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no production lines changed).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-tests-load-robust;
  base ddfb9f64946f1093861e8bf28d25dafe643da201 (given by the parent); HEAD
  0f363f9d5e4e03af33bc88cd358d66a1047d0539; commits f2d06b54c, c0d0713d3, 0f363f9d5 (`git log --stat`).
- Source/test snapshot identifier (including uncommitted inputs): HEAD above plus untracked
  `web/src/test-setup.spec.ts` sha256 58d955dee0157a0931f21af6c005a10a705852ad3b37ce4e64f33a08f2ab8bb2;
  `web/src/test-setup.ts` sha256 c441055621875140306245cde81ae467bef4dfe4287b2d32df8d3b90db42f8cd.
- Exact included files; renamed/deleted/excluded files and reasons: none included. The ARCHITECTURE.md
  step-1 path filter (`web/src`, excluding `*.spec.ts`) matches only `web/src/test-setup.ts`, which is a
  Vitest setup file (test infrastructure, type-checked with the specs, excluded from the app build).
  Excluded: `upload.service.spec.ts` (test), `vitest.config.mts`, `angular.json`, tsconfig files
  (configuration), `docs/ARCHITECTURE.md` (documentation).
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with
  `@vitest/coverage-v8`; `npm --prefix web exec -- ng test --watch=false --coverage
  --coverage-reporters=json-summary --coverage-reporters=json --include src/test-setup.spec.ts
  --include src/app/features/files/upload/upload.service.spec.ts`, run from the clone root.
- Run start/completion time and exit status: 2026-11-01 22:16:16 local, about 2 s, exit 0, 46 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json`
  (sha256 72fc5b0acda3406ff3617c69e0e653f3ca0bf4390155675f6f57cecbc451d3b2) and
  `web/coverage/web/coverage-final.json`, both written 22:16 by this run.
- Evidence outputs are fresh and inputs stayed unchanged: yes; the report lists `upload.service.ts`
  (unchanged production file, not in task scope) and does not contain `test-setup.ts`, confirming the
  setup file is not instrumented. Its behavior is instead verified by `test-setup.spec.ts` (both
  branches: normal reset and the `catch` path).
- Final run after latest test additions and production fixes: yes (no production fixes).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| none (no production files in scope) | N/A | N/A | N/A | N/A |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/test-setup.ts` (not instrumented) | per-test TestBed and timer reset, warning on teardown failure | `web/src/test-setup.spec.ts` exercises both the reset and the `catch` path |

## Unable to test

- AC4 (passing under load) and the timeout demo spec: not re-run by the test-writer, as instructed;
  the parent's runs are the evidence.
- That `test-setup.spec.ts` fails without the setup file: not demonstrated, because removing the
  `setupFiles` entry would mean editing configuration. Reasoned from Angular's `getCleanupHook`
  (`@angular/core/fesm2022/testing.mjs`: its `beforeEach` resets only when teardown is off) and Vitest's
  hook order (`onFinished` after `afterEach`). No follow-up needed.

## Summary

Tests added and all validation passed.
