# Test Report

Date: 2026-11-08

Task: web-small-gaps - Web: search type filter, read-later capture, Android download link

Scope: base 9c93d3e5934bb1c693330376255cd441e90621fb .. HEAD 01e35aef84cc41f9996fcb9a502659715e86aea4 (branch main). Task-owned commits 024c5158f (search type: filter), d493ad24b (capture single URL to Inbox or Read later), 8b1ce8a67 (Settings Android app version and download link), 371c178e9 (en/de strings); the parent named them by their pre-rebase hashes 4112cac74, 1691c01ca, 99d783940, cdfd6112f. The one later commit 01e35aef8 only adds tasks/web-palette-type-hint.md (no code). Reviewed with `git diff 9c93d3e59..HEAD -- web/`. Uncommitted inputs: only the five spec files listed below (hashes in Coverage).

Requirements:
- tasks/web-small-gaps.md acceptance criteria and its four recorded assumptions
- SPEC.md §4.4 (search), §4.5 (capture), §8.4 (bookmarks / read later), §11 (Android download)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md project notes
- Parent instructions (focused test command, coverage, no production changes)

## Test files changed

- `web/src/app/features/capture/capture.service.spec.ts`
- `web/src/app/features/capture/capture-dialog.spec.ts`
- `web/src/app/features/search/search-query.spec.ts`
- `web/src/app/features/settings/general/android-app-download.spec.ts`
- `web/src/locale/messages-de.spec.ts`

## Tests added or updated

Count: 24 test cases (counting each `it.each` row), in 16 `it`/`it.each` blocks plus one new `isSingleUrl` table row; no existing test changed or weakened.

- capture.service.spec.ts, `describe('readLater')`: posts a read-later bookmark with the description and is not silent; leaves out a missing or empty description (2 rows); sends no client id; a failed bookmark save rejects and is shown to the user.
- capture-dialog.spec.ts, `describe('read later')`: Ctrl+Enter in the notes saves to Read later too; sends the bookmark once while its save is still running (Enter, Enter, click); Shift+Enter clears the saved notes but keeps notes typed while saving. `isSingleUrl` table: new row `https://[::1` (passes the pattern, rejected by the URL parser).
- search-query.spec.ts: a label with a space written with a dash plus a plural with s; does not take an inherited object key for a type (3 rows, fail, see TW-1); accepts the prefix and type name in any case; keeps an empty query empty; `describe('in German')` with fictitious translations equal to messages.de.xlf: matches by UI label (`type:notiz`, `type:Termin`, `type:E-Mail`, `type:journaleintrag`, `type:termin,note`) (5 rows) and still matches ids and the English aliases.
- android-app-download.spec.ts: an unreachable server (status 0) is a failure, not "no app"; a 404 after Try again says no app is available.
- messages-de.spec.ts: this task's 16 message ids plus the reused `settings.retry` exist in English and have a non-empty German target that is not the English text copied over (except `settings.androidApp.version`, "Version" is the same word); `settings.androidApp.version` and `settings.androidApp.details` keep their interpolation placeholders.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Ctrl+K search can filter by type (`type:` prefix) | search-query.spec.ts (all, the parent's and the new ones), search-palette.provider.spec.ts `limits the search to the types of a type: filter...`, `does not search when only a type: filter...` | `npm --prefix web run test:ci -- --include=... (4 globs, see Focused)` | Fail: 3 rows of `does not take an inherited object key` fail (TW-1); everything else passes |
| AC2 Capture of a single URL offers Inbox or Read later (bookmark) | capture-dialog.spec.ts `describe('read later')` (12 tests), `isSingleUrl` table (10 rows); capture.service.spec.ts `describe('readLater')` (5 cases) | same | Pass |
| AC3 Settings shows the Android app version with a download link to /download/android | android-app-download.spec.ts (7 tests), general-settings.spec.ts `shows the Android app row with its version and download link` | same | Pass |
| AC4 New UI strings in web/src/locale (English + German) | messages-de.spec.ts `has the read-later capture and Android app messages in English and German (web-small-gaps)` plus the existing generic checks (no missing/stale/outdated/untranslated unit, placeholders kept) | same | Pass |
| AC5 Unit tests cover the new behavior; `npm run check` and `sh scripts/check.sh` pass | all of the above; full web suite via `npm --prefix web run coverage` | see Full test execution | Fail while TW-1 is open (the full suite has 3 failing tests); `sh scripts/check.sh`: Not run by me (parent's step) |

The German UI language for the palette and for the rendered components was tested through `loadTranslations` (search-query) and through the xlf files (messages-de.spec.ts). A real `build:de` run was not part of my scope.

## Focused test execution

Command (from the repo root; `npm run` sets the working directory to web/, which messages-de.spec.ts needs because it reads `process.cwd()/src/locale`):

`npm --prefix web run test:ci -- --include='src/app/features/search/**/*.spec.ts' --include='src/app/features/capture/**/*.spec.ts' --include='src/app/features/settings/general/**/*.spec.ts' --include='src/locale/*.spec.ts'`

Result:

Fail: 11 files, 205 passed, 3 failed. The 3 failures are the TW-1 regression rows in search-query.spec.ts and are kept on purpose.

Note: the parent's suggested form `npm --prefix web exec -- ng test --watch=false --include=...` runs from the repo root, so messages-de.spec.ts fails there with `ENOENT .../agent-1/src/locale/messages.xlf`. That is a working-directory effect of the command, not a defect; use `npm --prefix web run test:ci -- ...` instead.

Lint and format of the changed specs: `npm --prefix web run lint` -> All files pass linting; `npm --prefix web exec -- prettier --check` on the five specs -> all use Prettier code style.

## Full test execution

Command:

`npm --prefix web run coverage` (the full web Vitest suite with coverage)

Result:

Fail: 193 files, 4516 passed, 3 failed (TW-1), exit 1, 2026-11-08 17:17:30 to 17:18:38 +01:00. This run was before the last added `isSingleUrl` row. No coverage report is written when the run fails, so coverage was measured with the three TW-1 rows deselected (see Coverage): 193 files, 4517 passed, 3 skipped, exit 0.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` also runs the Gradle server and Android suites, and the parent runs it before `done`. It will fail on the web part until TW-1 is fixed.

## Bugs found

### A `type:` word naming an inherited object key is swallowed instead of kept as text

Finding ID:

`TW-1`

Test:

`parseSearchQuery > does not take an inherited object key ("constructor" / "constructors" / "note,constructor") for a type, and keeps an unknown word as text` in `web/src/app/features/search/search-query.spec.ts`

Production source:

`web/src/app/features/search/search-query.ts:20` (`ALIASES` is a plain object literal) and `:79` (`ALIASES[key] ?? ...`)

Requirement:

AC1 together with the recorded assumption "an unknown type: word stays search text", and the doc comment of `parseSearchQuery` ("A type: word that names no known type stays part of the text, so nothing typed is lost").

Expected behavior:

`parseSearchQuery('type:constructor passport')` returns `{ text: 'type:constructor passport', types: [] }`; likewise for `type:constructors` and `type:note,constructor` (one unknown name keeps the whole word as text).

Actual behavior:

`ALIASES['constructor']` returns the inherited `Object.prototype.constructor` function, which counts as a known type: the word is removed from the text, and the function is then dropped by the `SEARCH_TYPES` filter. Results: `type:constructor passport` -> `{ text: 'passport', types: [] }` (searches all types for "passport", the typed word is lost); `type:note,constructor passport` -> `{ text: 'passport', types: ['NOTE'] }`. Any lowercase key of `Object.prototype` after normalization (`constructor`, also with a plural s) behaves this way. A lookup that only reads own keys (`Object.hasOwn`, a `Map`, or a null-prototype object) would fix it.

Severity:

Low (a rare word, but it silently changes what is searched and breaks the stated "nothing typed is lost" rule).

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: verified for the task's TypeScript files, from a fresh run with the three TW-1 rows deselected (they fail by design until the fix). Those three rows only run lines that other tests already cover. The run must be repeated after the TW-1 fix.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 85/85 executable added/modified lines covered (100%). Method: lcov `DA` lines of each scoped file intersected with the `+` lines of `git diff -U0 9c93d3e59..HEAD` for that file. Branch coverage of the changed lines was not measured separately.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-small-gaps; base 9c93d3e5934bb1c693330376255cd441e90621fb; HEAD 01e35aef84cc41f9996fcb9a502659715e86aea4; commits and files from the parent, checked against `git log`/`git diff --stat 9c93d3e59..HEAD` (the only commit after the task commits touches tasks/ only).
- Source/test snapshot identifier (including uncommitted inputs): HEAD 01e35aef8 plus uncommitted specs with sha256 search-query.spec.ts 225991fd7f10e5e9...; capture.service.spec.ts f3e7c2d4afe2f1bb...; capture-dialog.spec.ts c5ed2cbce1a515f1...; android-app-download.spec.ts bd822ad7d148d30a...; messages-de.spec.ts bfcd87223814a9d2... (taken again after the run, unchanged; production files have no uncommitted changes).
- Exact included files: `web/src/app/features/search/search-query.ts`, `web/src/app/features/search/search-palette.provider.ts`, `web/src/app/features/capture/capture-dialog.ts`, `web/src/app/features/capture/capture.service.ts`, `web/src/app/features/settings/general/android-app-download.ts`, `web/src/app/features/settings/general/android-app.testing.ts`, `web/src/app/features/settings/general/general-settings.ts`. Excluded: `capture-dialog.html`, `general-settings.html` (templates, measured only through their components' compiled code), `capture-destination.scss` (styles), `web/src/locale/messages.xlf` and `messages.de.xlf` (data, checked by messages-de.spec.ts), all `*.spec.ts`. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with @vitest/coverage-v8 5.0.5 (Node 24.21.0) through the Angular unit-test builder; `npm --prefix web run coverage -- --filter='^(?!.*inherited object key)'` from the repo root (npm runs it in web/); the full suite (all 193 spec files) without the 3 TW-1 rows.
- Run start/completion time and exit status: 2026-11-08 17:20:37 to 17:21:59 +01:00, exit 0 (193 files passed, 4517 tests passed, 3 skipped).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 44e0f4e8bcffca7c90fcebe90ae10f6f5f2fba43175491d3774ccf764cdf70db; `web/coverage/web/coverage-final.json` sha256 1039cfcecb37d6b68c2fc31fca59eaddf892017cbcb74ab79826bf4c3260e0b5; `web/coverage/web/lcov.info`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest rewrote web/coverage/web at the end of this run (an earlier failed run wrote no report at all); the hashes differ from those of the run before the last test was added; the spec hashes and HEAD are the same before and after.
- Final run after latest test additions and production fixes: yes for the test additions; no production fixes exist yet (TW-1 is open). Rerun after the fix.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/search/search-query.ts` | 30/30 (100.00%) | 18/18 (100.00%) | 8/8 (100.00%) | 26/26 (100.00%) |
| `web/src/app/features/search/search-palette.provider.ts` | 24/24 (100.00%) | 11/11 (100.00%) | 7/7 (100.00%) | 21/21 (100.00%) |
| `web/src/app/features/capture/capture-dialog.ts` | 112/112 (100.00%) | 69/70 (98.57%) | 33/33 (100.00%) | 90/90 (100.00%) |
| `web/src/app/features/capture/capture.service.ts` | 8/8 (100.00%) | 7/7 (100.00%) | 3/3 (100.00%) | 5/5 (100.00%) |
| `web/src/app/features/settings/general/android-app-download.ts` | 52/52 (100.00%) | 23/27 (85.19%) | 6/6 (100.00%) | 36/36 (100.00%) |
| `web/src/app/features/settings/general/android-app.testing.ts` | 2/2 (100.00%) | 1/1 (100.00%) | 1/1 (100.00%) | 2/2 (100.00%) |
| `web/src/app/features/settings/general/general-settings.ts` | 126/127 (99.21%) | 57/63 (90.48%) | 30/30 (100.00%) | 103/103 (100.00%) |
| Aggregate (summed counters, 7 files) | 354/355 (99.72%) | 186/197 (94.42%) | 88/88 (100.00%) | 283/283 (100.00%) |

The aggregate leaves out the excluded templates, scss and xlf files listed above. No file in scope was missing from the report.

Before the last added test, capture-dialog.ts had 111/112 statements and 89/90 lines (line 33, the `catch` of `isSingleUrl`); the new `https://[::1` row covers it.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `capture-dialog.ts:175` binary-expr (`job.result()?.id ?? []`, the right side) | an attachment that finished without a result; code from before this task | Left: not changed by this task |
| `android-app-download.ts:29`, `:49` cond-expr | compiled template control flow (`@switch`/`@if` glue that Angular generates) | Left: every state (loading, none, failed, ready with and without version name) is tested by DOM assertions |
| `android-app-download.ts:108` if | Angular class metadata emitted for dev mode | Left: generated code |
| `android-app-download.ts:120` cond-expr (`: null`) | `app()` when the state is not ready; the template only calls it inside the `ready` case | Left: defensive, not reachable from the template |
| `general-settings.ts:85`, `:171`, `:172`, `:214`, `:224`, `:236` | time zone, label and image-allowlist rows from before this task; the task only adds an import and the Android row | Left: outside this task's changes |

## Unable to test

- The German UI as rendered by a localized build (`npm run build:de` / served `de` locale) was not run; the xlf content is checked by messages-de.spec.ts and label matching by the `loadTranslations` tests. No follow-up needed for this task.
- The real APK download through `/download/android` with a session cookie (an e2e/browser flow) was not run; unit tests check only the link's `href` and `download` attribute. AC3 does not name an e2e run.
- German plural forms (`type:Notizen`, `type:Aufgaben`) and English irregular plurals (`type:journal-entries`) are not matched: only a trailing s is removed, as the recorded assumption says ("plural s ok"). Not a defect against the current assumption; a human may want to revisit it.
- `sh scripts/check.sh` was not run (see .taskcheck).

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion. TW-1 (Low): `parseSearchQuery` treats `type:constructor` as a known type because `ALIASES` is read through the prototype chain. The three regression rows in search-query.spec.ts fail until it is fixed. Everything else passes, and the task's TypeScript files reach 100% line and 94.42% branch coverage (the remaining branches are generated, defensive, or older code).
