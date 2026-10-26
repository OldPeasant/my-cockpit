# Test Report

Date: 2026-10-26

Task: i18n-german - German UI

Scope: task commits 6eaaad753 and 4b3d0156e on base a4e1103b549dcc281e9d5c3f7491c1399aa64fcb (HEAD 4b3d0156eec62216d101a687b07992a4cb460928), as supplied by the parent worker; `git diff --stat a4e1103b5..HEAD` matches the described change set (web/angular.json, web/package.json, web/src/locale/messages.xlf, web/src/locale/messages.de.xlf, 20 files in android/app/src/main/res/values-de/, docs/ARCHITECTURE.md). Uncommitted at measurement time: only the new spec below and the reviews/i18n-german/ folder.

Requirements:
- tasks/i18n-german.md acceptance criterion: "Web builds a `de` locale (all messages translated, no missing-translation warnings); Android values-de complete (lint `MissingTranslation` clean)", plus its recorded assumptions (informal "du", English-only default build).
- SPEC.md line 197: quick-capture syntax `#project`, `@context`, `!today`/`!tomorrow`/`!2026-11-03`, `^waiting`.
- docs/ARCHITECTURE.md "Testing" rules and "Coverage"; conventions of web/src/app/app.i18n.spec.ts and web/src/app/shell/app-icons.spec.ts (Vitest specs that read repository files through `node:fs`).

## Test files changed

- `web/src/locale/messages-de.spec.ts` (new, untracked; git blob d60b15577f592eef63a35b851ed3a83c4173d947)

## Tests added or updated

Count: 7

The spec parses `messages.xlf` and `messages.de.xlf` (jsdom `DOMParser`, no new dependency). It closes the gap noted by the parent: Angular's XLIFF 1.2 loader silently falls back to the English `<source>` when a unit has no `<target>`, so `npm run build:de` does not catch that case. It runs in about 0.3 s inside `npm run test:ci`, so `.taskcheck` stays well under 2 minutes.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| de file is a valid XLIFF 1.2 with target-language de | `declares German as the target language of an XLIFF 1.2 file` | `cd web && npx ng test --watch=false --include src/locale/messages-de.spec.ts` | Pass |
| All messages translated: every extracted unit has a de unit, no stale units | `has a unit for every extracted message and none for messages that no longer exist` | same | Pass (1708/1708) |
| All messages translated: the de source equals the current English text (not translated from an older text) | `translates the current English text of every message` | same | Pass |
| All messages translated: no unit without a target or with an empty target (the silent fallback the build does not catch) | `gives every message a non-empty <target>` | same | Pass |
| Translations keep every `<x>` placeholder (interpolations, tags, ICU) | `keeps every placeholder of the English message in the translation` | same | Pass |
| ICU plural/select messages keep their cases | `keeps the plural and select cases of ICU messages` | same | Pass |
| German hints do not teach syntax the parser rejects (SPEC.md line 197) | `keeps the quick-capture due keywords the parser accepts (SPEC.md: !today, !tomorrow)` | same | **Fail** (TW-1) |
| Web `de` build with no missing-translation diagnostics | none added (the parent ran `npm run build:de` plus a negative check) | not re-run by the test-writer | Not run: the parent's evidence covers it; `.taskcheck` does not build `de` |
| Android values-de complete (lint `MissingTranslation`) | existing Android Lint (`warningsAsErrors`) | `sh scripts/check.sh android` | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/locale/messages-de.spec.ts`

Result:

Fail: 6 passed, 1 failed (TW-1). Lint: `npx ng lint --max-warnings=0 --lint-file-patterns src/locale/messages-de.spec.ts` -> All files pass linting. Formatted with Prettier.

## Full test execution

Command:

`sh scripts/check.sh` (= `.taskcheck`), then `sh scripts/check.sh android`, because the web failure stops the script before Android runs

Result:

Fail. tfp and server passed. web: api:check, lint and e2e typecheck passed. Vitest: 177 files, 3733 tests, 3732 passed, 1 failed (TW-1), 42 s. The web build step did not run because of the failure. android: ktlint + testDebugUnitTest + lintDebug passed ("all checks passed (android)").

## .taskcheck

Result:

Fail. The only failure is the new TW-1 test. It stays red until the translation is fixed.

## Bugs found

### German quick-capture hint tells the user to type `!heute` / `!morgen`, which the parser ignores

Finding ID:

`TW-1`

Test:

`web/src/locale/messages-de.spec.ts` > `German translations (messages.de.xlf)` > `keeps the quick-capture due keywords the parser accepts (SPEC.md: !today, !tomorrow)`

Production source:

`web/src/locale/messages.de.xlf:2047` (trans-unit `capture.syntaxHint`). Parser: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/CaptureParser.kt:194-196` accepts only `today` / `tomorrow`.

Requirement:

Task criterion "all messages translated", read together with SPEC.md line 197, which fixes the capture syntax as `!today`/`!tomorrow`/`!2026-11-03`. A translation must not change what the user is told to type.

Expected behavior:

The German hint keeps the literal keywords `!today` and `!tomorrow`, e.g. "... <code>!today</code>, <code>!tomorrow</code> oder <code>!2026-11-03</code> ...". Translating `#project`, `@context` and `^name` as `#Projekt`, `@Kontext` and `^Name` is fine, because those are placeholders for user-chosen names.

Actual behavior:

The target shows `!heute` and `!morgen`. A German user who follows the hint gets no due date: the parser does not recognise the token, and it stays as plain text in the title.

Severity:

Medium

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: not applicable. The task changed no executable source code.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no executable changed lines).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: i18n-german; base a4e1103b549dcc281e9d5c3f7491c1399aa64fcb; HEAD 4b3d0156eec62216d101a687b07992a4cb460928; commits 6eaaad753, 4b3d0156e, supplied by the parent and checked with `git diff --stat a4e1103b5..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 4b3d0156e plus untracked `web/src/locale/messages-de.spec.ts` (blob d60b15577f592eef63a35b851ed3a83c4173d947).
- Exact included files; renamed/deleted/excluded files and reasons: `git diff --name-only a4e1103b5..HEAD -- server/src/main web/src android/app/src/main` gives `web/src/locale/messages.xlf`, `web/src/locale/messages.de.xlf` and 20 `android/app/src/main/res/values-de/*.xml`. All are data/resource files, not code. Vitest v8 instruments TypeScript only, and JaCoCo instruments bytecode only, so neither tool has counters for them. Excluded as non-executable: web/angular.json and web/package.json (build configuration), docs/ARCHITECTURE.md (documentation). No renames or deletions.
- Provider/version, command, working directory, and test selection: no coverage run. `npm run coverage` (Vitest + @vitest/coverage-v8) and `./gradlew createDebugUnitTestCoverageReport` (JaCoCo) would report no counters for any file in scope.
- Run start/completion time and exit status: N/A (no coverage run)
- Generated report paths and report hashes/run identifier: N/A
- Evidence outputs are fresh and inputs stayed unchanged: N/A. No coverage numbers are presented.
- Final run after latest test additions and production fixes: no. No production fix exists yet (TW-1 is open). Rerun the new spec after the fix.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/locale/messages.xlf` | N/A (resource) | N/A | N/A | N/A |
| `web/src/locale/messages.de.xlf` | N/A (resource) | N/A | N/A | N/A |
| `android/app/src/main/res/values-de/*.xml` (20 files) | N/A (resource) | N/A | N/A | N/A |

These resources are checked by content tests instead of coverage: the new web spec (all 1708 units) and Android Lint `MissingTranslation`/`ExtraTranslation`/string-format checks in `lintDebug`.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/locale/messages.de.xlf` (all units) | Silent English fallback for a unit without a target; lost placeholders; ICU cases; stale translations | `messages-de.spec.ts` (6 passing checks) |
| `web/src/locale/messages.de.xlf:2047` | The hint must teach valid capture syntax | TW-1 (failing test) |

## Unable to test

- Whether `messages.xlf` is current with the templates (a new `i18n` text that nobody extracted). Checking this needs `ng extract-i18n`, which is a full Angular build and too slow for `.taskcheck`. No cheap substitute exists. If wanted, follow up with a CI step that runs `npm run i18n:extract` and fails on a diff.
- Whether the German text is good (meaning, informal "du", glossary terms). Only a human review can judge this. Apart from TW-1, I found no contradiction with the parser or the SPEC while inspecting.
- I added no Android unit test. Android Lint (`MissingTranslation`, `ExtraTranslation`, `StringFormatMatches`/`StringFormatCount`, `MissingQuantity`, all `warningsAsErrors`) already enforces completeness and format-argument parity in `.taskcheck`, so a JVM test would duplicate it. A grep found no capture-syntax keywords in the Android resources.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1: German `capture.syntaxHint` translates the parser keywords `!today`/`!tomorrow`).
