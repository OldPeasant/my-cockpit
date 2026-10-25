---
title: APK build in CI and download from the server
status: in_progress
owner: dell26/agent-1
deps: [and-login,p0-ci,p0-deploy,ci-split-check]
touches: [.github,server/src/main/kotlin/ch/sonensei/cockpit/core/download,server/src/test/kotlin/ch/sonensei/cockpit/core/download]
prio: 2
lease_until: 2026-10-25T22:33:24Z
---
## Goal
Distribute the app without the Play Store (SPEC.md §11).

## Acceptance criteria
- [ ] CI builds a signed release APK (keystore from CI secrets; debug-signed when absent) and attaches it as an artifact
- [ ] Server serves `${COCKPIT_DATA_DIR}/android/cockpit.apk` at `/download/android` (authenticated) with version info; test
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.download.*'` passes (Postgres via Testcontainers where persistence is involved)

## Notes
