---
title: Phase 0 demo script + README
status: in_progress
owner: dell26/agent-1
deps: [p0-e2e-harness,p0-web-settings,p0-web-sse,p0-ci,p0-web-devices,p0-trash-core,p0-search-core]
touches: [web/e2e/phase0,README.md]
prio: 1
lease_until: 2026-10-20T16:41:47Z
---
## Goal
Automate SPEC.md §13.2 Phase 0 and write the project README.

## Acceptance criteria
- [ ] `web/e2e/phase0/*.spec.ts` covers demo steps 1–5 (setup, TOTP login, backoff after 5 failures, recovery code single use, palette, theme persists across reload) and passes against the e2e stack
- [ ] README.md: what this is, 5-line quick start (dev), deploy pointer, links to SPEC/ARCHITECTURE/TEAM
- [ ] Any defect found is fixed here or recorded as a new task

## Notes
