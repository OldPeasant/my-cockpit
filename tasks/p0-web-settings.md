---
title: Settings page: structure and general section
status: in_progress
owner: dell26/agent-3
deps: [p0-web-shell,p0-settings]
touches: [web/src/app/features/settings]
prio: 2
lease_until: 2026-10-20T12:51:48Z
---
## Goal
Settings page with one sub-route per section, created up front so section tasks only touch their own folder.

## Acceptance criteria
- [ ] `settings.routes.ts` has child routes + placeholders for: general, gtd, security, calendars, integrations, mail, backup
- [ ] General section edits time zone (IANA list), week start, theme, energy/creativity labels via the settings API with optimistic update + error rollback; the server theme setting wins over the local one at login (ThemeService)
- [ ] Vitest component/service tests cover general section save and rollback on error; `cd web && npm run check` passes

## Notes
