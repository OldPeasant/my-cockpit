# Test Report

Date: 2026-10-26

Task: a11y-audit - Accessibility audit

Scope: `git diff b5757334f4b1cb369fcf8ddcd4ec1ac2ce6b3ec2..94756fbf0 -- web/` (task commits 9727243fc, f391266f9, 1cd339190, 204a0ecc3, 1a33988eb, 5694cbce8, 94756fbf0, supplied by the parent; they match `git log b5757334f..HEAD`). This report covers the final code at HEAD 94756fbf0. It replaces the earlier report, which covered the code up to 1cd339190 and did not include the trash label change (1a33988eb) or the markdown editor highlight style (5694cbce8).

Requirements:
- `tasks/a11y-audit.md`: the acceptance criterion and its ASSUMPTION notes (calendar scroller patch; trash 'Delete permanently' aria-label is now 'Delete permanently: <title>' (WCAG 2.5.3 label-in-name); markdown editor uses a HighlightStyle with theme CSS variables instead of `defaultHighlightStyle`, whose literal colors fail AA contrast in dark)
- `reviews/a11y-audit/review.md` CR-1/CR-2 (calendar time-grid patch)
- Parent instructions; `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`

## Test files changed

- `web/src/app/features/notes/editor/markdown-editor.spec.ts` (this pass: 1 test added, additions only, uncommitted)

Already committed in the task and reviewed in this pass (I did not change them):
- `web/src/app/features/calendar/views/calendar-view.spec.ts` (8 tests, 1cd339190)
- `web/src/app/features/notes/editor/markdown-editor.spec.ts` (3 tests, 94756fbf0)
- `web/src/app/features/trash/trash-page.spec.ts` (1 new test plus the label updated in existing tests, 94756fbf0)

## Tests added or updated

Count: 1 added in this pass (`uses no literal color for any token of a richer note`). Across the whole task: 13 new unit tests, plus existing trash tests updated to the new label.

Review of the tests committed in 94756fbf0:
- Trash: the new label is asserted per row (`Restore Your invoice`, `Delete permanently: Your invoice`), and the generic test `starts the accessible name of each row action with its visible text (WCAG 2.5.3)` checks every row button against its rendered text. That test would fail with the old label `Delete <title> permanently`, so it is a real regression test. It is adequate; nothing added.
- Markdown editor: the three tests check URL (`var(--app-accent)`, no literal color), Markdown marks (`var(--app-fg-muted)`), and that heading, link and emphasis stay bold, underlined and italic. The URL test fails with `defaultHighlightStyle` (`#219`). The gap: only three token kinds were checked for literal colors. The new test renders a richer note (h2, blockquote, list, strong, inline code, image with URL, horizontal rule, inline HTML). It checks that strong text is bold, and that no styled token span in the editor gets a `color: #...`/`rgb(...)` rule. This protects the contrast fix against a token kind that a later change might style with a literal color again.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Calendar time-grid scroller patch (role=rowgroup + tabindex=0, parent role=none; also without overflow; idempotent; leaves non-rowgroup parents, existing tabindex, non-vertical scrollers and elements outside `full-calendar` alone) | 7 tests in `calendar-view.spec.ts` `describe('keyboard access to the time grid')` | `npx --prefix web ng test --watch=false --include src/app/features/calendar/views/calendar-view.spec.ts --include src/app/features/notes/editor/markdown-editor.spec.ts --include src/app/features/trash/trash-page.spec.ts --include src/app/features/gtd/inbox/inbox-page.spec.ts` | Pass |
| Calendar today button: accessible name contains its visible text | `names the today button with its visible text` | same | Pass |
| Trash 'Delete permanently' accessible name starts with its visible text (WCAG 2.5.3) | `starts the accessible name of each row action with its visible text (WCAG 2.5.3)`, `labels the actions of each row with its title` (asserts `Delete permanently: Your invoice`), and the existing purge/disable/focus tests that now look the button up by its new label | same | Pass |
| Markdown editor: URLs and marks use theme colors, not literal colors (dark-theme contrast) | `colors URLs with the theme accent instead of a literal color`, `colors Markdown marks with the muted theme color` | same | Pass |
| Markdown editor: no token of a full note gets a literal color; strong is bold | `uses no literal color for any token of a richer note` (new) | same | Pass |
| Markdown editor: headings, links and emphasis can be told apart without color | `keeps headings, links and emphasis distinguishable without color` | same | Pass |
| Inbox page (its SCSS changed; the spec checks the page still works) | existing `inbox-page.spec.ts` | same | Pass |
| axe-core scan of every main page in light and dark (no serious/critical violations), keyboard walkthrough of capture and inbox processing | `web/e2e/a11y/a11y.spec.ts` | `npm --prefix web run e2e` (run by the parent) | Not run by the test-writer: the parent is running the Playwright suite against the podman stack right now, and a second run would collide (parent instruction). The e2e evidence for this criterion comes from the parent's run. |

## Focused test execution

Command:

`npx --prefix web ng test --watch=false --include src/app/features/calendar/views/calendar-view.spec.ts --include src/app/features/notes/editor/markdown-editor.spec.ts --include src/app/features/trash/trash-page.spec.ts --include src/app/features/gtd/inbox/inbox-page.spec.ts`

Result:

Pass: 4 files, 191 tests passed. `npx --prefix web prettier --check web/src/app/features/notes/editor/markdown-editor.spec.ts` passed.

## Full test execution

Command:

`npm --prefix web run coverage` (all web unit tests, with coverage) and `npm --prefix web run lint`

Result:

Pass: 176 test files, 3726 tests passed. `ng lint --max-warnings=0`: all files pass linting. The rest of `npm --prefix web run check` (api:check, e2e typecheck, `ng build`) was not run in this pass, and neither were the server or Android suites.

## .taskcheck

Result:

Not run by the test-writer. `sh scripts/check.sh` also runs the Gradle server and Android checks, which the parent runs before `done`. Only the web unit tests and lint ran here (Pass).

## Bugs found

None.

## Coverage

Status: verified (web part, whole-file and changed-line).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 11/11 executable added lines covered (100%). Method: added lines from `git diff -U0 b5757334f..HEAD -- <file>`, intersected with the `DA:` entries of `web/coverage/web/lcov.info`. Per file: calendar-view.ts 9/9, markdown-editor.ts 1/1 (the `HighlightStyle.define([...])` call is one statement; the `syntaxHighlighting(highlightStyle)` line has no separate DA entry), trash-page.ts 1/1 (the `$localize` return). Changed-branch coverage was not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: a11y-audit; base b5757334f4b1cb369fcf8ddcd4ec1ac2ce6b3ec2; HEAD 94756fbf0. The task commits were supplied by the parent and match `git log b5757334f..HEAD`. The scope is `git diff b5757334f HEAD -- web/`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 94756fbf0 plus the uncommitted `markdown-editor.spec.ts` (sha256 f918ac8c9605a83dee9056fa5c5df9056bc50db6db4377d37d60d46a60a5a0c8). Production files, all unchanged from HEAD: `calendar-view.ts` d75acbbc86c13836bba3290b48150363125cdfad5df5b9430624b2cf74f84aae, `markdown-editor.ts` c0cc011bc138d1f83a82c8dd8e70219652b4ddcf776b0412607282bf48724c50, `trash-page.ts` ff878e3afa518f917dc33f1f30172d48c6aa24a7fe007f2476fa6b240377993d. Specs: `trash-page.spec.ts` faec1057630630e16fb1bb6783968a9424f925f79991fc2a76cc0567b9df7a7b, `calendar-view.spec.ts` ec68ac31ddddce44e247153c16baac360813b8096fe633a078ce2133f496e4ab. All hashes were identical before and after the coverage run.
- Exact included files; renamed/deleted/excluded files and reasons: included `web/src/app/features/calendar/views/calendar-view.ts`, `web/src/app/features/notes/editor/markdown-editor.ts` and `web/src/app/features/trash/trash-page.ts`. Excluded:
  - `calendar-view.scss`, `gtd/inbox/inbox-page.scss` and `notes/editor/markdown-editor.scss`: stylesheets, not executable; the e2e axe scan checks them.
  - `web/package.json` and `web/package-lock.json` (the `@lezer/highlight` dependency): dependency manifests.
  - `web/e2e/a11y/a11y.spec.ts` and `web/playwright.config.ts`: test code and test configuration.
  - `*.spec.ts`: tests.
  - No renames or deletions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8`, via `npm --prefix web run coverage` (`ng test --watch=false --coverage ...`), cwd = repository root, all web unit tests.
- Run start/completion time and exit status: started 2026-10-26 11:23:51 +01:00, reports written 11:25:05 +01:00, exit 0, 176 files / 3726 tests passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 a0f2335d000d1ca6ea38f820bfb638580fd8ca82592265360a71a81dee4add80), `web/coverage/web/coverage-summary.json` (sha256 bf914b1d5893c97e87729fb686a41b7c6ea7cce97c0274ddc15ee3c0d65bb4ae), `web/coverage/web/lcov.info` (sha256 fc0076ec679f1dbaa85fb59c83d49465579cfa98772e1b947ba626a214615449).
- Evidence outputs are fresh and inputs stayed unchanged: yes. The reports' mtimes (11:25:05) are after the run start, and the snapshot hashes match before and after.
- Final run after latest test additions and production fixes: yes. It ran after the one test of this pass was added, on the final task code 94756fbf0.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/calendar/views/calendar-view.ts` | 139/144 (96.52%) | 66/71 (92.95%) | 41/42 (97.61%) | 118/118 (100%) |
| `web/src/app/features/notes/editor/markdown-editor.ts` | 81/83 (97.59%) | 48/50 (96%) | 23/24 (95.83%) | 73/74 (98.64%) |
| `web/src/app/features/trash/trash-page.ts` | 105/110 (95.45%) | 47/58 (81.03%) | 29/29 (100%) | 87/87 (100%) |
| Aggregate (summed counters, 3 files) | 325/337 (96.44%) | 161/179 (89.94%) | 93/95 (97.89%) | 278/279 (99.64%) |

The aggregate covers exactly the three files above. Every scoped executable file was measured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `calendar-view.ts:83` (`viewChild` arrow fn), branches at 67, 218, 242, 263, 276 (`if (!timeZone)` / `if (!settings)` / `if (!context)` guards) | Existing defensive code and Angular query glue | Not task code; no test added |
| `markdown-editor.ts:134` (`context.aborted` in link completion), `:214-215` (`focus()`), branch at 264 (`data?.files ?? []`) | Existing autocomplete/paste code | Not task code (the task changed lines 48-91 and 225, which are covered); no test added |
| `trash-page.ts:84, 88, 94, 100, 102, 104, 109, 168, 189-191` (stale-response `loadSeq` guards, load-more guards, busy guard, index guard) | Existing loading/concurrency guards | Not task code (the task changed only `purgeLabel`, line 158-159, which is covered); no test added |

## Unable to test

- The real FullCalendar 7 time-grid DOM needs browser layout, and jsdom does not parse the `overflow: hidden auto` shorthand. The unit tests use a fake structure that mirrors FullCalendar's `Scroller`. Only the e2e axe scan of the day and week views covers the real DOM.
- Real contrast ratios, both of the HighlightStyle colors and of the SCSS fixes, cannot be computed in jsdom, because the theme variables do not resolve there. The unit tests check only that theme variables are used instead of literal colors. The e2e axe scan in light and dark (run by the parent) provides the contrast evidence.
- The Playwright a11y suite (`web/e2e/a11y/a11y.spec.ts`, which now also seeds a trashed task) was not run by me; see the requirement table. Follow-up: the parent records its own e2e run as the evidence for the acceptance criterion.

## Summary

Tests added and all validation passed. The tests committed in 94756fbf0 for the trash label (label-in-name regression) and the markdown highlight style are adequate. I added one test that checks no token of a full note gets a literal color. No production defects were found. Task-scoped coverage: changed lines 11/11, whole-file lines 278/279. The e2e a11y suite and the full `.taskcheck` are left to the parent.
