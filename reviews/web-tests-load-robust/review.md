# Code Review

Date: 2026-11-01

Task: web-tests-load-robust - Web unit tests: no cascade failures and fewer timeouts on a busy machine

Reviewed revision: 31423d9229d819ee8996109791c69a64bd15629a

Scope: `git diff ddfb9f64946f1093861e8bf28d25dafe643da201..31423d9229d819ee8996109791c69a64bd15629a`, code files: web/vitest.config.mts, web/src/test-setup.ts, web/src/test-setup.spec.ts, web/angular.json, web/tsconfig.app.json, web/tsconfig.spec.json, web/src/app/features/files/upload/upload.service.spec.ts, docs/ARCHITECTURE.md (plus task-board commits in tasks/). Committed changes only.

Requirements:
- `tasks/web-tests-load-robust.md` acceptance criteria, recorded evidence and assumptions
- `AGENTS.md` project notes (lint contract, shared docs, touches)
- `docs/ARCHITECTURE.md` web testing rules
- Parent's evidence summary and coordinator's request to extend the review to the regression spec

Git range:
- Branch: main
- HEAD: 31423d9229d819ee8996109791c69a64bd15629a
- Base/start: ddfb9f64946f1093861e8bf28d25dafe643da201
- Commits reviewed: 10 (f2d06b54c, c0d0713d3, 0f363f9d5, 31423d922 with code changes; 962a84c37, 82d382cd2, 087f7602c, ca40a0ebf, d160e6667, aa249c69c record criterion evidence only). The first three code commits are the rebased versions of e45d0c6bc, 3661279cc and daee8b31d reviewed earlier; `git diff daee8b31d..31423d922` differs only in `tasks/web-tests-load-robust.md` and the new `web/src/test-setup.spec.ts`.

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## What was checked

- Cascade fix mechanism. The Angular unit-test builder's generated init file
  (`node_modules/@angular/build/src/builders/unit-test/runners/vitest/build-options.js`) registers
  `beforeEach(getCleanupHook(false))` / `afterEach(getCleanupHook(true))`; with the default
  `destroyAfterEach: true` the beforeEach hook is a no-op, so the reset only happens in the root
  afterEach. Vitest's `callSuiteHook` (`node_modules/vitest/dist/chunks/run.BPv-BBRv.js:3593-3616`)
  runs afterEach hooks sequentially and only calls the parent suite's afterEach after the inner ones
  succeed, so an inner `http.verify()` failure skips Angular's root reset. The comment in
  `web/src/test-setup.ts` is accurate, and the unconditional `resetTestingModule()` in a root
  `beforeEach` addresses the actual cause.
- Hook order. Setup-file hooks are registered on the file suite before the spec's own hooks (the
  builder sets `sequence.setupFiles: 'list'`), and Vitest runs parent beforeEach hooks first, so
  `vi.useRealTimers()` runs before any spec's `beforeEach(() => vi.useFakeTimers())`
  (change-events, reminder-notifications, mail-list-store and others). No spec enables fake timers
  in `beforeAll` or at describe level.
- TestBed configured outside `beforeEach`/tests. Only `upload.service.spec.ts` uses `beforeAll`, and
  it only prepares byte payloads (no TestBed). A grep for `TestBed.configureTestingModule` /
  `TestBed.inject` at describe level found only calls inside hooks, tests or helper functions
  (e.g. `browser-push.spec.ts:41` is inside a helper). Such a pattern would already break under
  Angular's own afterEach reset, so the new reset adds no new restriction.
- Swallowing the reset error with `console.warn`. A throw from `resetTestingModule()` here can only
  come from leftovers that Angular's own afterEach did not tear down, which happens only when an
  earlier afterEach of the previous test failed, so that test is already reported as failed. Angular's
  `resetTestingModule` (`node_modules/@angular/core/fesm2022/testing.mjs:1407-1424`) clears the active
  fixtures and tears down the module in `finally` blocks before rethrowing, so the following test
  really starts from a fresh TestBed. In the normal path the module was already reset and the second
  reset is a cheap no-op. Real failures are not masked; the warning keeps the teardown error visible.
- Timeouts: `testTimeout`/`hookTimeout` 20 s in `web/vitest.config.mts`, wired via `runnerConfig`;
  not per test (the 60 s per-test override in the upload spec was removed). Matches criterion 2 and
  the recorded assumption.
- Upload spec: the real-file case now hashes 5 MB + 1 byte in 2 MB chunks (2 MB, 2 MB, 1 MB + 1 byte)
  while reporting BIG_SIZE; `bigFile()` overrides `size` whenever the data length differs from
  BIG_SIZE, which covers both payloads; `session()` derives `chunkCount` from the real data length
  (3), consistent with the slices the service makes. The digest assertion still checks the
  incremental SHA-256 against WebCrypto over multi-chunk input. The progress events (1 MB of a 2 MB
  first chunk, 1 MB of the 1 MB + 1 byte last chunk) are within their chunks.
- Regression spec `web/src/test-setup.spec.ts`:
  - Correctness: `onTestFinished` callbacks run after all afterEach hooks
    (`run.BPv-BBRv.js:3920` afterEach, `:3928` onFinished), so the leftovers (an instantiated
    TestBed, fake timers, a fixture whose `ngOnDestroy` throws) are created after Angular's own
    reset and reach the next test exactly as after a skipped teardown. The even tests then assert
    real timers, a re-configurable TestBed, one `console.warn` with the setup's message, and the
    Angular "1 fixture threw errors during cleanup" error. With the setup file removed, tests 2 and 4
    would fail (TestBed already instantiated / no warning), so the spec guards the behaviour.
  - Order dependence: intentional and documented in the file comment. Vitest runs the tests of a
    file sequentially; neither `vitest.config.mts` nor `angular.json` enables `sequence.shuffle` or
    concurrency, and `isolate: true` keeps the leftovers from reaching other files. See CR-2 for the
    single-test case.
  - Flakiness: all four tests are synchronous, use no real timers, network or wall-clock values,
    and the `console.warn` spy is restored in the test and again in `afterAll`. Angular reports the
    fixture failure through `console.error`, not `console.warn`, so `toHaveBeenCalledTimes(1)` does
    not count it. No flakiness risk found.
- tsconfig edits: `test-setup.ts` is type-checked with the spec config (vitest globals) and excluded
  from the app build; ESLint covers it and the new spec via `**/*.ts`.
- `docs/ARCHITECTURE.md`: three lines in the existing web unit-test rules, consistent with the config.
- No production code, dependency or CI change; no secrets.

## Correction of the earlier CR-2

The earlier revision of this report had a CR-2 "Last-chunk progress event no longer matches the chunk
it reports on", claiming the last chunk of the real file is 1 byte. That was wrong: `pattern(5 * MB + 1)`
in 2 MB chunks gives chunks of 2 MB, 2 MB and 1 MB + 1 byte, so `{ loaded: MB, total: MB }` on the last
chunk and the expected `2 * CHUNK + MB` are plausible values. That finding is withdrawn; CR-2 below is a
new finding about the regression spec.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | REAL_FILE_TIMEOUT now equals the global test timeout, so a stall in the real-file test reports as a bare test timeout**
    - File: `web/src/app/features/files/upload/upload.service.spec.ts:60`
    - Category: Tests
    - Problem: `REAL_FILE_TIMEOUT = 20_000` is used as the `vi.waitFor` timeout of `next()`/`until()`
      in the real-file test (line 390), and the test itself now runs under the global 20 s
      `testTimeout`. A single wait can consume the whole test budget, so if a request never arrives
      Vitest reports "Test timed out in 20000ms" instead of the descriptive `expectOne` error from
      `waitFor`. Hashing is now 5 MB, so the raised wait is no longer what the comment ("hashing it
      is slow under coverage") needed.
    - Why it matters: Diagnosing a real regression in the chunked upload path gets harder, which is
      the kind of opaque timeout this task is meant to reduce.
    - Suggested fix: Keep the per-wait timeout clearly below the test timeout (for example drop
      `REAL_FILE_TIMEOUT` and use the default 5 s wait, or set it to about 10 s), and adjust the comment.

- [ ] **CR-2 | The fourth regression test fails with a misleading error when run on its own**
    - File: `web/src/test-setup.spec.ts:47`
    - Category: Tests
    - Problem: `warn` is only assigned in the `onTestFinished` callback of the third test. Running the
      fourth test alone (`ng test --include src/test-setup.spec.ts` with a `-t`/`--filter` on its name,
      or an editor's "run this test") leaves `warn` undefined, and `expect(warn).toHaveBeenCalledTimes(1)`
      fails with a Vitest "not a spy" error that says nothing about the dependency. The second test, run
      alone, passes without checking anything.
    - Why it matters: The order dependence is documented in the file comment, so a full run is
      correct; but a developer who isolates the failing test gets an error that points away from the
      cause.
    - Suggested fix: Start the fourth test with an explicit guard such as
      `expect(warn, 'runs after the previous test of this file').toBeDefined()`, or name the even tests
      so their dependency on the previous test is obvious.

## Notes for the parent worker (not findings)

- Evidence for criteria 1-6 is now recorded in `tasks/web-tests-load-robust.md`, including the 10
  slowest files before/after and `npm run check`. Those runs predate `web/src/test-setup.spec.ts`;
  make sure the final validation (`npm run test:ci` / `.taskcheck`) is run on 31423d922 so the new
  spec is covered by the recorded results.

## Review conclusion

No material issues found.
