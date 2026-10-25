# Fix Report

Date: 2026-10-25

Task: ci-split-check - CI: split the fast check into parallel jobs per part

Scope: current task; findings CR-1 and CR-2 of `reviews/ci-split-check/review.md`, changes limited to `.github/workflows/ci.yml` as instructed by the parent worker.

## Fixed findings

### CR-1 — Web/android timeout headroom is not measured yet

Files changed:
- `.github/workflows/ci.yml`

Problem:
check-web (20 min) and check-android (30 min) timeouts were guesses. Only the server duration (14.5 min) was measured, so "clear headroom" could not be shown for web/android.

Fix:
Parent-approved approach. Per-job durations cannot be measured here (no gh access), so an upper bound is used instead. The serial check took 32 min, of which the server took 14.5 min. tfp, web, android and setup together therefore took at most ~17.5 min, so no single one of them took longer than that. check-web and check-android `timeout-minutes` are now both 35, about 2x that bound. check-server stays 35 (2.4x the measured 14.5 min) and check-tfp stays 10.

Validation:
- `sh tests/test_check_sh.sh` — Pass (all check.sh selector tests passed, including the ci.yml job checks)
- `docker run --rm -v "$PWD:/repo:Z" -w /repo docker.io/rhysd/actionlint:latest` — Pass (exit 0, no findings)

### CR-2 — ci.yml comment implies all timeouts are based on measurements

Files changed:
- `.github/workflows/ci.yml`

Problem:
The comment above the check jobs said the timeouts were "about twice the measured duration", but only the server duration was measured.

Fix:
Rewrote the comment. It now says that the server was measured at 14.5 min in run 39625365916, and that web/android were not measured separately: they are bounded by the remaining ~17.5 min of the 32 min serial run, and their timeouts sit at ~2x that bound.

Validation:
- `docker run --rm -v "$PWD:/repo:Z" -w /repo docker.io/rhysd/actionlint:latest` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `sh tests/test_check_sh.sh` — Pass (exit 0)
- `docker run --rm -v "$PWD:/repo:Z" -w /repo docker.io/rhysd/actionlint:latest` — Pass (exit 0)

Full test suite:
- Not run. The change only touches the YAML timeout values and a comment in ci.yml. No code that the Gradle or npm suites exercise was changed.

Lint/type-check/build:
- actionlint on all workflows — Pass

`.taskcheck`:
- Not run. The parent worker runs it before `done`. The change does not affect `scripts/check.sh` or any part it checks.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.

Note for the parent worker: criterion 1 still rests on an upper bound, not on measured per-job durations. A green GitHub run (criterion 3) would provide the real per-job times that confirm the 35 min limits.
