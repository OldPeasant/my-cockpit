---
title: Unknown /download/* paths return 404, not the SPA index.html
status: todo
owner:
deps: [and-apk-distribution]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/spa,server/src/test/kotlin/ch/sonensei/cockpit/core/spa]
prio: 2
---
## Goal
`/download/` is a server prefix (and-apk-distribution, review finding CR-1), but
`SpaFallbackResourceResolver.SERVER_PREFIXES` only lists `api` and `actuator`, so a logged-in
request to an unknown path such as `/download/ios` gets the SPA `index.html` with 200.

## Acceptance criteria
- [ ] `download` is in `SERVER_PREFIXES` (KDoc updated); an unknown `/download/...` path returns 404 for a logged-in user; web test
- [ ] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.spa.*'` passes

## Notes
