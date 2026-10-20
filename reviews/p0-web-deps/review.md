# Code Review

Date: 2026-10-20

Task: p0-web-deps - Web dependencies, theme and i18n baseline

Scope: all task commits on top of base ef7ea3f (`git diff ef7ea3f4ea9c7ea46af2a68c2fd8dbf6bfe3bbdf..c5af6ea15b6537db42936d7c1d68179f63a34e33 -- web docs`). This is a full review of the final code. It replaces the previous review, which covered an earlier revision (its commits were rebased away; `web/` was identical up to 3a50c06). For the bulk of `web/package-lock.json` I only checked that it is consistent with `web/package.json`. Files under `reviews/` are evidence, not code, and were not reviewed as code. I reviewed committed changes only. The working tree had only a modified `reviews/p0-web-deps/tests.md`.

Requirements:
- `tasks/p0-web-deps.md` acceptance criteria and the assumptions recorded in its Notes
- `SPEC.md` §2.1 tech stack (line 90: Angular Material + CDK, CodeMirror 6), line 163 (English first, i18n-ready), lines 214/500 (theme light/dark/system), open question 2 (line 821: default Material 3)
- `docs/ARCHITECTURE.md` (stack table, Install, Testing, Coverage), `docs/QUALITY.md`, `AGENTS.md` project notes
- Parent dispositions of the previous review: CR-1 and CR-2 accepted with recorded assumptions; CR-3 addressed by 11176b0

Git range:
- Branch: main
- HEAD: c5af6ea15b6537db42936d7c1d68179f63a34e33
- Base/start: ef7ea3f4ea9c7ea46af2a68c2fd8dbf6bfe3bbdf
- Commits reviewed: 6 (75cc358, 40710a8, faf31ed, 3a50c06, 11176b0, c5af6ea)

Summary: 0 P0, 0 P1, 0 P2, 3 P3 open (CR-1, CR-2, CR-4); CR-3 from the previous review is resolved

## Commands run

- `npm --prefix web run check` at c5af6ea -> pass. Lint: "All files pass linting". Vitest: 2 files, 7 tests passed. Production `ng build` OK: main 208.52 kB, styles 9.59 kB, polyfills 534 bytes.
- `npm --prefix web ls --depth=0` -> every required package is installed at the locked version, with no missing, invalid or extraneous entries.
- Node comparison of `package-lock.json` `packages[""]` with `package.json` -> `dependencies` and `devDependencies` are identical, so the lockfile is in sync for `npm ci`. I did not run `npm ci` myself, because the reviewer must not install anything. The parent's own `npm ci` run is the evidence for criterion 1.
- `node_modules/fullcalendar/package.json` -> it has `./daygrid`, `./timegrid`, `./interaction` and `./list` subpath exports. This supports the recorded FullCalendar 7 assumption.
- Grep for literal colors (`#hex`, `rgb(a)`, `hsl(a)`) in `web/src/app`, `web/src/styles.scss` and `web/src/theme` -> none found.
- Built `dist/web/browser/styles-*.css` -> the `--mat-sys-*` colors are emitted as `light-dark(...)`, and there are `[data-theme=light]{color-scheme:light}` and `[data-theme=dark]{color-scheme:dark}` overrides.
- `@angular/build` unit-test builder: `runners/vitest/plugins.js:195` defaults Vitest to `isolate: false`. `plugins.js:218` and `executor.js:334` pass the builder's `isolate` option through, so `"options": {"isolate": true}` in `angular.json` really turns on per-file isolation.
- I tried to run the suite in a single worker (`VITEST_MAX_WORKERS=1`), but the reviewer sandbox did not permit it. Isolation is confirmed from the builder source above.

## Acceptance criteria check

1. Libraries: the required packages are present: `@angular/material`, `@angular/cdk`, `@angular/localize`, the CodeMirror 6 packages with `@codemirror/lang-markdown`, `@fullcalendar/angular`, `@fullcalendar/core` and `@fullcalendar/rrule` (FullCalendar 7 provides daygrid/timegrid/interaction/list through `fullcalendar` subpaths, per the recorded assumption), TipTap (`core`, `starter-kit`, `extension-link`, `extension-image`, with the `pm` peer), and `pdfjs-dist`. The dev dependencies `ng-openapi-gen`, `@playwright/test`, `otplib` and `@axe-core/playwright` are present. The lockfile is in sync. Met, provided the parent records its `npm ci` run.
2. Theme: `web/src/theme/_theme.scss` applies `mat.theme` on `:root` and adds `--app-*` tokens. `color-scheme: light dark` follows `prefers-color-scheme`, and `data-theme="light|dark"` overrides it. Without the attribute, or with `system`, the OS scheme applies. `app.scss` and `styles.scss` use only tokens. Met.
3. Localize: the `@angular/localize/init` polyfill, the types in both tsconfigs, `sourceLocale: "en"`, the `extract-i18n` target with `npm run i18n:extract`, and a committed `src/locale/messages.xlf` are in place. The ids `app.title` and `app.scaffold` match the source (`app.ts:11`, `app.html:3`). Both the source rendering and the translated rendering are covered by tests. Met.
4. `npm run check` passes (run above), and the ARCHITECTURE.md stack table and Install section are updated. Met.

## Tests

- `app.spec.ts` checks the exact English source text for the `$localize` title and for the `i18n` paragraph, and that the `$localize` global from the polyfill is present. Apart from that, it only collapses `.compileComponents()` onto one line (formatting).
- `app.i18n.spec.ts` loads fictitious German translations keyed by the real ids, checks that both strings are translated, and calls `clearTranslations()` in `afterEach`. Its dependency on first-render caching is now backed by `isolate: true`, and the reworded comment (c5af6ea) describes that accurately.
- The tests check rendered DOM, not implementation details. No existing test was weakened or removed. The theme is pure SCSS with no runtime logic. It is verified through the built CSS, which is reasonable for this baseline task.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Theme declares Roboto but no Roboto font is loaded**
    - File: `web/src/theme/_theme.scss:22`
    - Category: Quality
    - Problem: `plain-family` and `brand-family` start with `Roboto`, but no font is loaded, so the UI falls back to system fonts on machines without Roboto.
    - Why it matters: rendering differs between machines.
    - Suggested fix: self-host Roboto in a later shell task, or keep the documented fallback.
    - Status at c5af6ea: unchanged. The parent accepted it, with a recorded assumption ("system fallbacks apply until a shell task loads fonts"). The disposition is reasonable.

- [ ] **CR-2 | Files changed outside the declared `touches`, plus formatting-only churn**
    - File: `web/src/main.ts:1`, `web/tsconfig.app.json:6`, `web/tsconfig.spec.json:6`, `web/src/app/app.{ts,html,scss,spec.ts}`, `web/src/app/app.i18n.spec.ts`, `web/src/locale/messages.xlf`
    - Category: Quality
    - Problem: the task changes files that are not in its `touches` list. Some of the edits are Prettier-only reformatting (`main.ts:7`, the tsconfig arrays, `app.spec.ts:8`).
    - Why it matters: more merge-conflict risk for parallel tasks.
    - Suggested fix: keep the assumption that lists these files, and avoid formatting-only edits in later tasks.
    - Status at c5af6ea: the parent accepted it, with recorded assumptions. The `app.*` files are scaffold placeholders that this task needs in order to prove criteria 2 and 3.

- [x] **CR-3 | The i18n spec relies on per-file isolation, which the test builder did not turn on** (from the previous review)
    - File: `web/angular.json:84`, `web/src/app/app.i18n.spec.ts:5`
    - Category: Tests
    - Status at c5af6ea: resolved. 11176b0 sets `"isolate": true` on the `test` target. The builder passes this through to Vitest (`plugins.js:218`), so each spec file gets a fresh module graph, and the i18n spec no longer depends on how files are spread across workers. The cost is a small slowdown per file. The full web suite still runs in about 0.5 s.

- [ ] **CR-4 | Project-wide `isolate: true` for web unit tests is not documented**
    - File: `web/angular.json:84`
    - Category: Quality
    - Problem: the change turns on Vitest per-file isolation for every future web spec. This differs from the Angular default (`isolate: false`, `plugins.js:195`). The only explanation is a comment in `app.i18n.spec.ts`. The `docs/ARCHITECTURE.md` "Testing" section does not mention it.
    - Why it matters: a later task tuning test speed could remove it without knowing that `app.i18n.spec.ts` depends on it. The i18n test would then fail only on some machines. It is also a convention that future spec authors may rely on, for example module-level state no longer leaking between files.
    - Suggested fix: add a one-line rule under ARCHITECTURE.md "Testing", for example: "Web unit tests run with Vitest `isolate: true` (angular.json); each spec file gets a fresh module graph." Optional; it does not block this task.

## Review conclusion

No material issues found. The open findings are all P3 and do not block completion. CR-1 and CR-2 are accepted with recorded assumptions, CR-3 is resolved, and CR-4 is an optional documentation improvement.
