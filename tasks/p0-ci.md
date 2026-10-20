---
title: GitHub Actions CI
status: todo
owner:
deps: [p0-deploy]
touches: [.github]
prio: 2
---
## Goal
CI that runs what agents run, with the same runtimes.

## Acceptance criteria
- [ ] Workflow on push/PR: JDK 21, Node from `.nvmrc`, Android SDK (platform 37, build-tools 36); runs `sh scripts/check.sh`, `./gradlew integrationTest`, `docker build .`
- [ ] Versions match `.nvmrc`/Gradle toolchains (reviewer compares); workflow validated with `actionlint` or `gh workflow view` after push (if it can't run here: skip with reason, the owner checks the first run)

## Notes
