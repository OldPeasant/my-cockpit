---
title: Application shell: navigation, top bar, palette skeleton, placeholders
status: in_progress
owner: dell26/agent-2
deps: [p0-web-api-client]
touches: [web/src/app/shell,web/src/app/app.routes.ts,web/src/app/app.ts,web/src/app/app.html,web/src/app/app.scss,web/src/app/app.spec.ts,web/src/app/features]
prio: 1
lease_until: 2026-10-20T09:57:46Z
---
## Goal
The layout every feature plugs into (SPEC.md §9.1). Creates a lazy route + placeholder page for every feature so feature tasks only touch their own folder.

## Acceptance criteria
- [ ] Left navigation with all sections of SPEC.md §9.1 (Dashboard … Settings, plus Logbook and Trash); responsive (collapses to a drawer < 900 px)
- [ ] `app.routes.ts` lazy-loads `features/<feature>/<feature>.routes.ts` for every section; each placeholder file renders a 'coming soon' page; the routes file names are listed in `web/src/app/features/README.md`
- [ ] Top bar with search field (opens the command palette), capture '+' button; hotkeys `Ctrl+K` (palette), `Q` / `Ctrl+Shift+Space` (capture, ignored while typing in inputs), `?` (shortcut overview). Capture and palette contents are provided via injection tokens (`CAPTURE_DIALOG`, `PALETTE_PROVIDERS`) with placeholder implementations
- [ ] Theme toggle light/dark/system, applied before first paint and remembered in localStorage (read/write wrapped in try/catch); a `ThemeService` exposes it so p0-web-settings can sync it with the server settings
- [ ] Vitest component/service tests cover navigation rendering, hotkeys ignored in inputs, theme switching; `cd web && npm run check` passes

## Notes
