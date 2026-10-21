# Fix Report

Date: 2026-10-21

Task: p2-smoke — Phase 2 demo script incl. restore on a fresh install

Scope: current task; findings CR-1 and CR-2 from `reviews/p2-smoke/review.md`, TW-2 from `reviews/p2-smoke/tests.md`
(same issue as CR-1).

## Fixed findings

### CR-1 (and TW-2) — Step 5 works only once per `server-fresh`

Files changed:
- `web/e2e/phase2/demo.spec.ts`
- `web/e2e/README.md`

Problem:
Step 5 asserted `setupRequired: true` on the fresh-install server. Nothing returns `server-fresh` to a fresh state,
so a second run against a kept stack (`E2E_BASE_URL` + `E2E_FRESH_BASE_URL`) always failed step 5 with what looks
like a restore regression.

Fix:
Step 5 now checks that `/api/v1/setup/status` answers OK and reads `setupRequired`. If it is false, the step is
skipped (`test.skip`) with a message saying that the fresh-install server was already used and giving the
`up -d --force-recreate -V server-fresh postgres-fresh` command (same `E2E_IMAGE` and `E2E_FRESH_PORT` as the
stack) that recreates it. As the reviewer advised, it is not reset through `/api/test/reset`, which would keep the
old data directory. The README now says that `server-fresh` is single-use, that `npm run e2e` recreates it on every
run, and gives the full recreate command for a kept stack.

Validation:
- `npm --prefix web run e2e -- --project=phase-demos` (podman-compose stack, fresh `server-fresh`) — Pass, 21 passed,
  step 5 ran (not skipped).
- The skip path (a second run against a kept stack) was not run. Running with `E2E_KEEP=1` on fixed ports needed a
  permission that was not granted in this session. The skip condition is a single `test.skip(!setupRequired, ...)`
  on the value that the original assertion checked.

### CR-2 — `getByText('not included')` matches substrings

Files changed:
- `web/e2e/phase2/demo.spec.ts`

Problem:
The substring match would also match future module restore warnings such as "... are not included", which would
break Playwright's strict mode.

Fix:
`report.getByText('not included', { exact: true })` now matches only the Secrets `dd` text.

Validation:
- `npm --prefix web run e2e -- --project=phase-demos` — Pass (step 5 passed with the exact locator).

## Not fixed

None.

## Validation summary

Focused tests:
- `npm --prefix web run e2e:typecheck` — Pass
- `npx --prefix web eslint web/e2e` — Pass (no output)
- `npx --prefix web prettier --check web/e2e web/e2e/README.md` — Pass

Full test suite:
- `npm --prefix web run e2e -- --project=phase-demos > .tmp/fix-e2e.log 2>&1` — Pass, exit 0, 21 passed (51.6s)
  across the phase 0, 1 and 2 demos

Lint/type-check/build:
- Covered above (e2e typecheck, eslint, prettier)

`.taskcheck`:
- Not run (the fix touches only e2e specs and docs, which `.taskcheck` does not run; the parent worker runs it
  before `done`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
