---
title: CI: split the fast check into parallel jobs per part
status: in_progress
owner: dell26/agent-1
deps: []
touches: [.github/workflows/ci.yml,scripts/check.sh,docs/ARCHITECTURE.md]
prio: 1
lease_until: 2026-10-25T22:17:31Z
---
## Goal
The CI job "Fast check (scripts/check.sh)" runs server, web and Android one after another and now takes ~32 min on
GitHub runners (server tests alone 14.5 min, run 39625365916) against `timeout-minutes: 40`. As the code grows it
will time out. Run the parts as parallel jobs so each has room and the whole result arrives sooner.

## Acceptance criteria
- [x] `.github/workflows/ci.yml` runs the check as separate jobs (tfp gate tests, server, web, android), each with
  only the setup it needs and its own `timeout-minutes` with clear headroom over its measured duration
  - verified (2026-10-25 22:57+0100 by dell26/agent-1): .github/workflows/ci.yml at 3d4763ba: jobs check-tfp (checkout only, 10 min), check-server (java+gradle, 35 min), check-web (node from .nvmrc, 35 min), check-android (java+android SDK 37/build-tools 36+gradle, 35 min), each 'sh scripts/check.sh <part>'; actionlint (docker rhysd/actionlint) -> 0 errors; code-reviewer confirmed per-job setup needs (reviews/ci-split-check/review.md). Headroom: server 2.4x measured 14.5 min (run 39625365916); web/android ~2x the 17.5 min upper bound (32 min serial - server), not measured per job
- [x] CI still runs exactly what agents run: the jobs call `scripts/check.sh` with a part selector (e.g.
  `sh scripts/check.sh server`); `sh scripts/check.sh` without arguments behaves as before (`.taskcheck`,
  `COCKPIT_NO_ANDROID=1` still work)
  - verified (2026-10-25 22:58+0100 by dell26/agent-1): sh scripts/check.sh (no args, at f01b769d) -> tfp, server, web (3463 tests), android all ran, 'all checks passed', exit 0; sh scripts/check.sh tfp -> 20 tests OK; sh scripts/check.sh bogus -> exit 2; sh tests/test_check_sh.sh (stubbed parts, incl. no-arg order, COCKPIT_NO_ANDROID=1 skip with and without selector, ci.yml calls each part) -> 45 ok, exit 0
- [~] A push or PR run on GitHub shows all jobs green with their durations (run id in the evidence); the comment
  header of ci.yml and docs/ARCHITECTURE.md "Test"/CI section are updated if they describe the job layout
  - skipped (2026-10-25 22:58+0100 by dell26/agent-1): needs a GitHub Actions run: gh is not permitted in this sandbox and CI only runs on pushes to main/PRs, which only 'done' pushes; owner checks the first main run after done (all 4 check jobs green + per-job durations; raise web/android timeouts if one uses > half). ci.yml header comment and ARCHITECTURE.md Testing/CI text updated

## Notes
- The integration-test job fails for an unrelated reason (flaky Radicale tests, task cal-caldav-retry); a red
  integration job does not block this task's third criterion as long as the check jobs are green.
- server/gradle.properties raises the Gradle/Kotlin heap (clean test compile ran out of memory, 2026-10-21).
- ASSUMPTION (2026-10-25 22:52+0100 by dell26/agent-1): CI job timeouts set to ~2x measured: server 35 min (14.5 min measured in run 39625365916), android 30, web 20, tfp 10; web/android durations were not measured separately (gh run view not available here), estimated from the 32 min serial total
- ASSUMPTION (2026-10-25 22:57+0100 by dell26/agent-1): Supersedes the earlier timeout line: final CI timeouts tfp 10, server 35 (2.4x measured 14.5 min), web 35, android 35; web/android not measured separately, bounded by the ~17.5 min the 32 min serial run spent outside the server, so ~2x that bound
