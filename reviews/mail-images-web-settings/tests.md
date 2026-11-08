# Test Report

Date: 2026-11-08

Task: mail-images-web-settings - Web mail images follow the synced image settings

Scope: base `55b1f084e1e81823df801048b6019d5ef465dad6` .. HEAD `fb91dc29e4a1275e717c384023ffa8cf14cc2105` on `main`. Task commits as supplied by the parent: `04d1cc7b2`, `22154836b`, `fecd57e88`, `e36d59980`, `fb91dc29e`. `07bd17d10` (`tasks/web-de-invite-translations.md`) belongs to another task and is excluded. This is the second and final test-writer pass. It covers the final code after the fixer commit `fb91dc29e` (CR-1/TW-1, CR-2, CR-3) plus the uncommitted test additions listed below.

Requirements:
- `tasks/mail-images-web-settings.md` (acceptance criteria and the three recorded assumptions)
- `reviews/mail-images-web-settings/fixes.md` (what the fixes were meant to change)
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage"), `docs/QUALITY.md`
- Parent instructions: do not rerun criterion 4 (Playwright against bootRun); ignore the uncommitted `review.md`

## Test files changed

Uncommitted in this pass:
- `web/src/app/features/mail/read/image-allowlist.spec.ts`
- `web/src/app/features/mail/read/mail-read-page.spec.ts`

Already committed in the task (first pass and fixer): the same two files, `web/src/app/features/settings/general/general-settings.spec.ts`, `web/src/app/features/mail/mail.routes.spec.ts`, and the e2e spec `web/e2e/mail-images/image-settings.spec.ts`.

## Tests added or updated

Count: 4 added in this pass (regression tests for the fixes). No existing test was changed or weakened.

image-allowlist.spec.ts:
- `rejects removing a settings entry without saving, then saves from the loaded list once the settings load` (CR-1: `removeEntry` rejects without a PATCH while the settings cannot be loaded. The next change, after they load, starts from the stored list and does not lose entries.)
- `keeps the old local list when reading it fails and merges it on the next page load` (CR-3: when `localStorage.getItem` throws, the key stays. A new instance, as created on the next page load, merges the list and removes the key.)

mail-read-page.spec.ts (`remote images`):
- `load once, without saving the sender, on "Always load" when the settings cannot be loaded` (CR-1 at page level: images load for this mail, nothing is PATCHed, and no "Stop" is offered)
- `hide "Stop" for a listed sender when the policy changes to "allow" while the mail is open` (CR-2: the "Stop" offer follows a live change of the synced policy)

The fixer's tests for the same findings are in the committed specs and still pass: `does not replace the stored senders with a list built before the settings were loaded`, `rejects a change without saving when the settings cannot be loaded`, `is ready when the browser storage cannot be read` (now also checks that `removeItem` is not called), and `do not offer "Stop" for an allowed sender when the policy is "allow"`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `remoteImages=PROXY` without asking when the policy is `allow` | `load through the proxy at once, without a banner, when the policy is "allow"`, `load only once through the proxy when the policy is "allow" and the sender is allowed too`, `show the error state, without a blocked fallback, ...` | focused `ng test` (below) | Pass |
| AC1: PROXY without asking when the sender (address or domain) is listed | `load at once for a sender whose domain is allowed`, the `senderListed` table, `blocks until the settings are loaded, then follows them` | focused `ng test` | Pass |
| AC1: otherwise the "Load images" banner shows | `stay blocked for other senders`, `stay blocked, and the message still shows, when the settings cannot be loaded`, `do not request a message left while the settings were still loading` | focused `ng test` | Pass |
| AC1 / CR-2: "Stop" offered only when the images load because the sender is listed (policy `block`) | `do not offer "Stop" for an allowed sender when the policy is "allow"`, `hide "Stop" for a listed sender when the policy changes to "allow" while the mail is open`, existing Stop round-trip test | focused `ng test` | Pass |
| AC2: "Always load" adds the sender with `PATCH /api/v1/settings`, not localStorage | `"Always load from this sender" adds the sender to the synced setting ...`, `adds a sender lower case, once, ...`, `runs changes one after the other ...`, `still load once when saving the sender fails` | focused `ng test` | Pass |
| AC2 / CR-1: no save that would replace the stored list while the settings are not loaded | `does not replace the stored senders with a list built before the settings were loaded`, `rejects a change without saving when the settings cannot be loaded`, `rejects removing a settings entry without saving, then saves from the loaded list ...`, `load once, without saving the sender, on "Always load" when the settings cannot be loaded` | focused `ng test` | Pass |
| AC2: old localStorage entries merged once, then the key is removed | merge-once, duplicates, already-allowed, concurrent-ready, invalid-value, retry-after-failed-save tests; read-page migration tests | focused `ng test` | Pass |
| AC2 / CR-3: unreadable storage removes nothing; next page load retries | `is ready when the browser storage cannot be read`, `keeps the old local list when reading it fails and merges it on the next page load` | focused `ng test` | Pass |
| AC3: Settings "Remote images in mail: ask / always load" and sender list with Remove | `remote images in mail` block in general-settings.spec.ts (7 tests) | focused `ng test` | Pass |
| AC4: manual check against bootRun + `npm start` | `web/e2e/mail-images/image-settings.spec.ts` | none by test-writer | Not run: parent instruction (the parent already ran it against bootRun + ng serve; see the task's verify notes) |
| AC5: new UI strings in en + de locale | `src/locale/messages-de.spec.ts` plus `ng build` within `npm run check` | `cd web && npm run check` | Pass (`build:de` not run by test-writer) |
| AC6: unit tests cover the new behavior; `npm run check` and `sh scripts/check.sh` pass | all of the above | `npm run check`; `sh scripts/check.sh` | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/mail/read/image-allowlist.spec.ts' --include='src/app/features/mail/read/mail-read-page.spec.ts' --include='src/app/features/settings/general/general-settings.spec.ts' --include='src/app/features/mail/mail.routes.spec.ts'`

Result:

Pass (4 files, 190 tests). `npx prettier --check` on the two changed specs: clean.

## Full test execution

Command:

`npm --prefix web run check` (api:check, `ng lint --max-warnings=0`, e2e:typecheck, test:ci, `ng build`), 2026-11-08 12:58-12:59 +01:00

Result:

Pass: 187 files, 4185 tests, build complete. The bundle-budget warning was already there before this task.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (2026-11-08 13:01:19-13:02:54 +01:00) printed "all checks passed" for server, web and android.

## Bugs found

None in this pass. TW-1 from the first pass (a sender change made without loaded settings could replace the stored list) was fixed in `fb91dc29e`. Its regression test now passes, and this pass added more tests for it.

## Coverage

Status: verified (fresh run on the final revision plus this pass's uncommitted tests)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 106/106 executable added lines covered (100%). Method: lcov `DA` lines intersected with the added lines of `git diff -U0 55b1f084e..HEAD` for each scoped file. Removed and non-executable lines are not counted. Changed-branch coverage was not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-images-web-settings`; base `55b1f084e1e81823df801048b6019d5ef465dad6`; HEAD `fb91dc29e4a1275e717c384023ffa8cf14cc2105`. The parent supplied the commits `04d1cc7b2 22154836b fecd57e88 e36d59980 fb91dc29e`. The file scope comes from `git diff --name-only 55b1f084e..HEAD -- web/src`, without specs, and matches the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `fb91dc29e` plus uncommitted `image-allowlist.spec.ts` sha256 `27853e475d1d3ad2…cac` and `mail-read-page.spec.ts` sha256 `b84eef4159d4146c…ee97`. Every production/spec file in scope was hashed before the run and checked again after it: unchanged.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/mail/read/image-allowlist.ts`, `web/src/app/features/mail/read/mail-read-page.ts`, `web/src/app/features/settings/general/general-settings.ts`, `web/src/app/features/settings/general/general-settings.html`. Excluded: `general-settings.scss` (styles, no executable behavior); `web/src/locale/messages*.xlf` (translation data, checked by `messages-de.spec.ts` and the build); `web/e2e/**` and `web/playwright.config.ts` (test code). No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 + `@vitest/coverage-v8` 5.0.5; `npm --prefix web run coverage` (`ng test --watch=false --coverage ...`); working directory `web/`; full web unit suite (187 files, 4185 tests).
- Run start/completion time and exit status: 2026-11-08 12:59:41 to 13:00:42 +01:00, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 `4c6d76a382ce0ba3…5c5d`), `coverage-final.json` (`3e51fc3606ddc66e…c22`), `lcov.info` (`195b2ba64c73e850…89b`).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Vitest recreated `web/coverage/` during the run (directory 12:59:51, files 13:00:41-42), and the input hashes matched after the run.
- Final run after latest test additions and production fixes: yes. The run includes `fb91dc29e` and all four tests added in this pass.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/read/image-allowlist.ts` | 75/75 (100%) | 47/47 (100%) | 25/25 (100%) | 54/54 (100%) |
| `web/src/app/features/mail/read/mail-read-page.ts` | 198/204 (97.05%) | 121/136 (88.97%) | 42/43 (97.67%) | 164/164 (100%) |
| `web/src/app/features/settings/general/general-settings.ts` | 126/127 (99.21%) | 57/63 (90.47%) | 30/30 (100%) | 103/103 (100%) |
| `web/src/app/features/settings/general/general-settings.html` | 222/229 (96.94%) | 12/12 (100%) | 9/11 (81.81%) | 133/133 (100%) |
| **Aggregate (summed counters, 4 measured files)** | 621/635 (97.80%) | 237/258 (91.86%) | 106/109 (97.25%) | 454/454 (100%) |

Every scoped file was measured. Excluded files (`general-settings.scss`, the locale `.xlf` files) are not part of the aggregate.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `general-settings.ts:233` (`if (this.removing().has(entry)) return;`, true branch) | Second Remove click while the first removal is saving | Justified gap. The button is disabled while saving; `disables Remove while the removal saves and sends it once` covers that, so the guard cannot be reached through the UI. |
| `general-settings.ts:168,169,211`, `:82` (decorator artifact); `general-settings.html` form submit / time-zone autocomplete listeners (L14, L30) | Time-zone search and pending-save counter, which existed before this task | Outside the task's changes; no test added. |
| `mail-read-page.ts:84,109,120,124,145,178,251,283,285,313,317,381`, keydown host listener (L71) | Fallbacks (`?? ''`, `?? []`), guards and name formatting, all from before this task | Outside the task's changes. All added executable lines are covered (10/10), and the new `senderAllowed` condition has tests for both policies. |
| `image-allowlist.ts` | none | Fully covered. |

## Unable to test

- AC4 (the manual or Playwright check against `./gradlew bootRun` + `npm start`) was not run in this pass, as the parent instructed. The parent recorded a passing run of `web/e2e/mail-images/image-settings.spec.ts` against bootRun + ng serve. No follow-up is needed from the test-writer.
- The test-writer did not run `npm run build:de`. The parent's evidence for AC5 covers it.

## Summary

Tests added and all validation passed. The fixes for CR-1/TW-1, CR-2 and CR-3 have regression tests at service and page level. `npm run check` (4185 tests) and `.taskcheck` pass. Task-scoped coverage is 100% of lines and 100% of added executable lines; the remaining branch gaps are in code from before this task or in a guard the UI cannot reach. No production defects found.
