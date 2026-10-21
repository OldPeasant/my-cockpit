# Test Report

Date: 2026-10-22

Task: app-logo - Show the brand mark in the web top bar, web login/setup and Android login

Scope: `git diff 58b1f5f8..56903887` (base 58b1f5f8fc4d82bd86d8f5c751ca612bf1dc72d1 from the parent / `git merge-base`). The task code commit is 56903887126d28a5f6bf73b98fa90f1e7c742c3f (it was ab9280d6475b0c2d692fdd4a8077b034770d666a when the run started. The parent re-committed it on top of `verify: app-logo #5` while the run was in progress, and `git diff ab9280d6 56903887` touches only `tasks/app-logo.md`, so the production code is identical). Plus the uncommitted test files listed below.

Requirements:
- tasks/app-logo.md acceptance criteria and recorded assumptions
- reviews/app-logo/review.md (CR-1: placement specs for the shell and the auth layout)
- docs/ARCHITECTURE.md "Coverage", AGENTS.md project notes

## Test files changed

- `web/src/app/shell/shell.spec.ts` (modified: 3 tests added)
- `web/src/app/features/auth/auth-layout.spec.ts` (new: 2 tests)
- `android/app/src/test/java/ch/sonensei/cockpit/auth/LoginScreenTest.kt` (modified: 1 test added)
- `android/app/src/test/java/ch/sonensei/cockpit/auth/LogoDrawableTest.kt` (new: 3 tests)

An earlier test-writer run was interrupted and left these files uncommitted. I reviewed them and kept them. I extended `shows the brand mark in the top bar only in the compact layout` so it also checks that widening the window again removes the top-bar mark and leaves exactly one logo (the one in the brand link). `logo.spec.ts` (3 tests) is part of the task commit and was not changed.

## Tests added or updated

Count: 9 (web 5, Android 4)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `LogoComponent` inline SVG, cut-out needles, no extra request, size input, aria-hidden, sky `#E8962E` | `logo.spec.ts`: `renders the mark inline: dials with cut-out needles, sky and ground`, `is decorative`, `is 28 px by default and follows the size input` (task commit) | `cd web && npx ng test --watch=false --include src/app/shell/shell.spec.ts --include src/app/shell/logo.spec.ts --include src/app/features/auth/auth-layout.spec.ts` | Pass |
| AC1: ink follows light/dark theme (`light-dark()` driven by `color-scheme`) | none: jsdom computes no `light-dark()`/`color-scheme` | - | Not testable in unit tests: the parent's screenshot evidence (AC5) covers it |
| AC1: brand link keeps its accessible name | `shell.spec.ts`: `shows the decorative brand mark before the text of the brand link` (text "My Cockpit", no aria-label, logo aria-hidden, href `/`) | same focused command | Pass |
| AC2: top-bar logo (28 px) before the "My Cockpit" text, also in the collapsed drawer < 900 px (CR-1) | `shell.spec.ts`: `shows the decorative brand mark before the text of the brand link`, `shows the brand mark in the top bar only in the compact layout`, `closes the drawer when the brand link (or its mark) is clicked in the compact layout` | same focused command | Pass |
| AC2: auth layout shows the logo (64 px) above the login/setup forms (CR-1) | `auth-layout.spec.ts`: `shows the 64 px brand mark above the app name and the projected form`, `keeps the mark decorative so only the text names the app` | same focused command | Pass |
| AC3: `ic_logo.xml` vector, light/dark via `drawable-night`, transparent background | `LogoDrawableTest`: `the logo inflates as a 64 dp square`, `in light mode the mark uses the dark ink and the orange sky` (`notnight`), `in dark mode the mark uses the light ink and keeps the orange sky` (`night`) | `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.LoginScreenTest' --tests 'ch.sonensei.cockpit.auth.LogoDrawableTest'` | Pass (3/3) |
| AC3: logo on `LoginScreen` with `contentDescription = null` | `LoginScreenTest`: `the brand mark is decorative and the title alone names the screen` | same Android command | Pass (LoginScreenTest 13/13) |
| AC3: 72 dp size, above the title | none: an Image without contentDescription has no semantics node to measure | - | Not testable through semantics: the code sets `Modifier.size(72.dp)` before the title (lines 99-103), and the parent's screenshot covers it (AC5) |
| AC4: shell/auth-layout/logo specs, `npm run check`, Android `ktlintCheck testDebugUnitTest lintDebug` pass | whole suites | see below | Pass |
| AC5: screenshots | - | - | Not run by test-writer: this is the parent's manual evidence |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/shell/shell.spec.ts --include src/app/shell/logo.spec.ts --include src/app/features/auth/auth-layout.spec.ts`
`cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.auth.LoginScreenTest' --tests 'ch.sonensei.cockpit.auth.LogoDrawableTest'`

Result:

Pass. Web: 3 files, 30 tests passed. Android: LoginScreenTest 13 tests and LogoDrawableTest 3 tests, 0 failures, 0 errors, 0 skipped.

## Full test execution

Command:

`cd web && npm run check` (api:check && lint && e2e:typecheck && test:ci && ng build)
`cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug`
`cd web && npm run coverage` (full Vitest suite: 2676 tests passed)

Result:

Pass. Web: the `&&` chain reached `ng build`, which finished with "Application bundle generation complete". The build printed one warning: the initial bundle (664.75 kB) is over its 500 kB budget. It is only a warning. This task adds the logo only to the lazy `shell` and auth chunks, so it is unlikely to come from this task, but I did not compare against the base. Android: BUILD SUCCESSFUL.

## .taskcheck

Result:

Not run by test-writer. Its parts (web `npm run check`, Android `ktlintCheck testDebugUnitTest lintDebug`) ran above and passed. The server was not changed by this task. The parent runs `.taskcheck` before `done`.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by hand for Android `LoginScreen.kt`. Changed lines 99-102 (Image) and 104-107 (Text) all have `ci>0, mi=0` in the JaCoCo XML. For web, the changed files `logo.ts` and `shell.html` have 100% line coverage, and `auth-layout.ts` has 100% line coverage, so every changed line is covered. I did not compute a separate changed-branch metric.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: app-logo; base 58b1f5f8fc4d82bd86d8f5c751ca612bf1dc72d1; HEAD 56903887126d28a5f6bf73b98fa90f1e7c742c3f (same production content as ab9280d6, the task commit the parent named). The file list comes from `git diff --name-only 58b1f5f8..HEAD` and matches the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): sha256 `shell.spec.ts` 862946815097e8c5..., `auth-layout.spec.ts` 96246e3a7dacff49..., `LoginScreenTest.kt` 069891c05675ae15..., `LogoDrawableTest.kt` 954b27a4d1d2b4e0... No uncommitted production changes.
- Exact included files: `web/src/app/shell/logo.ts`, `web/src/app/shell/shell.ts`, `web/src/app/shell/shell.html` (the provider reports it as its own file), `web/src/app/features/auth/auth-layout.ts`, `android/app/src/main/java/ch/sonensei/cockpit/auth/LoginScreen.kt`. Excluded, with reasons: `shell.scss` (styles, nothing to execute); `res/drawable/ic_logo.xml` and `res/drawable-night/ic_logo.xml` (resources, not instrumented; `LogoDrawableTest` covers them by behaviour); `logo.spec.ts` (test); `tasks/app-logo.md` (task metadata). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: web is Vitest 5.0.5 with `@vitest/coverage-v8` 5.0.5, `npm run coverage` in `web/`, full suite. Android is JaCoCo through AGP `enableUnitTestCoverage`, `./gradlew createDebugUnitTestCoverageReport` in `android/`, full debug unit tests.
- Run start/completion time and exit status: web 2026-10-21T22:53:46Z to 22:54:35Z, 2676 tests passed, summary printed. Android 22:54:48Z to 22:54:55Z, BUILD SUCCESSFUL. The test task was up-to-date from the full `testDebugUnitTest` run at about 22:51Z on the same inputs, and the report task executed again.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (mtime 00:54:35 +0200, sha256 689823e85de65d0e38a6313e802460e0e9e1b0eb0370bde759ff748733036d06), `web/coverage/web/coverage-summary.json`; `android/app/build/reports/coverage/test/debug/report.xml` (mtime 00:54:55 +0200, sha256 a8c2f91bddd298286feb4fc6ac044c76b49dc0463cd595001528e7d2b310e0e6).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The report mtimes fall inside the run windows. The test files did not change after the last edit, which came before all the full runs. The only HEAD change was to `tasks/`.
- Final run after latest test additions and production fixes: yes (no production fixes were made).

Values below use covered/total (percentage). Android "Statements" is the JaCoCo INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/shell/logo.ts` | 10/10 (100%) | 10/10 (100%) | 1/1 (100%) | 5/5 (100%) |
| `web/src/app/shell/shell.ts` | 47/47 (100%) | 22/22 (100%) | 14/14 (100%) | 38/38 (100%) |
| `web/src/app/shell/shell.html` | 94/94 (100%) | 6/6 (100%) | 7/7 (100%) | 66/66 (100%) |
| `web/src/app/features/auth/auth-layout.ts` | 13/13 (100%) | 8/9 (88.9%) | 1/1 (100%) | 6/6 (100%) |
| `android/.../auth/LoginScreen.kt` | 1551/1698 instr. (91.3%) | 56/73 (76.7%) | 20/28 (71.4%) | 137/150 (91.3%) |

Web aggregate (summed counters, the 4 web files): statements 164/164 (100%), branches 46/47 (97.9%), functions 23/23 (100%), lines 115/115 (100%). I did not sum web and Android together because the providers count different things. Excluded: `shell.scss` and the two drawable XMLs (reasons above).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/app/features/auth/auth-layout.ts`: 1 branch reported at "line 49" (the file is shorter: this is the compiled template's update block, mapped back) | none: an Angular-generated template branch | Not a material gap. All lines of the template and the logo binding are covered. |
| `android/.../LoginScreen.kt:240-256` (`LoginScreenPreview`) | none: the `@Preview` composable is never called by tests | Not a material gap. It existed before this task. |
| `android/.../LoginScreen.kt:58-67, 82` partial branches | Compose default-parameter and `$changed` branches in the existing signature | Not a material gap. Not changed by this task. |

## Unable to test

- Theme-dependent ink on web (`light-dark()` with `color-scheme` from `prefers-color-scheme` / `data-theme`): jsdom does not compute it. The screenshots for AC5 (top bar in light and dark) must cover it. Parent follow-up: record that evidence.
- Android logo size (72 dp) and its position above the title: a decorative Image has no semantics node. Covered by code inspection and the AC5 screenshot or preview.
- AC5 screenshots: manual evidence for the parent.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.
