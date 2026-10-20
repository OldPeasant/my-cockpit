---
title: Application shell: navigation, top bar, palette skeleton, placeholders
status: in_progress
owner: dell26/agent-2
deps: [p0-web-api-client]
touches: [web/src/app/shell,web/src/app/app.routes.ts,web/src/app/app.ts,web/src/app/app.html,web/src/app/app.scss,web/src/app/app.spec.ts,web/src/app/features]
prio: 1
lease_until: 2026-10-20T10:12:54Z
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
- ASSUMPTION (2026-10-20 11:40+0200 by dell26/agent-2): Theme is applied before first paint by web/public/theme-init.js (synchronous <script src> in index.html, outside the task touches) instead of an inline script, so a strict CSP (p0-deploy) needs no hash; key 'cockpit.theme', data-theme='light|dark|system' on <html>.
- ASSUMPTION (2026-10-20 11:40+0200 by dell26/agent-2): Q and ? are ignored while typing in inputs/textareas/selects/contenteditable; Ctrl+K and Ctrl+Shift+Space work everywhere (SPEC 4.5 limits only Q to 'not in an input'; Ctrl+Shift+Space produces no text). Meta+K also opens the palette (macOS). Single-key shortcuts are ignored while a dialog is open.
- ASSUMPTION (2026-10-20 11:40+0200 by dell26/agent-2): GTD sections use top-level URLs (/inbox, /next, /projects, /waiting, /someday, /logbook) via features/gtd/gtd.routes.ts mounted at '' after the other sections; each view loads a sub-folder routes file (gtd/inbox, gtd/next, gtd/projects, gtd/lists/{waiting,someday,logbook}.routes.ts) matching the GTD view tasks' touches.
- ASSUMPTION (2026-10-20 11:40+0200 by dell26/agent-2): Feature tasks plug into the shell without touching app files: CAPTURE_DIALOG comes from features/capture/capture.providers.ts provideCapture(), extra PALETTE_PROVIDERS (multi) from features/search/search.providers.ts provideSearch(); the shell adds 'Go to <section>' palette commands. PaletteProvider.items(query) returns an Observable; the palette switchMaps per query so stale results are dropped.
- ASSUMPTION (2026-10-20 11:40+0200 by dell26/agent-2): Shell, not-found page and capture placeholder dialog are lazy-loaded so the initial bundle stays under the 500 kB budget warning (452 kB vs 548 kB eager). No icon font (would need an external or self-hosted font): the shell uses a few inline SVG icons.
- ASSUMPTION (2026-10-20 11:50+0200 by dell26/agent-2): Correction: while any dialog is open, every shell shortcut except Ctrl+K is ignored (Q, ?, and Ctrl+Shift+Space), so capture never opens on top of another dialog or itself.
