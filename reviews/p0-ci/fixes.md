# Fix Report

Date: 2026-10-20

Task: p0-ci - GitHub Actions CI

Scope: current task; finding CR-1 from `reviews/p0-ci/review.md` (reviewed HEAD 90cbf45).

## Fixed findings

### CR-1 — Unfiltered push trigger + per-ref cancel-in-progress

Files changed:
- `.github/workflows/ci.yml`
- `docs/ARCHITECTURE.md`

Problem:
`on: push:` had no branch or path filter, and `cancel-in-progress: true` also applied to `main`. Board-only
commits (heartbeat/claim/assume/verify) and `wip/*` backup pushes each started all three jobs, and runs on
`main` were cancelled by the next push, so a finished task rarely got a complete CI result.

Fix:
- `push` is limited to `branches: [main]` (`wip/*` and `status/*` pushes no longer start CI; PRs still run via `pull_request`).
- `paths-ignore` for `tasks/**`, `reviews/**` and `BOARD.md` on both `push` and `pull_request`. `board.html` was not added because it doesn't exist in the repository.
- `cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}`: runs on `main` are never cancelled, and superseded runs on other refs (PRs) still are.
- Workflow header comment extended to describe the trigger and cancellation policy.
- `docs/ARCHITECTURE.md` CI sentence changed from "on every push and PR" to describe the new triggers, the skipped paths and the no-cancel rule on `main`.

Validation:
- `docker run --rm -v <clone>:/repo:Z -w /repo docker.io/rhysd/actionlint:latest -no-color` — Pass (exit 0, no findings)

## Not fixed

None.

## Validation summary

Focused tests:
- actionlint (see above) — Pass

Full test suite:
- `sh scripts/check.sh` — Pass ("all checks passed": server, web, android, TFP tooling)

Lint/type-check/build:
- Covered by `sh scripts/check.sh` — Pass

`.taskcheck`:
- Pass (`sh scripts/check.sh`)

Remaining unresolved findings:
0

Note for the parent worker: the reviewer suggested recording the trigger policy with `tasks.py assume`; that is TFP state and was left to the parent.

## Final status

All validated findings fixed and validation passed.
