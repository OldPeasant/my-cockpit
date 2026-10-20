---
title: Web dependencies, theme and i18n baseline
status: in_progress
owner: dell26/agent-3
deps: []
touches: [web/package.json,web/package-lock.json,web/angular.json,web/src/styles.scss,web/src/index.html,web/src/app/app.config.ts,web/src/theme]
prio: 1
lease_until: 2026-10-20T09:23:09Z
releases: 0
extended: 
hold: 
---
## Goal
Add the web libraries the spec needs up front (SPEC.md §2.1) and a Material 3 light/dark theme.

## Acceptance criteria
- [x] package.json adds: @angular/material + @angular/cdk, @angular/localize, CodeMirror 6 (`@codemirror/*`, markdown language), @fullcalendar/angular + core/daygrid/timegrid/interaction/list/rrule, TipTap (`@tiptap/core`, starter-kit, link, image), pdfjs-dist, ng-openapi-gen (dev), @playwright/test + otplib (dev), @axe-core/playwright (dev); `npm ci` works from the lockfile
  - verified (2026-10-20 10:48+0200 by R1963MACOS/agent-1): cd web && npm ci (node 24.21.0, npm 11.19.0) -> installed from lockfile, no errors; npm ls --depth=0 lists @angular/material+cdk+localize 22.2.3, @codemirror/* incl lang-markdown, @fullcalendar/angular+core+rrule 7.1.3 and fullcalendar 7.1.3 (exports daygrid/timegrid/interaction/list), @tiptap/core+starter-kit+extension-link+extension-image 3.32.0, pdfjs-dist 6.4.299, ng-openapi-gen, @playwright/test, otplib, @axe-core/playwright; nothing missing or invalid
- [x] Material 3 theme with design tokens on `:root`, light + dark (`prefers-color-scheme` + explicit `data-theme` override); no hard-coded colors in `app.*`
  - verified (2026-10-20 10:48+0200 by R1963MACOS/agent-1): cd web && npm run check (ng build) then grep built dist/web/browser/styles-*.css -> :root has color-scheme:light dark with --mat-sys-* light-dark() tokens and --app-* tokens, :root[data-theme=light]{color-scheme:light} and :root[data-theme=dark]{color-scheme:dark} present; grep for #hex/rgb/hsl in web/src/app/app.* -> no matches
- [x] `@angular/localize` set up (`$localize`/`i18n` usable, English source locale)
  - verified (2026-10-20 10:48+0200 by R1963MACOS/agent-1): angular.json i18n.sourceLocale=en, @angular/localize/init polyfill; cd web && npm run check -> app.spec.ts (English source title via $localize, i18n template text) and app.i18n.spec.ts (loadTranslations for ids app.title/app.scaffold renders translations) pass, 7/7 tests
- [x] `npm run check` passes; ARCHITECTURE.md stack table updated
  - verified (2026-10-20 10:48+0200 by R1963MACOS/agent-1): cd web && npm run check -> eslint all files pass, vitest 2 files/7 tests passed, ng build ok; docs/ARCHITECTURE.md stack table Web row lists Material/CDK, localize, CodeMirror, FullCalendar, TipTap, pdfjs-dist, ng-openapi-gen, Playwright, axe, otplib

## Notes
- ASSUMPTION (2026-10-20 10:32+0200 by dell26/agent-2): FullCalendar v7 ships daygrid/timegrid/interaction/list as subpath exports of the 'fullcalendar' package (no separate @fullcalendar/daygrid etc. v7 releases); added 'fullcalendar' + @fullcalendar/core/angular/rrule (+ rrule, temporal-polyfill peers) instead of separate plugin packages
- ASSUMPTION (2026-10-20 10:33+0200 by dell26/agent-2): Theme uses default Material 3 azure/blue palettes and Roboto (SPEC open question 2: default Material 3); data-theme='system' or absent follows prefers-color-scheme
- ASSUMPTION (2026-10-20 10:33+0200 by dell26/agent-2): Touched web/src/app/app.{html,ts,scss} (outside declared touches) minimally to prove $localize/i18n works and replace styles with tokens; committed extracted web/src/locale/messages.xlf as the i18n source file
- ASSUMPTION (2026-10-20 10:37+0200 by dell26/agent-2): Also touched web/src/main.ts, web/tsconfig.app.json, web/tsconfig.spec.json (outside declared touches) for @angular/localize types; Roboto is named but not self-hosted yet, system fallbacks apply until a shell task loads fonts
- release by dell26/agent-2: handed off by dell26/agent-2: You've hit your session limit · resets 2pm (Europe/Berlin)
- hold by R1963MACOS/agent-1: Implementation, verify evidence and review evidence (reviews/p0-web-deps, reviewed bdb9141) are complete and committed; web check passes. done is REFUSED only because .taskcheck's server tests need Testcontainers Postgres and the Docker daemon is not running on R1963MACOS (Cannot connect to the Docker daemon at ~/.docker/run/docker.sock; agent may not start Docker Desktop). Start Docker on this machine (or run done from a machine with Docker), unhold, and run: python tasks.py done p0-web-deps
