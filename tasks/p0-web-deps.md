---
title: Web dependencies, theme and i18n baseline
status: in_progress
owner: dell26/agent-2
deps: []
touches: [web/package.json,web/package-lock.json,web/angular.json,web/src/styles.scss,web/src/index.html,web/src/app/app.config.ts,web/src/theme]
prio: 1
lease_until: 2026-10-20T08:51:38Z
---
## Goal
Add the web libraries the spec needs up front (SPEC.md §2.1) and a Material 3 light/dark theme.

## Acceptance criteria
- [ ] package.json adds: @angular/material + @angular/cdk, @angular/localize, CodeMirror 6 (`@codemirror/*`, markdown language), @fullcalendar/angular + core/daygrid/timegrid/interaction/list/rrule, TipTap (`@tiptap/core`, starter-kit, link, image), pdfjs-dist, ng-openapi-gen (dev), @playwright/test + otplib (dev), @axe-core/playwright (dev); `npm ci` works from the lockfile
- [ ] Material 3 theme with design tokens on `:root`, light + dark (`prefers-color-scheme` + explicit `data-theme` override); no hard-coded colors in `app.*`
- [ ] `@angular/localize` set up (`$localize`/`i18n` usable, English source locale)
- [ ] `npm run check` passes; ARCHITECTURE.md stack table updated

## Notes
- ASSUMPTION (2026-10-20 10:32+0200 by dell26/agent-2): FullCalendar v7 ships daygrid/timegrid/interaction/list as subpath exports of the 'fullcalendar' package (no separate @fullcalendar/daygrid etc. v7 releases); added 'fullcalendar' + @fullcalendar/core/angular/rrule (+ rrule, temporal-polyfill peers) instead of separate plugin packages
- ASSUMPTION (2026-10-20 10:33+0200 by dell26/agent-2): Theme uses default Material 3 azure/blue palettes and Roboto (SPEC open question 2: default Material 3); data-theme='system' or absent follows prefers-color-scheme
- ASSUMPTION (2026-10-20 10:33+0200 by dell26/agent-2): Touched web/src/app/app.{html,ts,scss} (outside declared touches) minimally to prove $localize/i18n works and replace styles with tokens; committed extracted web/src/locale/messages.xlf as the i18n source file
