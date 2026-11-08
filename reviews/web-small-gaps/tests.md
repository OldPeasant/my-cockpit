# Test Report

Date: 2026-11-08

Task: web-small-gaps - Web: search type filter, read-later capture, Android download link

Scope: final revision. Base 9c93d3e5934bb1c693330376255cd441e90621fb .. HEAD f3e36cde7ab434c35281da9f349228348af12bb8 (branch main), reviewed with `git diff 9c93d3e59..HEAD -- web/ docs/ARCHITECTURE.md`. Task-owned commits (prefix "web-small-gaps:"): bce39e09f (type: filter in Ctrl+K search), 5a5dc8211 (capture a single URL to Inbox or Read later), b4b9d7b43 (Android app version and download link in Settings), 70e14275a (English and German strings), 672a4b918 (test-writer tests and first reports), f3e36cde7 (review fixes: own-key alias lookup for TW-1, dev proxy for /download for CR-1). Commits from other agents merged from main are not part of the scope. No uncommitted source or test inputs (the working tree only shows `reviews/web-small-gaps/review.md` modified by the code reviewer, which is not a test input).

Requirements:
- tasks/web-small-gaps.md acceptance criteria and its recorded assumptions
- SPEC.md §4.4 (search), §4.5 (capture), §8.4 (bookmarks / read later), §11 (Android download)
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md project notes
- reviews/web-small-gaps/fixes.md (TW-1 and CR-1 fixes)
- Parent instructions (second round on the final revision, no production changes)

## Test files changed

First round (committed in 672a4b918); no test file changed in this second round:

- `web/src/app/features/capture/capture.service.spec.ts`
- `web/src/app/features/capture/capture-dialog.spec.ts`
- `web/src/app/features/search/search-query.spec.ts`
- `web/src/app/features/settings/general/android-app-download.spec.ts`
- `web/src/locale/messages-de.spec.ts`

## Tests added or updated

Count: 24 test cases from the first round (counting each `it.each` row), in 16 `it`/`it.each` blocks plus one new `isSingleUrl` table row; 0 added in this round. No existing test changed or weakened. `search-query.spec.ts` has the same sha256 (225991fd7f10e5e9...) as in the first round, so the TW-1 regression rows were not altered by the fix.

- capture.service.spec.ts, `describe('readLater')`: posts a read-later bookmark with the description and is not silent; leaves out a missing or empty description (2 rows); sends no client id; a failed bookmark save rejects and is shown to the user.
- capture-dialog.spec.ts, `describe('read later')`: Ctrl+Enter in the notes saves to Read later too; sends the bookmark once while its save is still running (Enter, Enter, click); Shift+Enter clears the saved notes but keeps notes typed while saving. `isSingleUrl` table: new row `https://[::1` (passes the pattern, rejected by the URL parser).
- search-query.spec.ts: a label with a space written with a dash plus a plural with s; does not take an inherited object key for a type (3 rows, the TW-1 regression, now pass); accepts the prefix and type name in any case; keeps an empty query empty; `describe('in German')` with fictitious translations equal to messages.de.xlf: matches by UI label (`type:notiz`, `type:Termin`, `type:E-Mail`, `type:journaleintrag`, `type:termin,note`) (5 rows) and still matches ids and the English aliases.
- android-app-download.spec.ts: an unreachable server (status 0) is a failure, not "no app"; a 404 after Try again says no app is available.
- messages-de.spec.ts: this task's 16 message ids plus the reused `settings.retry` exist in English and have a non-empty German target that is not the English text copied over (except `settings.androidApp.version`, "Version" is the same word); `settings.androidApp.version` and `settings.androidApp.details` keep their interpolation placeholders.

Why no new tests in this round: the TW-1 fix adds one own-key check in `lookup()`; both sides of the new condition are run (an alias such as `mail` is an own key, `constructor` is not), and `search-query.ts` is at 20/20 branches. `normalize()` removes `_`, so `__proto__` cannot reach the lookup, and `constructor` is the only all-lowercase key of `Object.prototype`; the existing rows cover the class of input. The CR-1 fix is a dev-server proxy config entry with no unit-testable code (see Unable to test).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Ctrl+K search can filter by type (`type:` prefix) | search-query.spec.ts (all, including the 3 TW-1 rows `does not take an inherited object key ...`), search-palette.provider.spec.ts `limits the search to the types of a type: filter...`, `does not search when only a type: filter...` | Focused command below; TW-1 rows also alone with `--include='src/app/features/search/search-query.spec.ts' --filter='inherited object key'` | Pass (TW-1 rows: 3 passed) |
| AC2 Capture of a single URL offers Inbox or Read later (bookmark) | capture-dialog.spec.ts `describe('read later')` (12 tests), `isSingleUrl` table (10 rows); capture.service.spec.ts `describe('readLater')` (5 cases) | Focused command below | Pass |
| AC3 Settings shows the Android app version with a download link to /download/android | android-app-download.spec.ts (7 tests), general-settings.spec.ts `shows the Android app row with its version and download link` | Focused command below | Pass (unit level; the real download through the dev proxy or Caddy was not run, see Unable to test) |
| AC4 New UI strings in web/src/locale (English + German) | messages-de.spec.ts `has the read-later capture and Android app messages in English and German (web-small-gaps)` plus the existing generic checks | Focused command below | Pass |
| AC5 Unit tests cover the new behavior; `npm run check` and `sh scripts/check.sh` pass | all of the above; full web suite via `npm --prefix web run coverage` | `npm --prefix web run coverage` | Pass for the web suite (193 files, 4520 tests). `npm run check`: the parent reports exit 0 on this HEAD (not rerun by me). `sh scripts/check.sh`: Not run by me (parent's step, also runs Gradle suites) |

## Focused test execution

Command (from the repo root; `npm run` sets the working directory to web/, which messages-de.spec.ts needs):

`npm --prefix web run test:ci -- --include='src/app/features/search/**/*.spec.ts' --include='src/app/features/capture/**/*.spec.ts' --include='src/app/features/settings/general/**/*.spec.ts' --include='src/locale/*.spec.ts'`

Result:

Pass: 11 files, 209 tests passed, 0 failed (2026-11-08 17:26:52 +01:00, HEAD f3e36cde7).

TW-1 regression rows alone: `npm --prefix web run test:ci -- --include='src/app/features/search/search-query.spec.ts' --filter='inherited object key'` -> 3 passed, 29 skipped by the filter.

## Full test execution

Command:

`npm --prefix web run coverage` (the full web Vitest suite with coverage, no filter)

Result:

Pass: 193 files, 4520 tests passed, exit 0, 2026-11-08 17:27:13 to 17:29:27 +01:00, HEAD f3e36cde7. The parent separately ran `npm --prefix web run check` on this HEAD -> exit 0 (lint clean, 193 files / 4520 tests, build ok).

## .taskcheck

Result:

Not run: `sh scripts/check.sh` also runs the Gradle server and Android suites, and the parent runs it before `done`.

## Bugs found

### A `type:` word naming an inherited object key is swallowed instead of kept as text

Finding ID:

`TW-1`

Status:

Fixed in f3e36cde7 (reviews/web-small-gaps/fixes.md); the regression tests now pass on the final revision.

Test:

`parseSearchQuery > does not take an inherited object key ("constructor" / "constructors" / "note,constructor") for a type, and keeps an unknown word as text` in `web/src/app/features/search/search-query.spec.ts`

Production source:

`web/src/app/features/search/search-query.ts:79` (`lookup()`, now `(Object.hasOwn(ALIASES, key) ? ALIASES[key] : undefined) ?? ...`)

Requirement:

AC1 together with the recorded assumption "an unknown type: word stays search text", and the doc comment of `parseSearchQuery` ("A type: word that names no known type stays part of the text, so nothing typed is lost").

Expected behavior:

`parseSearchQuery('type:constructor passport')` returns `{ text: 'type:constructor passport', types: [] }`; likewise for `type:constructors` and `type:note,constructor`.

Actual behavior (before the fix, revision 01e35aef8):

`ALIASES['constructor']` returned the inherited `Object.prototype.constructor` function, so the word was removed from the text: `type:constructor passport` -> `{ text: 'passport', types: [] }`; `type:note,constructor passport` -> `{ text: 'passport', types: ['NOTE'] }`. On the final revision the expected results are returned.

Severity:

Low

Fix required:

`No longer — fixed by the fixer in f3e36cde7 and verified by the unchanged regression tests.`

No other production defects were found in this round.

## Coverage

Status: verified on the final revision (HEAD f3e36cde7, full suite, no deselected tests).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 85/85 executable added/modified lines covered (100%). Method: lcov `DA` lines of each scoped file intersected with the `+` lines of `git diff -U0 9c93d3e59..HEAD` for that file. Branch coverage of the changed lines was not measured separately.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-small-gaps; base 9c93d3e5934bb1c693330376255cd441e90621fb; HEAD f3e36cde7ab434c35281da9f349228348af12bb8; scope from the parent (`git diff 9c93d3e59..HEAD -- web/ docs/ARCHITECTURE.md`, commits prefixed "web-small-gaps:"), checked with `git log --oneline 9c93d3e59..HEAD -- web/ docs/ARCHITECTURE.md` (only the six task commits) and `git diff --stat`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD f3e36cde7 with a clean tree for web/ before and after the run (`git status --porcelain` showed only `reviews/web-small-gaps/review.md`, not an input). sha256: search-query.ts 8d46d29deabdd397...; search-query.spec.ts 225991fd7f10e5e9...; proxy.conf.json 4eb48a99a28db8ac....
- Exact included files: `web/src/app/features/search/search-query.ts`, `web/src/app/features/search/search-palette.provider.ts`, `web/src/app/features/capture/capture-dialog.ts`, `web/src/app/features/capture/capture.service.ts`, `web/src/app/features/settings/general/android-app-download.ts`, `web/src/app/features/settings/general/android-app.testing.ts`, `web/src/app/features/settings/general/general-settings.ts`. Excluded: `capture-dialog.html`, `general-settings.html` (templates, measured through their components' compiled code), `capture-destination.scss` (styles), `web/src/locale/messages.xlf` and `messages.de.xlf` (data, checked by messages-de.spec.ts), `web/proxy.conf.json` (dev-server config, not executable app code), `docs/ARCHITECTURE.md` (documentation), all `*.spec.ts`. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with @vitest/coverage-v8 5.0.5 (Node 24.21.0) through the Angular unit-test builder; `npm --prefix web run coverage` from the repo root (npm runs it in web/); the full suite, all 193 spec files, no filter.
- Run start/completion time and exit status: 2026-11-08 17:27:13 to 17:29:27 +01:00, exit 0 (193 files passed, 4520 tests passed).
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 2d3a839b5e8f0932ffd81d7627209b3804422e42af70b25dfbb227fa10b9b029; `web/coverage/web/coverage-final.json` sha256 8b3f7b32b5577d805fc01ed3a2d370ed97f81a381f4325eac41af98c9dbb2ced; `web/coverage/web/lcov.info` sha256 34f5fed8ad05ec434feded8a3a0102df9056c69ea2fc27c001b1ca6f1f0952ee.
- Evidence outputs are fresh and inputs stayed unchanged: yes. All three report files have mtime 2026-11-08 17:29:27 +01:00 (the end of this run), and their hashes differ from the first-round run; HEAD and the tree were the same before and after.
- Final run after latest test additions and production fixes: yes (after the TW-1 and CR-1 fixes in f3e36cde7; no tests were added after it).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/search/search-query.ts` | 30/30 (100.00%) | 20/20 (100.00%) | 8/8 (100.00%) | 26/26 (100.00%) |
| `web/src/app/features/search/search-palette.provider.ts` | 24/24 (100.00%) | 11/11 (100.00%) | 7/7 (100.00%) | 21/21 (100.00%) |
| `web/src/app/features/capture/capture-dialog.ts` | 112/112 (100.00%) | 69/70 (98.57%) | 33/33 (100.00%) | 90/90 (100.00%) |
| `web/src/app/features/capture/capture.service.ts` | 8/8 (100.00%) | 7/7 (100.00%) | 3/3 (100.00%) | 5/5 (100.00%) |
| `web/src/app/features/settings/general/android-app-download.ts` | 52/52 (100.00%) | 23/27 (85.19%) | 6/6 (100.00%) | 36/36 (100.00%) |
| `web/src/app/features/settings/general/android-app.testing.ts` | 2/2 (100.00%) | 1/1 (100.00%) | 1/1 (100.00%) | 2/2 (100.00%) |
| `web/src/app/features/settings/general/general-settings.ts` | 126/127 (99.21%) | 57/63 (90.48%) | 30/30 (100.00%) | 103/103 (100.00%) |
| Aggregate (summed counters, 7 files) | 354/355 (99.72%) | 188/199 (94.47%) | 88/88 (100.00%) | 283/283 (100.00%) |

The aggregate leaves out the excluded templates, scss, xlf, proxy config and docs listed above. No file in scope was missing from the report. Compared with the first round, `search-query.ts` gained two branches (the `Object.hasOwn` condition), both covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `capture-dialog.ts:175` binary-expr (`job.result()?.id ?? []`, the right side) | an attachment that finished without a result; code from before this task | Left: not changed by this task |
| `android-app-download.ts:29`, `:49` cond-expr | compiled template control flow (`@switch`/`@if` glue that Angular generates) | Left: every state (loading, none, failed, ready with and without version name) is tested by DOM assertions |
| `android-app-download.ts:108` if | Angular class metadata emitted for dev mode | Left: generated code |
| `android-app-download.ts:120` cond-expr (`: null`) | `app()` when the state is not ready; the template only calls it inside the `ready` case | Left: defensive, not reachable from the template |
| `general-settings.ts:85`, `:171`, `:172`, `:214`, `:224`, `:236` | time zone, label and image-allowlist rows from before this task; the task only adds an import and the Android row | Left: outside this task's changes |

## Unable to test

- CR-1 (`web/proxy.conf.json` forwards `/download`): the dev proxy under `npm start` against a running server was not exercised; there is no unit-test seam for the Angular dev-server proxy config. The JSON entry mirrors the existing `/api` and `/actuator` entries. Follow-up only if the parent wants a manual `npm start` check.
- The German UI as rendered by a localized build (`npm run build:de` / served `de` locale) was not run; the xlf content is checked by messages-de.spec.ts and label matching by the `loadTranslations` tests. No follow-up needed for this task.
- The real APK download through `/download/android` with a session cookie (an e2e/browser flow) was not run; unit tests check only the link's `href` and `download` attribute. AC3 does not name an e2e run.
- German plural forms (`type:Notizen`, `type:Aufgaben`) and English irregular plurals (`type:journal-entries`) are not matched: only a trailing s is removed, as the recorded assumption says ("plural s ok"). Not a defect against the current assumption; a human may want to revisit it.
- `sh scripts/check.sh` was not run by me (see .taskcheck).

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. TW-1 is fixed in f3e36cde7 and its unchanged regression tests pass. On the final revision the focused suite (209 tests) and the full web suite (4520 tests) pass. The task's TypeScript files have 100% line coverage, 100% changed-line coverage (85/85) and 94.47% branch coverage (188/199). The uncovered branches are generated, defensive, or older code.
