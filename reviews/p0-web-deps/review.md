# Code Review

Date: 2026-10-20

Task: p0-web-deps - Web dependencies, theme and i18n baseline

Scope: task commits 4b778d3, ca3afc3 and 74ca75e on top of base fda2f09 (`git diff fda2f0926bf562603f7243a8ceb9abd40c8163c4..74ca75e014b74c8da78103f9d2228111ae4c04c6 -- web docs`). This is a re-review: the earlier review covered fda2f09..ca3afc3, and 74ca75e only adds tests (`web/src/app/app.spec.ts`, `web/src/app/app.i18n.spec.ts`). I only checked the bulk of `web/package-lock.json` for consistency with `web/package.json`. I reviewed committed changes only. The working tree had only untracked `reviews/`.

Requirements:
- `tasks/p0-web-deps.md` acceptance criteria and recorded assumptions
- `SPEC.md` §2.1 (tech stack), §163 (English first, i18n-ready), §214/§500 (theme light/dark/system), open question 2 (default Material 3 colors)
- `docs/ARCHITECTURE.md`, `docs/QUALITY.md`, `AGENTS.md` project notes
- Parent dispositions for CR-1 and CR-2 of the previous review (both accepted, assumptions recorded)

Git range:
- Branch: main
- HEAD: local `main` is at af67d30, which is 74ca75e rebased onto newer main commits. `git diff 74ca75e af67d30 -- web` is empty. The other differences are `scripts/check.sh` (the `COCKPIT_NO_ANDROID` switch), `tasks.py`, `tasks/` and `tests/`, all from other commits. Under the `docs/QUALITY.md` "Freshness" rules this rebase is allowed.
- Reviewed revision: 74ca75e014b74c8da78103f9d2228111ae4c04c6
- Base/start: fda2f0926bf562603f7243a8ceb9abd40c8163c4
- Commits reviewed: 3 (4b778d3, ca3afc3, 74ca75e)

Summary: 0 P0, 0 P1, 0 P2, 3 P3

## Commands run

- `cd web && npm run check` (run on the working tree at af67d30, whose `web/` is identical to 74ca75e) -> pass. Lint: "All files pass linting". Vitest: 2 files, 7 tests passed. `ng build` OK: main 208.52 kB, styles 9.59 kB.
- From the previous review, still valid because no production code changed: the lockfile vs package.json comparison, `npm ls --depth=0`, the FullCalendar dist-tag and peer checks, the built-CSS inspection (`light-dark(`, `[data-theme=light|dark]`), and the grep for literal colors in `web/src/app`.
- `node_modules/@angular/build/src/builders/unit-test/runners/vitest/plugins.js:195` and `schema.json` "isolate" -> the Angular unit-test builder defaults to Vitest `isolate: false`, and `angular.json` does not override it (this is the evidence for CR-3).
- I tried to run the suite in one worker (`VITEST_MAX_WORKERS=1`, or a `--runner-config` file in /tmp). The reviewer sandbox did not permit it, so CR-3 is not reproduced empirically.

## Review of the new tests (74ca75e)

- `app.spec.ts` adds exact-text checks for the English source title and the `i18n`-marked scaffold paragraph. These show that the `:@@id:` metadata is stripped and the source locale renders. A third test shows the `$localize` global from `@angular/localize/init` is present and returns the source text. The rest of the change only collapses `.compileComponents()` onto one line (formatting).
- `app.i18n.spec.ts` loads fictitious German translations, keyed by the real custom ids `app.title` and `app.scaffold` (they match `app.ts` and `app.html`), and checks that both the `$localize` title and the template `i18n` text are translated. `afterEach` calls `clearTranslations()`. This is meaningful evidence for criterion 3: the ids are wired, so a later locale build will pick them up.
- The tests check rendered DOM, not implementation details. No existing test was weakened or removed.

## Acceptance criteria check

1. Libraries: all required packages are present, and the lockfile matches package.json. Met. The parent still has to record `npm ci` evidence.
2. Theme: `mat.theme` system tokens and `--app-*` tokens, `color-scheme: light dark` following `prefers-color-scheme`, and `data-theme` overrides. Met.
3. Localize: the polyfill, types, `sourceLocale: en`, `extract-i18n` and `npm run i18n:extract` are set up. Rendering is now covered by tests for both the source locale and loaded translations. Met.
4. `npm run check` passes, and ARCHITECTURE.md is updated. Met.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Theme declares Roboto but no Roboto font is loaded**
    - File: `web/src/theme/_theme.scss:20`
    - Category: Quality
    - Problem: `plain-family` and `brand-family` start with `Roboto`, but no font is loaded, so the UI falls back to system fonts when Roboto is not installed.
    - Why it matters: rendering differs between machines.
    - Suggested fix: load a self-hosted Roboto later, or keep the documented fallback.
    - Status at 74ca75e: unchanged in code. The parent accepted it: system font fallbacks stay until a later shell task loads fonts, and an assumption is recorded. That disposition is reasonable.

- [ ] **CR-2 | Files changed outside the declared `touches`, plus formatting-only churn**
    - File: `web/tsconfig.app.json:6`, `web/tsconfig.spec.json:6`, `web/src/main.ts:1`, `web/src/locale/messages.xlf:1`
    - Category: Quality
    - Problem: the task changes files outside its `touches`, and some of the changes are Prettier-only reformatting.
    - Why it matters: more merge-conflict risk for parallel tasks.
    - Suggested fix: name the files in an assumption, and avoid formatting-only edits.
    - Status at 74ca75e: the parent accepted it. The assumption now names `main.ts` and the tsconfig files, and the formatting was left as is to avoid more churn. 74ca75e adds another small formatting-only change in `app.spec.ts` (`.compileComponents()` joined onto one line). It is harmless, and the file is a task-owned placeholder.

- [ ] **CR-3 | The i18n spec relies on per-file isolation, which the test builder does not turn on**
    - File: `web/src/app/app.i18n.spec.ts:5`
    - Category: Tests
    - Problem: the spec's own comment says Angular caches a component's i18n template constants on its first render, so the translations must be loaded before `App` first renders, and that is why this spec has its own file. But the Angular unit-test builder runs Vitest with `isolate: false` by default (`@angular/build/.../vitest/plugins.js:195`), and `angular.json` has no `isolate` option. A separate file only isolates when the two spec files land in different workers. Here that happens because there are 16 CPUs, so many workers. On a machine with few cores (Vitest uses about one fewer worker than CPUs), or after more spec files are added, `app.spec.ts` could render `App` first in the same worker. The `app.scaffold` assertion (`app.i18n.spec.ts:30-36`) would then see the cached English text, and the test would fail depending on file order. The `$localize` title test is not affected, because the field initializer runs again for each instance.
    - Why it matters: `.taskcheck` could fail intermittently on slower or smaller agent machines, for reasons unrelated to the task being checked.
    - Suggested fix: make the assumption the comment depends on explicit. Either set `"options": { "isolate": true }` on the `test` target in `angular.json` (this costs some speed), or remove the dependency on first render (for example, assert only translated `$localize` output, or render a small dedicated test component instead of `App`). At minimum, confirm the spec passes with `VITEST_MAX_WORKERS=1` in both file orders.

## Review conclusion

No material issues found. All three findings are P3 and do not block completion. CR-1 and CR-2 are accepted by the parent with recorded assumptions. CR-3 is a test-robustness improvement the parent may fix or accept.
