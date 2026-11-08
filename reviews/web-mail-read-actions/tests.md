# Test Report

Date: 2026-11-08

Task: web-mail-read-actions - Web mail read view: unread, flag, move, shortcuts r/a/f/c

Scope: final revision bfe1687b44e74a53c130203e0b5ec2f080cd2bb7 on main, base 8e7ae960e4811726fafaaa1970a6e86b9b4f0a1a (`git diff 8e7ae960e..bfe1687b4 -- web`), as named by the parent worker. The task commits are a62f12729 (implementation), 5c306bd13 (tests, first test report and review) and bfe1687b4 (fixer: German star wording, a constant "Star" label with a hidden ☆/★ glyph and `aria-pressed`, and the new shared pure function `moveTargetsOf(nodes, exceptId)` in `mail-folders-store.ts`, used by `MailFoldersStore.moveTargets` and `MailReadPage.move()`). This is the second test-writer pass, on the final code. The working tree was clean when I measured: every test is committed, and this pass added no new test files. The only uncommitted file besides this report is `reviews/web-mail-read-actions/review.md` (the code-reviewer's, which I didn't touch).

Requirements:
- `tasks/web-mail-read-actions.md` acceptance criteria and its recorded assumptions (PageShortcuts registry in the `?` overview; r/a/f only in the read view, c in list and read view; Move loads folders on demand and goes on like Archive, Star stays, Mark unread waits for the pending mark-read)
- SPEC.md §7.3 (mail shortcuts), as quoted by the task
- docs/ARCHITECTURE.md "Coverage" and i18n conventions; AGENTS.md
- `reviews/web-mail-read-actions/fixes.md` (what changed in bfe1687b4)

## Test files changed

Across the task (all committed):
- `web/src/app/features/mail/read/mail-read-page.spec.ts`
- `web/src/app/features/mail/list/mail-list-page.spec.ts`
- `web/src/app/features/mail/list/mail-list-session.spec.ts`
- `web/src/app/features/mail/list/mail-shortcuts.spec.ts`
- `web/src/app/features/mail/list/mail-folders-store.spec.ts` (fixer: `moveTargetsOf`)
- `web/src/app/shell/shell.spec.ts`
- `web/src/app/shell/page-shortcuts.spec.ts` (new: PageShortcuts and ShortcutsDialog)

This pass: none. The fixer's commit already brings the tests its changes need, and I found no gap worth another test (see below).

## Tests added or updated

Count: 68 added and 5 updated across the task. This pass added 0.

- First pass (5c306bd13): 66 added, 1 updated. Read page 42, list page 6, list session 6, mail shortcut help 4, PageShortcuts and ShortcutsDialog 7, shell 1.
- Fixer (bfe1687b4): 2 added. `moveTargetsOf › keeps the selectable folders in tree order, without the excluded one` (exclusion, undefined and null exception, empty input, non-selectable parent dropped, tree order kept). Read page `Star › keeps one accessible label, with the state in aria-pressed and the glyph hidden` (screen-reader text stays "Star" in both states, the hidden glyph switches ☆ to ★, `aria-pressed` becomes true). The fixer also updated 4 text assertions from "★ Starred" to "★ Star".
- Second-pass review of the fixer changes: `MailReadPage.move()` now filters through `moveTargetsOf`. It's still covered end-to-end by `Move › offers the account's selectable folders except the current one and moves there`: the fake account has the non-selectable `[Gmail]` folder and the current Inbox, and the test asserts the picker gets exactly Archive, Projects and Projects/Alpha with their depths. `MailFoldersStore.moveTargets` keeps its own existing tests (exclusion, unknown account, no trees). The action-bar order test (`sit next to Delete ...`) and the routed `clickAction(harness, '☆ Star')` test still pass with the new label text.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Read view: mark unread, returns to where it was opened from | `Mark unread › marks the message unread and returns to where it was opened from`, `runs with u`, `waits for marking the message read on opening, so the two cannot cross`, `still marks unread after marking read on opening failed`, `stays on the message with the server reason when it could not be marked unread`, `does not leave a message the user moved to while it ran`, `does not navigate when the page was left while it ran`; routed: `Mark unread › returns to the folder it was opened from, so Back does not open the message again`, `returns to the page it was opened from, with its filter`, `returns to the unified Inbox when opened directly`, `stays on the message when it fails`; list: `Mark unread shows the message unread in the list and returns to it`, `keeps the list as it was when Mark unread or Star fails`; session `markedUnread` (4) | focused `cd web && npx ng test --watch=false --include ...` (8 specs), `cd web && npm run coverage`, `cd web && npm run check` | Pass |
| AC1 Read view: flag/unflag | `Star › stars the message with FLAG and stays on it, showing it starred`, `keeps one accessible label, with the state in aria-pressed and the glyph hidden`, `removes the star of a starred message with UNFLAG, then stars it again with s`, `disables the actions while it runs, so nothing runs twice`, `keeps the star as it was and reports why when starring or unstarring fails`, `does not star the message the user moved to while it ran`; routed `stays on the message after starring it`; list `Star stars the message in the list and stays on it`; session `flagged` (2) | same | Pass |
| AC1 Read view: move to folder | `Move › offers the account's selectable folders except the current one and moves there`, `runs with v`, `does not move, and enables the actions again, when the picker is cancelled`, `keeps the actions busy while the folders load and the picker is open, and opens it once`, `reports folders that cannot be loaded, without opening the picker`, `stays on the message with the server reason when the move fails`, `opens no picker for a message the user left while the folders loaded`, `does not move a message the user left while the picker was open`, `does not open while another dialog is open`; routed `returns to the folder it was opened from after moving the message, like Archive`; list `Move moves on to the next message of the list, like Archive`; `moveTargetsOf › keeps the selectable folders in tree order, without the excluded one`; existing `MailFoldersStore` moveTargets tests | same | Pass |
| AC1 actions absent before load / after load failure; shortcut titles | `are not offered, and u, s and v do nothing, before the message has loaded or when it failed`, `show their shortcuts in the title`, `sit next to Delete in the action bar, with their shortcuts in the title` | same | Pass |
| AC2 `r`/`a`/`f` reply, reply all, forward in the read view | `%s opens the compose page to %s to this message` (r, a, f), `names the keys in the titles of Reply, Reply all and Forward`, `r, a and f do nothing before the message has loaded or when it failed`, `reply to the message shown, not the one shown before`, `ignore r, a, f and c while typing, as chords, repeated or while a dialog is open`; mapping in `mailShortcutFor` | same | Pass |
| AC2 `c` compose everywhere in mail | read: `c opens a new message, also before the message has loaded or when it failed`; list: `opens a new message with c`, `ignores c while typing, with modifiers, repeated or while a dialog is open`, `offers a Compose link to a new message` (title c) | same | Pass |
| AC2 r/a/f do nothing in the list (assumption) | list: `leaves %s alone in the list (a read view key): no action, no navigation` (r, a, f) | same | Pass |
| AC2 listed in the shortcut help | `mail shortcut help` (4 tests); `PageShortcuts` (4 tests); `ShortcutsDialog` (3 tests); read page and list page `lists its keys in the ? overview while it is shown`; shell `shows the shown page's shortcuts in the ? overview under its title` | same | Pass |
| AC3 New UI strings in `web/src/locale` (English + German) | No unit test (i18n is a build concern). Checked at bfe1687b4: `mailRead.unflag` (removed by the fixer) is gone from both files and from the template; `mailRead.*`, `mail.shortcutsTitle`, `mailShortcuts.*` and `shortcuts.or` units match one-to-one between `messages.xlf` and `messages.de.xlf` (86 each, existing `mailRead.*` units included). `npm run build:de` reports no missing translation for any mail or shortcut unit. The new German wording ("Mit Stern markieren", "Der Stern konnte nicht entfernt werden: {reason}") is in the de file | `grep -c -E 'id="(mailRead\.[A-Za-z]+\|mail\.shortcutsTitle\|mailShortcuts\.[A-Za-z]+\|shortcuts\.or)"' web/src/locale/messages*.xlf`; `grep -rn 'mailRead.unflag"' web/src`; `cd web && npm run build:de` | Pass for this task's units. `build:de` itself still fails on the same 16 `calendar.invite.*` units, which already lack German targets at the base commit (another task, see "Unable to test") |
| AC4 Unit tests cover the new behavior; `npm run check` passes | All of the above; full suite 189 files / 4337 tests | `cd web && npm run check` | Pass |
| AC4 `sh scripts/check.sh` passes | none | not run | Not run: it includes the server and Android Gradle builds, which helper sub-agents can't run reliably here. The parent worker runs `.taskcheck` |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/mail/list/mail-folders-store.spec.ts --include src/app/features/mail/read/mail-read-page.spec.ts --include src/app/features/mail/list/mail-list-page.spec.ts --include src/app/features/mail/list/mail-move-dialog.spec.ts --include src/app/shell/shell.spec.ts --include src/app/shell/page-shortcuts.spec.ts --include src/app/features/mail/list/mail-list-session.spec.ts --include src/app/features/mail/list/mail-shortcuts.spec.ts`

Result:

Pass: 8 files, 436 tests (2026-11-08 14:44:45 +01:00, at bfe1687b4).

## Full test execution

Command:

`cd web && npm run coverage` (the full unit suite with coverage), then `cd web && npm run check` (`api:check && lint && e2e:typecheck && test:ci && ng build`)

Result:

Pass. The coverage run passed 189 files and 4337 tests. `npm run check` reached and finished the production build, its last `&&` step. The only warning is the existing initial-bundle budget warning (692.43 kB against 500 kB), which doesn't fail the check.

## .taskcheck

Result:

Not run. `sh scripts/check.sh` also runs the server and Android Gradle suites, so the parent worker runs it. Its web part (`npm run check`) passed, as described above.

## Bugs found

None. Every test passes at bfe1687b4. I found no behavior that contradicts the acceptance criteria, the recorded assumptions or the fixer's stated changes.

## Coverage

Status: verified (fresh run at bfe1687b4, all 4337 tests passed)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: TypeScript 114/114 executable added lines covered (100%). Method: added lines from `git diff -U0 8e7ae960e..HEAD -- {file}` for each file, intersected with the provider's lcov `DA` records (script `.tmp/scoped-cov.mjs`, Git-ignored). Lines without a `DA` record aren't executable and are left out, and removed lines aren't counted. Templates: `mail-list-page.html` 1/1. `mail-read-page.html` shows 9/22, but that number is a measurement artifact (see the template note below), not missing tests. Changed-branch coverage was not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-mail-read-actions; base 8e7ae960e4811726fafaaa1970a6e86b9b4f0a1a; HEAD bfe1687b44e74a53c130203e0b5ec2f080cd2bb7. Task commits a62f12729, 5c306bd13 and bfe1687b4, as named by the parent. Scope is `git diff --name-only 8e7ae960e..HEAD -- web/src`, without specs and xlf files.
- Source/test snapshot identifier (including uncommitted inputs): HEAD bfe1687b4 with a clean working tree for `web/` (no uncommitted source or test changes). Spot hashes, sha256: `mail-read-page.ts` 0bb5a9abd760..., `mail-folders-store.ts` 4e2be6359ba5..., `mail-read-page.spec.ts` 2fe5a2ba0514..., `mail-folders-store.spec.ts` 40eae24b6c75.... After the coverage, check and build:de runs, `git status --short` showed only `reviews/web-mail-read-actions/review.md` modified (by the code-reviewer, not a measured input).
- Exact included files; renamed/deleted/excluded files and reasons: included are the seven TS files and two templates in the tables below. `mail-folders-store.ts` is new in scope since the first pass (changed by bfe1687b4). Excluded: `messages.xlf` and `messages.de.xlf` (translation data, not executable) and all `*.spec.ts` files (tests). No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` (v8), `npm run coverage`, run in `web/`, full unit suite.
- Run start/completion time and exit status: started 2026-11-08 14:45:15 +01:00 and took 50.60 s. All 189 files and 4337 tests passed, and the provider printed its coverage summary. I piped the output through `tail`, so I didn't capture the shell exit code itself; there were no failures and the report was regenerated.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 0b30474b7856b0c13139ee475af3bfd8714e58760d83e15d738e2e4d2bce7431; `coverage-final.json` 3529e3c68b5967b55c1cc1b69ae90124d5c3fd114ad7c5ff93efe187bbdb36cb; `lcov.info` ff1cca8e8ecbb9738975ef92ec578e3da3f051b2eeae643436a8bb974e89b5e1. All three have mtime 1794145565 (2026-11-08 14:46 +01:00), written by this run. They differ from the first pass's hashes.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The run rewrote `coverage/`, its repository totals match this run's stdout (statements 22408/23555, lines 16602/17465), and no source or test input changed during or after the run.
- Final run after latest test additions and production fixes: yes. It ran on bfe1687b4, after the fixer commit.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/read/mail-read-page.ts` | 299/305 (98.03%) | 190/207 (91.79%) | 63/64 (98.44%) | 245/245 (100%) |
| `web/src/app/features/mail/list/mail-shortcuts.ts` | 24/24 (100%) | 27/27 (100%) | 8/8 (100%) | 17/17 (100%) |
| `web/src/app/features/mail/list/mail-list-page.ts` | 248/249 (99.60%) | 167/174 (95.98%) | 63/64 (98.44%) | 207/207 (100%) |
| `web/src/app/features/mail/list/mail-list-session.ts` | 84/85 (98.82%) | 89/90 (98.89%) | 33/33 (100%) | 62/62 (100%) |
| `web/src/app/features/mail/list/mail-folders-store.ts` | 92/92 (100%) | 60/61 (98.36%) | 39/39 (100%) | 75/75 (100%) |
| `web/src/app/shell/page-shortcuts.ts` | 12/12 (100%) | 6/6 (100%) | 5/5 (100%) | 6/6 (100%) |
| `web/src/app/shell/shortcuts-dialog.ts` | 43/44 (97.73%) | 10/11 (90.91%) | 1/1 (100%) | 23/23 (100%) |
| Aggregate, TypeScript (summed counters, 7 files; none missing from the report) | 802/811 (98.89%) | 549/576 (95.31%) | 212/214 (99.07%) | 635/635 (100%) |
| `web/src/app/features/mail/list/mail-list-page.html` | 229/229 (100%) | 36/36 (100%) | 19/19 (100%) | 152/152 (100%) |
| `web/src/app/features/mail/read/mail-read-page.html` (artifact, see note) | 62/273 (22.71%) | 3/47 (6.38%) | 0/18 (0%) | 58/189 (30.69%) |

Template note (corrects the first pass, which said templates had no separate v8 report). The provider does report templates: 59 `.html` files are in `lcov.info`. `mail-list-page.html` measures fully. `mail-read-page.html` measures low because `mail-read-page.spec.ts` calls `TestBed.overrideComponent(MailReadPage, ...)` (lines 248, 2208, 2413, 2485). That makes Angular recompile the template in JIT for those tests, so the AOT template code that v8 maps to the `.html` file never runs there. The DOM tests do verify the counted-as-uncovered bindings: for example, line 127 (the hidden ☆/★ glyph) is asserted by `keeps one accessible label, with the state in aria-pressed and the glyph hidden`, and the action bar's buttons and titles by `sit next to Delete ...` and `show their shortcuts in the title`. So I left the read template out of the aggregate and didn't chase its number with tests. Making it measurable would need a different spec setup, not more tests.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-read-page.ts:251` branch 1 (`shown ? ... : shown` in markUnread) | Defensive: the message can't be null here, because the line before already checked that the shown id equals `id` | Justified gap, not reachable |
| `mail-read-page.ts:84` host-binding function and statement | Compiler-generated `(document:keydown)` wrapper. The handler itself runs in many key tests, so this looks like a source-map artifact | Non-material |
| `mail-read-page.ts:99, 120, 134, 145, 149, 163, 174, 195, 202, 207, 428, 460, 462, 490, 494, 559` (branches and statements; shifted 2 lines up by bfe1687b4) | Code from before this task: route-param fallbacks, image and allowlist guards, GTD and attachment guards, address and content-type fallbacks | Outside the task's changed lines (changed-line coverage 73/73 in this file); no test added |
| `mail-list-page.ts:48, 98, 102, 109, 120, 349, 424, 445` | Code from before this task (route snapshot fallbacks, select guard, scroll helpers) | Outside the task's changed lines |
| `mail-list-session.ts:159` branch 0 | Code from before this task (`fetchMore` guard) | Outside the task's changed lines |
| `mail-folders-store.ts:45` branch 1 (`this.trees() ?? []` in `accountById`) | Code from before this task. The new `moveTargetsOf` and the changed `moveTargets` are fully covered | Outside the task's changed lines (2/2 changed lines covered) |
| `shortcuts-dialog.ts:31` statement, `:66` branch 0 | Compiler-generated template and class code, mapped onto the template outlet line and the class declaration. All rendering paths are tested | Non-material |
| `mail-read-page.html` (13 of 22 changed template lines counted as uncovered, including 105-141) | Read view action bar bindings | Measurement artifact of `overrideComponent` (template note above); behavior asserted by the DOM tests listed there |

## Unable to test

- `sh scripts/check.sh` (AC4): not run here because it includes Gradle suites. The parent worker runs it as `.taskcheck`.
- The German build as a whole: `npm run build:de` still fails on the same 16 `calendar.invite.*` units ("No translation found"). Those units come from the itip invitation dialog, which already exists at the base commit, so the failure isn't caused by this task. The build reports no missing unit for this task's mail or shortcut strings. Follow-up belongs to the task that owns the calendar invitation dialog.
- Real browser behavior (Playwright) of the new buttons and keys: no criterion names e2e tests, and none were run. A grep of `web/e2e` for the old Star labels ("Starred", ☆, ★) only finds `e2e/phase5/demo.spec.ts:153`, `getByLabel('Starred')`. That locator targets the list row's star icon (`mail-list-page.html:243`, `aria-label="Starred"`), which this task didn't change, so no e2e locator depends on the removed "★ Starred" button text.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The fixer's changes (Star label and glyph, German wording, `moveTargetsOf`) are covered by tests at bfe1687b4, every changed executable TypeScript line is covered, and focused tests, the full unit suite with coverage, and `npm run check` all pass.
