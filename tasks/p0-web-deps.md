---
title: Web dependencies, theme and i18n baseline
status: todo
owner:
deps: []
touches: [web/package.json,web/package-lock.json,web/angular.json,web/src/styles.scss,web/src/index.html,web/src/app/app.config.ts,web/src/theme]
prio: 1
---
## Goal
Add the web libraries the spec needs up front (SPEC.md §2.1) and a Material 3 light/dark theme.

## Acceptance criteria
- [ ] package.json adds: @angular/material + @angular/cdk, @angular/localize, CodeMirror 6 (`@codemirror/*`, markdown language), @fullcalendar/angular + core/daygrid/timegrid/interaction/list/rrule, TipTap (`@tiptap/core`, starter-kit, link, image), pdfjs-dist, ng-openapi-gen (dev), @playwright/test + otplib (dev), @axe-core/playwright (dev); `npm ci` works from the lockfile
- [ ] Material 3 theme with design tokens on `:root`, light + dark (`prefers-color-scheme` + explicit `data-theme` override); no hard-coded colors in `app.*`
- [ ] `@angular/localize` set up (`$localize`/`i18n` usable, English source locale)
- [ ] `npm run check` passes; ARCHITECTURE.md stack table updated

## Notes
