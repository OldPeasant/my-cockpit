# Code Review

Date: 2026-10-20

Task: p0-web-deps - Web dependencies, theme and i18n baseline

Scope: the final revision, `git diff 3ecd166e11427f923055712ea7d90cb0aa3f61ff..bdb91418024288742e38bb73feac34c115fe6518 -- web docs`. This re-review replaces the earlier full review of c5af6ea. `git diff c5af6ea bdb9141 -- web` is empty, so `web/` is byte-identical to the code reviewed then. The only code or doc change since then is bdb9141, which adds 3 lines to `docs/ARCHITECTURE.md` "Testing" to fix CR-4. Everything else in d8e43bf..bdb9141 is files under `reviews/`, which are evidence and were not reviewed as code. I reviewed committed changes only.

Requirements:
- `tasks/p0-web-deps.md` acceptance criteria and the assumptions recorded in its Notes
- `SPEC.md` §2.1 tech stack, English-first/i18n-ready, theme light/dark/system, open question 2 (Material 3 default)
- `docs/ARCHITECTURE.md` (stack table, Install, Testing, Coverage), `docs/QUALITY.md`, `AGENTS.md` project notes
- `reviews/p0-web-deps/fixes.md` (CR-4 fix) and the parent dispositions of CR-1 and CR-2 (accepted, with recorded assumptions)

Git range:
- Branch: main
- HEAD: bdb91418024288742e38bb73feac34c115fe6518
- Base/start: 3ecd166e11427f923055712ea7d90cb0aa3f61ff
- Commits reviewed: 7 (d492875, a9d3055, 0456442, 74d6136, 4ca96bd, d8e43bf, bdb9141). db4b907 is evidence only.

Summary: 0 P0, 0 P1, 0 P2, 2 P3 open (CR-1, CR-2, both accepted with recorded assumptions). CR-3 and CR-4 are resolved.

## Commands run

- `npm --prefix web run check` at bdb9141 -> pass. The chain lint -> Vitest -> production build ran to the end. Vitest: 2 files, 7 tests passed (547 ms). Build: main 208.52 kB, styles 9.59 kB, polyfills 534 bytes. These are the same numbers as at c5af6ea.
- `git diff --stat c5af6ea bdb9141 -- web` -> empty. The web code is unchanged since the previous full review.
- `git diff --stat d8e43bf..bdb9141` -> only `docs/ARCHITECTURE.md` (+3 lines) and files under `reviews/`.
- `web/angular.json:82-87` -> the `test` target still has `"options": {"isolate": true}`. The new documentation line describes it correctly.

## Acceptance criteria check

None of the findings from the previous full review changed, because `web/` is identical:

1. Libraries: all required packages are present, and the lockfile matches `package.json`. Met, provided the parent records its `npm ci` run.
2. Material 3 theme with light/dark/system via tokens: Met.
3. `@angular/localize` set up with source locale `en`, an extraction target and a committed `messages.xlf`, with tests for both the source rendering and the translated rendering: Met.
4. `npm run check` passes (re-run above), and the ARCHITECTURE.md stack table and Install section are updated. Met.

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
    - Status at bdb9141: unchanged. The parent accepted it with a recorded assumption. The disposition is reasonable.

- [ ] **CR-2 | Files changed outside the declared `touches`, plus formatting-only churn**
    - File: `web/src/main.ts:1`, `web/tsconfig.app.json:6`, `web/tsconfig.spec.json:6`, `web/src/app/app.{ts,html,scss,spec.ts}`, `web/src/app/app.i18n.spec.ts`, `web/src/locale/messages.xlf`
    - Category: Quality
    - Problem: the task changes files that are not in its `touches` list. Some of the edits are Prettier-only reformatting.
    - Why it matters: more merge-conflict risk for parallel tasks.
    - Suggested fix: keep the recorded assumption, and avoid formatting-only edits in later tasks.
    - Status at bdb9141: unchanged. The parent accepted it with recorded assumptions.

- [x] **CR-3 | The i18n spec relies on per-file isolation, which the test builder did not turn on**
    - File: `web/angular.json:85`
    - Category: Tests
    - Status at bdb9141: resolved. 4ca96bd added `"isolate": true`, and it is still in place.

- [x] **CR-4 | Project-wide `isolate: true` for web unit tests is not documented**
    - File: `docs/ARCHITECTURE.md:148-150`
    - Category: Quality
    - Status at bdb9141: resolved. The "Testing" rules now say:
        - that web unit tests run with Vitest `isolate: true` (`web/angular.json` `test` target);
        - that each spec file gets a fresh module graph;
        - that `app.i18n.spec.ts` depends on this, because Angular caches i18n template constants on first render;
        - that the setting must not be removed for speed.

      The edit is small and limited to this rule, as the shared-doc convention requires. It matches the code.

## Review conclusion

No material issues found.
