---
title: CI: split the fast check into parallel jobs per part
status: todo
owner:
deps: []
touches: [.github/workflows/ci.yml,scripts/check.sh,docs/ARCHITECTURE.md]
prio: 1
---
## Goal
The CI job "Fast check (scripts/check.sh)" runs server, web and Android one after another and now takes ~32 min on
GitHub runners (server tests alone 14.5 min, run 39625365916) against `timeout-minutes: 40`. As the code grows it
will time out. Run the parts as parallel jobs so each has room and the whole result arrives sooner.

## Acceptance criteria
- [ ] `.github/workflows/ci.yml` runs the check as separate jobs (tfp gate tests, server, web, android), each with
  only the setup it needs and its own `timeout-minutes` with clear headroom over its measured duration
- [ ] CI still runs exactly what agents run: the jobs call `scripts/check.sh` with a part selector (e.g.
  `sh scripts/check.sh server`); `sh scripts/check.sh` without arguments behaves as before (`.taskcheck`,
  `COCKPIT_NO_ANDROID=1` still work)
- [ ] A push or PR run on GitHub shows all jobs green with their durations (run id in the evidence); the comment
  header of ci.yml and docs/ARCHITECTURE.md "Test"/CI section are updated if they describe the job layout

## Notes
- The integration-test job fails for an unrelated reason (flaky Radicale tests, task cal-caldav-retry); a red
  integration job does not block this task's third criterion as long as the check jobs are green.
- server/gradle.properties raises the Gradle/Kotlin heap (clean test compile ran out of memory, 2026-10-21).
