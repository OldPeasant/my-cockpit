# Test Report

Date: 2026-10-26

Task: i18n-german - German UI (second test-writer pass, final code)

Scope: task commits f85cb8ba1, 8db7efcdf, 9945e112a, 9e1c7c073 (plus the review-only commit e8a479b4d) on base 811cbc6d04e58215aff5190194ebef767a5c637a. Measured at HEAD e8a479b4d4ffaf1fc68a5c29b17b8878bbc4a6e6. The parent worker supplied this scope, and `git log --oneline 811cbc6d0..HEAD` and `git diff --stat 811cbc6d0..HEAD` confirm it. The working tree was clean before the runs, and this pass made no uncommitted source or test changes.

Requirements:
- tasks/i18n-german.md acceptance criterion: "Web builds a `de` locale (all messages translated, no missing-translation warnings); Android values-de complete (lint `MissingTranslation` clean)", and the recorded assumptions.
- SPEC.md line 197 (quick-capture syntax `!today`/`!tomorrow`). This is the basis of TW-1.
- reviews/i18n-german/review.md (second pass, 0 findings), reviews/i18n-german/fixes.md (CR-1..CR-8, TW-1 fixed), and the first-pass tests.md.
- docs/ARCHITECTURE.md "Testing" / "Coverage", and AGENTS.md.

## Test files changed

- None in this pass. The spec from the first pass, `web/src/locale/messages-de.spec.ts` (blob d60b15577f592eef63a35b851ed3a83c4173d947), was committed in 9945e112a and is unchanged.

## Tests added or updated

Count: 0

No real gap remains. The existing spec covers every check I could automate for the web criterion: unit-set parity, current sources, non-empty targets, placeholders, ICU cases and capture keywords. Android Lint covers the Android criterion. A grep of `android/app/src/main/res/values/*.xml` found no capture-syntax keywords, so Android needs no keyword test.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| de file is XLIFF 1.2 with target-language de | `declares German as the target language of an XLIFF 1.2 file` | `cd web && npx ng test --watch=false --include src/locale/messages-de.spec.ts` | Pass |
| All messages translated: unit-set parity, no stale units | `has a unit for every extracted message and none for messages that no longer exist` | same | Pass (1708/1708 units) |
| Translations match the current English source | `translates the current English text of every message` | same | Pass |
| No unit without a target or with an empty target (the silent fallback) | `gives every message a non-empty <target>` | same | Pass (1708 targets) |
| Placeholders kept | `keeps every placeholder of the English message in the translation` | same | Pass |
| ICU plural/select cases kept | `keeps the plural and select cases of ICU messages` | same | Pass |
| TW-1 regression: capture hint keeps `!today`/`!tomorrow` | `keeps the quick-capture due keywords the parser accepts (SPEC.md: !today, !tomorrow)` | same | Pass (was Fail in the first pass) |
| Web builds a `de` locale with no missing-translation diagnostics | build with `i18nMissingTranslation: "error"` | `cd web && npm run build:de`, then the same build piped to `grep -icE "translation\|i18n\|locali"` | Pass: the build completed and wrote output to `dist/web/browser/de`, whose `index.html` has `<html lang="de">`. The grep counted 0 translation, i18n or localize diagnostics. The only warning is the initial bundle budget warning (679 kB > 500 kB), which the default English build also shows. |
| Android values-de complete (lint `MissingTranslation` clean) | Android Lint (`warningsAsErrors = true`, `abortOnError = true`) | `cd android && ./gradlew lintDebug --rerun-tasks` | Pass: BUILD SUCCESSFUL in 1m 16s. The fresh `app/build/reports/lint-results-debug.xml` (13:09:15) has 0 MissingTranslation issues, and its only issues are 2 `AutoboxingStateCreation` hints. `values/` and `values-de/` each have 564 `<string>`/`<plurals>` entries. |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/locale/messages-de.spec.ts`

Result:

Pass: 1 file, 7 tests passed (13:07:20, 756 ms).

## Full test execution

Command:

`sh scripts/check.sh`

Result:

Pass: "== all checks passed", exit 0. This covers tfp, server, web (api check, lint, Vitest, build) and android (ktlint, unit tests, lintDebug). I captured only the tail of the output, so I don't have per-suite test counts for this run.

Wall time: 13:09:26 to 13:21:12, about 12 minutes. This is far above the < 2 min target, but it is not caused by this task. Other agents' runs were loading the machine, and my `--rerun-tasks` lint run just before had made the Gradle outputs stale. The parent should keep the 15-minute `done` timeout in mind.

## .taskcheck

Result:

Pass (same run as above).

## Bugs found

None in this pass.

TW-1 from the first pass (the German `capture.syntaxHint` showed `!heute`/`!morgen`) is fixed in 9e1c7c073. Its regression test now passes.

## Coverage

Status: not applicable. The task changed no executable production code. Line, branch and function coverage do not apply to translation resources, so this report measures them by unit and string completeness instead.

Metric: whole-file coverage of task-changed production files (N/A, see above). Translation completeness is measured instead.
Changed-line coverage: not measured, because the task has no executable changed lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: i18n-german. Base 811cbc6d04e58215aff5190194ebef767a5c637a, HEAD e8a479b4d4ffaf1fc68a5c29b17b8878bbc4a6e6, task commits f85cb8ba1, 8db7efcdf, 9945e112a, 9e1c7c073 (the parent supplied them and `git log` confirms them).
- Source/test snapshot identifier (including uncommitted inputs): HEAD e8a479b4d with a clean tree. Blobs: `messages.de.xlf` e8153a5ebba65faa08d19f5860a971df0fa11dd1, `messages.xlf` aaf927c8fcd4e0c6b41e2f821c754b45e490b371, `messages-de.spec.ts` d60b15577f592eef63a35b851ed3a83c4173d947.
- Exact included files; renamed/deleted/excluded files and reasons:
  - Included: `web/src/locale/messages.xlf`, `web/src/locale/messages.de.xlf`, and 20 files `android/app/src/main/res/values-de/*.xml`. All are resource or data files. Vitest v8 instruments only TypeScript and JaCoCo only bytecode, so neither tool has counters for them.
  - Excluded as non-executable: `web/angular.json` and `web/package.json` (build configuration), `docs/ARCHITECTURE.md`, and `reviews/`.
  - The test file `messages-de.spec.ts` is not in the scope.
  - No renames or deletions.
- Provider/version, command, working directory, and test selection: I made no line-coverage run, because no executable file is in scope. Completeness was measured with Vitest v5.0.5 (`messages-de.spec.ts`), `grep -c` unit and target counts, Angular `ng build --configuration production,de`, and Android Lint 9.4.1 (`lintDebug --rerun-tasks`).
- Run start/completion time and exit status:
  - Spec: 13:07:20, pass.
  - build:de: about 13:07:31, completed.
  - lintDebug: completed 13:09:15, BUILD SUCCESSFUL.
  - check.sh: 13:09:26 to 13:21:12, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/lint-results-debug.xml` (regenerated 13:09:15 by the `--rerun-tasks` run) and `web/dist/web/browser/de/` (Git-ignored).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The tree was clean before and after the runs. The lint report was regenerated rather than taken from an up-to-date cache (the earlier report was from 12:55).
- Final run after latest test additions and production fixes: yes. The runs used the final code after fix commit 9e1c7c073.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/locale/messages.xlf` | N/A (resource) | N/A | N/A | N/A |
| `web/src/locale/messages.de.xlf` | N/A (resource) | N/A | N/A | N/A |
| `android/app/src/main/res/values-de/*.xml` (20 files) | N/A (resource) | N/A | N/A | N/A |

Translation completeness instead of line coverage:
- Web: 1708/1708 extracted units have a German unit, and 1708/1708 have a non-empty `<target>`. There are 0 stale units, 0 outdated sources, 0 placeholder mismatches and 0 ICU-case mismatches.
- Android: 564/564 `values/` entries exist in `values-de/`, with 0 MissingTranslation issues.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `web/src/locale/messages.de.xlf` (all units) | Silent English fallback, lost placeholders, ICU cases, outdated sources | Covered by `messages-de.spec.ts` (7 passing tests) |
| `web/src/locale/messages.de.xlf` `capture.syntaxHint` | The hint must teach valid capture syntax | Covered by the TW-1 regression test (now passing) |

## Unable to test

- Whether `messages.xlf` is current with the templates (an `i18n` text nobody extracted). This needs `ng extract-i18n`, which is too slow for `.taskcheck`. A possible follow-up is a CI step that runs `npm run i18n:extract` and fails on a diff. This is unchanged since the first pass.
- Whether the German text is good (meaning, informal "du", glossary). Only a human can judge this. The second code review checked the terminology and found no issues.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. TW-1 is fixed and its regression test passes. The `de` build has no translation diagnostics, Android lint has no MissingTranslation issues, and `.taskcheck` passes.
