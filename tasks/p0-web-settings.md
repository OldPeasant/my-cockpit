---
title: Settings page: structure and general section
status: in_progress
owner: dell26/agent-3
deps: [p0-web-shell,p0-settings]
touches: [web/src/app/features/settings]
prio: 2
lease_until: 2026-10-20T12:55:41Z
---
## Goal
Settings page with one sub-route per section, created up front so section tasks only touch their own folder.

## Acceptance criteria
- [ ] `settings.routes.ts` has child routes + placeholders for: general, gtd, security, calendars, integrations, mail, backup
- [ ] General section edits time zone (IANA list), week start, theme, energy/creativity labels via the settings API with optimistic update + error rollback; the server theme setting wins over the local one at login (ThemeService)
- [ ] Vitest component/service tests cover general section save and rollback on error; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-20 14:37+0200 by dell26/agent-3): Server theme sync at login: provideSettingsSync() (settings folder) is added to the shell route providers in app.routes.ts (outside touches, one line); it loads GET /settings whenever AuthService.user() becomes set and applies its theme via ThemeService.setMode. Load failures are silent and keep the local theme.
- ASSUMPTION (2026-10-20 14:37+0200 by dell26/agent-3): General opens at /settings itself (no redirect to /settings/general) so the auth guard's returnUrl stays /settings; other sections at /settings/<gtd|security|calendars|integrations|mail|backup>. app.spec.ts placeholder test now skips /settings (BUILT list) and has a settings-page test.
- ASSUMPTION (2026-10-20 14:37+0200 by dell26/agent-3): General section auto-saves per row (selects/toggle on change, labels and time zone on change/blur) instead of a Save button; saves are serialized so each PATCH sends the latest ETag; after a 412 the settings are reloaded and the change is rolled back with a toast.
