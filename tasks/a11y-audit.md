---
title: Accessibility audit
status: in_progress
owner: dell26/agent-1
deps: [p6-smoke]
touches: [web/e2e/a11y]
prio: 3
lease_until: 2026-10-26T10:10:58Z
---
## Goal
Accessible web UI.

## Acceptance criteria
- [ ] axe-core Playwright scan of every main page in light and dark theme: no serious/critical violations (fixes included); keyboard-only walkthrough of capture and inbox processing

## Notes
- ASSUMPTION (2026-10-26 10:47+0100 by dell26/agent-1): Audit = axe-core WCAG 2.1 A/AA tags, failing only on serious/critical impact; 'every main page' = every shell section of app.routes.ts/gtd.routes.ts (4 calendar views) plus login, capture dialog, command palette and inbox processing mode, with seeded inbox/next tasks (other pages scanned with their empty states)
- ASSUMPTION (2026-10-26 10:48+0100 by dell26/agent-1): Fixes outside touches (web/e2e/a11y): calendar-view.ts/.scss, inbox-page.scss, markdown-editor.scss, and playwright.config.ts runs a11y/ in the inbox-exclusive project (it processes the whole inbox and sets the shared account's theme via PATCH /settings, restored to system afterwards). The criterion requires the fixes.
- ASSUMPTION (2026-10-26 10:48+0100 by dell26/agent-1): Calendar time grid: FullCalendar 7 renders its scroller as a generic div inside role=rowgroup; to make it keyboard-scrollable without breaking ARIA ownership, the scroller gets tabindex=0 + role=rowgroup and its parent role=none (DOM patch after datesSet). Revisit if FullCalendar adds a native option.
