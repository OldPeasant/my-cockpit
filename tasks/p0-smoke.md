---
title: Phase 0 demo script + README
status: in_progress
owner: dell26/agent-1
deps: [p0-e2e-harness,p0-web-settings,p0-web-sse,p0-ci,p0-web-devices,p0-trash-core,p0-search-core]
touches: [web/e2e/phase0,README.md]
prio: 1
lease_until: 2026-10-20T16:51:54Z
---
## Goal
Automate SPEC.md §13.2 Phase 0 and write the project README.

## Acceptance criteria
- [ ] `web/e2e/phase0/*.spec.ts` covers demo steps 1–5 (setup, TOTP login, backoff after 5 failures, recovery code single use, palette, theme persists across reload) and passes against the e2e stack
- [ ] README.md: what this is, 5-line quick start (dev), deploy pointer, links to SPEC/ARCHITECTURE/TEAM
- [ ] Any defect found is fixed here or recorded as a new task

## Notes
- ASSUMPTION (2026-10-20 18:36+0200 by dell26/agent-1): Phase 0 step 1 is automated against the e2e stack (deploy/docker-compose.e2e.yml: server + Postgres, no Caddy), as the criterion says: the spec checks /actuator/health is UP, not behind the proxy. The Caddy path is covered by step 6 (H, owner's deploy).
- ASSUMPTION (2026-10-20 18:36+0200 by dell26/agent-1): Phase demo specs (e2e/phase<N>/) run as their own Playwright project 'phase-demos' that every other spec depends on, because they reset the stack, trigger the global login backoff and move the server clock; a failing demo skips the rest (rerun with --no-deps). Edited web/playwright.config.ts outside the task's touches for this.
- ASSUMPTION (2026-10-20 18:36+0200 by dell26/agent-1): Defect fixed here (criterion 3): the top-bar theme menu set only the local theme and the settings sync on login/reload reverted it to the server value. The menu now saves via a THEME_PREFERENCE shell token that provideSettingsSync backs with SettingsService.update (edits in web/src/app/shell and features/settings, outside touches).
