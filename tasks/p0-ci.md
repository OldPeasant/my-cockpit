---
title: GitHub Actions CI
status: in_progress
owner: dell26/agent-1
deps: [p0-deploy]
touches: [.github]
prio: 2
lease_until: 2026-10-20T10:02:56Z
---
## Goal
CI that runs what agents run, with the same runtimes.

## Acceptance criteria
- [ ] Workflow on push/PR: JDK 21, Node from `.nvmrc`, Android SDK (platform 37, build-tools 36); runs `sh scripts/check.sh`, `./gradlew integrationTest`, `docker build .`
- [ ] Versions match `.nvmrc`/Gradle toolchains (reviewer compares); workflow validated with `actionlint` or `gh workflow view` after push (if it can't run here: skip with reason, the owner checks the first run)

## Notes
- ASSUMPTION (2026-10-20 11:41+0200 by dell26/agent-1): CI split into 3 parallel jobs (check, integration, image) on ubuntu-24.04 with Temurin 21, actions pinned to major tags (checkout/setup-java/setup-node v5, setup-gradle v5, setup-android v3); faster feedback than one serial job
- ASSUMPTION (2026-10-20 11:41+0200 by dell26/agent-1): Android SDK in CI: android-actions/setup-android installs 'platforms;android-37.0' and 'build-tools;36.0.0' (same packages as ARCHITECTURE.md Install); COCKPIT_NO_ANDROID is not set so Android is checked in CI
- ASSUMPTION (2026-10-20 11:47+0200 by dell26/agent-1): CI triggers: push to main + pull_request only, ignoring tasks/**, reviews/**, BOARD.md, and never cancelling main runs; agent board commits are frequent and wip/* pushes are backups, not candidates
