---
title: Web unit tests: no cascade failures and fewer timeouts on a busy machine
status: in_progress
owner: dell26/agent-1
deps: [web-files-upload-preview]
touches: [web/angular.json,web/vitest.config.ts,web/src/test-setup.ts,web/src/app/features/files/upload/upload.service.spec.ts,docs/ARCHITECTURE.md]
prio: 1
lease_until: 2026-11-01T21:33:29Z
---
## Goal
`cd web && npm run test:ci` (part of `.taskcheck`) fails when the host is busy (other agents' Gradle builds, load
average 100-200): a few tests exceed the default 5 s timeout, and each timeout then fails every following test of
the same file with "Cannot configure the test module when the test module has already been instantiated. Make
sure you are not using `inject` before `TestBed.configureTestingModule`." One run showed 8 timeouts and 46 such
follow-on errors; another 129 failures. The same suite passes when the machine is idle (4053 tests, 2026-11-01).
Seen in `reviews/p3-smoke/tests.md`, `reviews/web-contacts/fixes.md`, `reviews/web-files-upload-preview/tests.md`
and by the owner while closing h-prod-p2. Make the check reliable under load without hiding real failures.

## Acceptance criteria
- [ ] A test that times out no longer breaks the following tests of its file: the TestBed is reset (and pending
      HTTP expectations/timers cleaned up) even after a timeout, e.g. via a global setup file for the Angular
      unit-test builder. Demonstrated with a temporary spec that times out in its first test: the file reports
      exactly that one failure, the next tests pass (then remove the demo spec)
- [ ] Timeouts suit a shared host: the unit-test runner uses a higher default test and hook timeout (e.g. 15-20 s)
      and/or a bounded number of workers, set in the builder/Vitest config, not per test. Record the chosen values
      and the reason with `tasks.py assume`
- [ ] The slowest tests are made cheaper instead of only given more time: at least the real 50 MB SHA-256 test in
      `web/src/app/features/files/upload/upload.service.spec.ts` (e.g. smaller input, or the large case moved out of
      `test:ci`). The 10 slowest test files of a full run are listed in the evidence before/after
- [ ] Under load the suite passes: `npm run test:ci` run 3 times while the machine is busy (e.g. `stress-ng --cpu
      <2x cores>` or a parallel `./gradlew check` in `server/`) -> 0 failures each time; record the load average
- [ ] Real failures still fail: a deliberately wrong assertion fails the run (then reverted); `cd web && npm run
      check` passes; total `test:ci` time on an idle machine is not more than 20 % slower than before
- [ ] `docs/ARCHITECTURE.md` (web testing section) notes the timeout/worker settings in one or two lines

## Notes
Requested by the owner (2026-11-01). Only test infrastructure and the named slow spec; no production code. If other
specs are among the slowest, fix them only when the change is local to the spec and they are not in another
in-progress task's `touches`.
- ASSUMPTION (2026-11-01 22:13+0100 by dell26/agent-1): Web unit tests: testTimeout and hookTimeout 20 s in web/vitest.config.mts (builder runnerConfig); worker count left at Vitest's default. Why: under load 4x cores, healthy component tests take up to ~10x their idle time (0.5 s -> several s), 20 s gives headroom while a hung test still fails; bounding workers would slow the idle run (>20 % budget) and does not help when the load comes from other processes
- ASSUMPTION (2026-11-01 22:13+0100 by dell26/agent-1): Runner config is web/vitest.config.mts (not .ts as in touches: ESM-only vitest/config import); test-setup.ts was added to tsconfig.spec.json and excluded from tsconfig.app.json so lint/build type-check it like a spec (small edits outside touches)
- ASSUMPTION (2026-11-01 22:13+0100 by dell26/agent-1): Real-file upload test now hashes a 5 MB + 1 byte file in 2 MB chunks (reporting the 50 MB+1 size) instead of a real 50 MB file: still checks chunk slicing and incremental SHA-256 against WebCrypto on multi-MB chunks; 1.8 s -> 0.2 s idle
