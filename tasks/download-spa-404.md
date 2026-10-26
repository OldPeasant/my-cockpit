---
title: Unknown /download/* paths return 404, not the SPA index.html
status: in_progress
owner: dell26/agent-1
deps: [and-apk-distribution]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/spa,server/src/test/kotlin/ch/sonensei/cockpit/core/spa]
prio: 2
lease_until: 2026-10-26T01:35:29Z
---
## Goal
`/download/` is a server prefix (and-apk-distribution, review finding CR-1), but
`SpaFallbackResourceResolver.SERVER_PREFIXES` only lists `api` and `actuator`, so a logged-in
request to an unknown path such as `/download/ios` gets the SPA `index.html` with 200.

## Acceptance criteria
- [x] `download` is in `SERVER_PREFIXES` (KDoc updated); an unknown `/download/...` path returns 404 for a logged-in user; web test
  - verified (2026-10-26 02:04+0100 by dell26/agent-1): SERVER_PREFIXES = api, actuator, download + KDoc; SpaFallbackTests 'unknown download path is 404, not index html' (@WithCockpitUser, /download/ios, /download/, /download -> 404) + resolver test; ./gradlew test --tests 'ch.sonensei.cockpit.core.spa.*' -> pass; with fix reverted -> 3 tests fail
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.spa.*'` passes
  - verified (2026-10-26 02:04+0100 by dell26/agent-1): cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*' -> BUILD SUCCESSFUL, 19 tests, 0 failures

## Notes
