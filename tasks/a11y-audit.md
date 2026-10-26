---
title: Accessibility audit
status: done
owner: dell26/agent-1
deps: [p6-smoke]
touches: [web/e2e/a11y]
prio: 3
lease_until: 
extended: dell26/agent-1
---
## Goal
Accessible web UI.

## Acceptance criteria
- [x] axe-core Playwright scan of every main page in light and dark theme: no serious/critical violations (fixes included); keyboard-only walkthrough of capture and inbox processing
  - verified (2026-10-26 11:26+0100 by dell26/agent-1): npm --prefix web run e2e (Playwright chromium against podman deploy/docker-compose.e2e.yml stack, HEAD 94756fbf0) -> 69 passed in 3.0m, incl. e2e/a11y/a11y.spec.ts: axe-core WCAG 2.1 A/AA scan of sign-in + every main page (shell sections, 4 calendar views, capture dialog, command palette, inbox processing, trash with item) in light and dark: 0 serious/critical; keyboard-only capture + inbox processing walkthrough passed

## Notes
- ASSUMPTION (2026-10-26 10:47+0100 by dell26/agent-1): Audit = axe-core WCAG 2.1 A/AA tags, failing only on serious/critical impact; 'every main page' = every shell section of app.routes.ts/gtd.routes.ts (4 calendar views) plus login, capture dialog, command palette and inbox processing mode, with seeded inbox/next tasks (other pages scanned with their empty states)
- ASSUMPTION (2026-10-26 10:48+0100 by dell26/agent-1): Fixes outside touches (web/e2e/a11y): calendar-view.ts/.scss, inbox-page.scss, markdown-editor.scss, and playwright.config.ts runs a11y/ in the inbox-exclusive project (it processes the whole inbox and sets the shared account's theme via PATCH /settings, restored to system afterwards). The criterion requires the fixes.
- ASSUMPTION (2026-10-26 10:48+0100 by dell26/agent-1): Calendar time grid: FullCalendar 7 renders its scroller as a generic div inside role=rowgroup; to make it keyboard-scrollable without breaking ARIA ownership, the scroller gets tabindex=0 + role=rowgroup and its parent role=none (DOM patch after datesSet). Revisit if FullCalendar adds a native option.
- ASSUMPTION (2026-10-26 11:04+0100 by dell26/agent-1): Trash: the 'Delete permanently' button's aria-label changed from 'Delete <title> permanently' to 'Delete permanently: <title>' (WCAG 2.5.3 label-in-name, found by the full e2e run where trash had items); trash-page.ts is outside touches, the criterion requires the fix. The a11y spec now seeds one trashed task.
- ASSUMPTION (2026-10-26 11:11+0100 by dell26/agent-1): Markdown editor (notes/journal): CodeMirror's defaultHighlightStyle replaced by a same-shaped HighlightStyle using theme tokens (--app-accent, --mat-sys-tertiary/secondary, --app-danger, --app-fg-muted), since its literal colors (#219 URLs) fail AA contrast in dark; adds direct dep @lezer/highlight (already transitive). Outside touches; required by the criterion.
- extended by dell26/agent-1: Implementation complete: axe-core scan (light+dark, all main pages) + keyboard walkthrough in web/e2e/a11y, fixes for calendar, journal, inbox, trash label-in-name, markdown editor highlight contrast; unit tests committed; .taskcheck passes at 7719939fa. Left: re-run test-writer + code-reviewer on the final code (trash label + markdown HighlightStyle commits 663d94d67..7719939fa are newer than review.md), re-run full Playwright e2e a11y suite, tasks.py verify #1, write quality.json, done.
- done by dell26/agent-1: `sh scripts/check.sh` passed in 82s on b5215d3a1; criteria verified 1/1
