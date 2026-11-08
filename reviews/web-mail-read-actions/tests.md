# Test Report

Date: 2026-11-08

Task: web-mail-read-actions - Web mail read view: unread, flag, move, shortcuts r/a/f/c

Scope: task commit a62f12729af1ba990af79dc6b774c3814bbe2470 on main, base 8e7ae960e4811726fafaaa1970a6e86b9b4f0a1a (`git diff 8e7ae960e..a62f12729 -- web`), as named by the parent worker. Working tree was clean before testing; the only uncommitted changes are the test files listed below and this report (plus the code-reviewer's `review.md`, not touched).

Requirements:
- `tasks/web-mail-read-actions.md` acceptance criteria and its three recorded assumptions (PageShortcuts registry in the `?` overview; r/a/f only in the read view, c in list and read view; Move loads folders on demand and goes on like Archive, Star stays, Mark unread waits for the pending mark-read)
- SPEC.md §7.3 (mail shortcuts), as quoted by the task
- docs/ARCHITECTURE.md "Coverage" and i18n conventions; AGENTS.md
- Parent worker's change summary

## Test files changed

- `web/src/app/features/mail/read/mail-read-page.spec.ts`
- `web/src/app/features/mail/list/mail-list-page.spec.ts`
- `web/src/app/features/mail/list/mail-list-session.spec.ts`
- `web/src/app/features/mail/list/mail-shortcuts.spec.ts`
- `web/src/app/shell/shell.spec.ts`
- `web/src/app/shell/page-shortcuts.spec.ts` (new: PageShortcuts and ShortcutsDialog)

## Tests added or updated

Count: 66 added, 1 updated (the list page's Compose link test now also checks its `title="c"`).

- read page: 42 (Mark unread 7, Star 5, Move 9, shared 2, r/a/f/c 8, help registration 1, routed back-navigation 6, list-session integration 4). `FakeMailService` now records `target` for MOVE and answers `folders()`; a `fakeMoveDialog` helper replaces `MatDialog.open` and records the dialog data.
- list page: 6; list session: 6; mail shortcut help: 4; PageShortcuts and ShortcutsDialog: 7; shell: 1.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Read view: mark unread, returns to where it was opened from | `Mark unread › marks the message unread and returns to where it was opened from`, `runs with u`, `waits for marking the message read on opening, so the two cannot cross`, `still marks unread after marking read on opening failed`, `stays on the message with the server reason when it could not be marked unread`, `does not leave a message the user moved to while it ran`, `does not navigate when the page was left while it ran`; routed: `Mark unread › returns to the folder it was opened from, so Back does not open the message again`, `returns to the page it was opened from, with its filter`, `returns to the unified Inbox when opened directly`, `stays on the message when it fails`; list: `Mark unread shows the message unread in the list and returns to it`, `keeps the list as it was when Mark unread or Star fails`; session `markedUnread` (4) | `cd web && npx ng test --watch=false --include` (each changed spec), then `npm run coverage` and `npm run check` | Pass |
| AC1 Read view: flag/unflag | `Star › stars the message with FLAG and stays on it, showing it starred`, `removes the star of a starred message with UNFLAG, then stars it again with s`, `disables the actions while it runs, so nothing runs twice`, `keeps the star as it was and reports why when starring or unstarring fails`, `does not star the message the user moved to while it ran`; routed `stays on the message after starring it`; list `Star stars the message in the list and stays on it`; session `flagged` (2) | same | Pass |
| AC1 Read view: move to folder | `Move › offers the account's selectable folders except the current one and moves there`, `runs with v`, `does not move, and enables the actions again, when the picker is cancelled`, `keeps the actions busy while the folders load and the picker is open, and opens it once`, `reports folders that cannot be loaded, without opening the picker`, `stays on the message with the server reason when the move fails`, `opens no picker for a message the user left while the folders loaded`, `does not move a message the user left while the picker was open`, `does not open while another dialog is open`; routed `returns to the folder it was opened from after moving the message, like Archive`; list `Move moves on to the next message of the list, like Archive` | same | Pass |
| AC1 actions absent before load / after load failure; shortcut titles | `are not offered, and u, s and v do nothing, before the message has loaded or when it failed`, `show their shortcuts in the title`, existing `sit next to Delete in the action bar...` | same | Pass |
| AC2 `r`/`a`/`f` reply, reply all, forward in the read view | `%s opens the compose page to %s to this message` (r, a, f), `names the keys in the titles of Reply, Reply all and Forward`, `r, a and f do nothing before the message has loaded or when it failed`, `reply to the message shown, not the one shown before`, `ignore r, a, f and c while typing, as chords, repeated or while a dialog is open`; mapping in `mailShortcutFor` (existing, extended by implementer) | same | Pass |
| AC2 `c` compose everywhere in mail | read: `c opens a new message, also before the message has loaded or when it failed`; list: `opens a new message with c`, `ignores c while typing, with modifiers, repeated or while a dialog is open`, `offers a Compose link to a new message` (title c) | same | Pass |
| AC2 r/a/f do nothing in the list (assumption) | list: `leaves %s alone in the list (a read view key): no action, no navigation` (r, a, f) | same | Pass |
| AC2 listed in the shortcut help | `mail shortcut help` (4 tests: r/a/f/c descriptions, read view key list and order, list help has c and / but not r/a/f, unique non-empty entries); `PageShortcuts` (4 tests: empty, caller's DestroyRef, order and removal, equal sections removed by identity); `ShortcutsDialog` (3 tests: shell only, sections with h3 titles and "or" joining, live updates); read page and list page `lists its keys in the ? overview while it is shown`; shell `shows the shown page's shortcuts in the ? overview under its title` | same | Pass |
| AC3 New UI strings in `web/src/locale` (English + German) | No unit test (i18n is a build concern). Checked: the 34 new or touched units (`mailRead.*` 15, `mail.shortcutsTitle`, `mailShortcuts.*` 17, `shortcuts.or`) appear in both `messages.xlf` and `messages.de.xlf` (grep count 34 each). `npm run build:de` reports no missing translation for any mail or shortcut unit | `grep -c -E 'id="(mailRead...|mailShortcuts...)"' web/src/locale/messages*.xlf`; `cd web && npm run build:de` | Pass for this task's units; `build:de` itself fails on 16 `calendar.invite.*` units that already lack German targets at the base commit (another task, see "Unable to test") |
| AC4 Unit tests cover the new behavior; `npm run check` passes | All of the above; full suite 189 files / 4335 tests | `cd web && npm run check` | Pass |
| AC4 `sh scripts/check.sh` passes | none | not run | Not run: it includes the server and Android Gradle builds, which helper sub-agents can't run reliably here. The parent worker runs `.taskcheck` |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/shell/shell.spec.ts --include src/app/shell/page-shortcuts.spec.ts --include src/app/features/mail/read/mail-read-page.spec.ts --include src/app/features/mail/list/mail-list-page.spec.ts --include src/app/features/mail/list/mail-list-session.spec.ts --include src/app/features/mail/list/mail-shortcuts.spec.ts`

Result:

Pass: 6 files, 396 tests. Three first-run failures came from mistakes in my tests, which I fixed: a selector that also caught the GTD button group, and a TestBed configure call made after the first inject. No production defect was involved.

## Full test execution

Command:

`cd web && npm run coverage` (the full unit suite with coverage), then `cd web && npm run check` (api:check, lint with max-warnings 0, e2e typecheck, all unit tests, ng build)

Result:

Pass. The coverage run passed 189 files and 4335 tests. `npm run check` finished with the production build; the only warning is the existing initial-bundle budget warning (692 kB against the 500 kB budget), which the check does not fail on.

## .taskcheck

Result:

Not run. `sh scripts/check.sh` also runs the server and Android Gradle suites, so the parent worker runs it. The web part of it (`npm run check`) passed, as described above.

## Bugs found

None. Every new test passes against a62f12729, and I found no behavior that contradicts the acceptance criteria or the recorded assumptions.

## Coverage

Status: verified (fresh run, exit 0, all tests passed)

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: 113/113 executable added lines covered (100%). Method: added lines from `git diff -U0 8e7ae960e HEAD` for each TS file, intersected with the provider's lcov `DA` records. Lines without a `DA` record are not executable and are left out. Removed lines are not counted. Changed-branch coverage was not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: web-mail-read-actions; base 8e7ae960e4811726fafaaa1970a6e86b9b4f0a1a; HEAD a62f12729af1ba990af79dc6b774c3814bbe2470, which is the single task commit named by the parent. Scope is `git diff --name-only 8e7ae960e..HEAD -- web/src`, without specs and xlf files.
- Source/test snapshot identifier (including uncommitted inputs): production code = HEAD a62f12729 (no uncommitted production changes). Uncommitted test files, sha256: shell.spec.ts 54cf2447fe37..., page-shortcuts.spec.ts e1f83f9da64f..., mail-read-page.spec.ts fe1156cf5c23..., mail-list-page.spec.ts e60888dee3f8..., mail-list-session.spec.ts dad2e9ed6db1..., mail-shortcuts.spec.ts 6abe19d854d0.... I re-checked all six after the coverage and check runs and they were unchanged.
- Exact included files; renamed/deleted/excluded files and reasons: included are the six TS files in the table below. Excluded: `mail-read-page.html` and `mail-list-page.html` (Angular templates have no separate v8 report; their bindings are exercised by the DOM tests above), `messages.xlf` and `messages.de.xlf` (translation data, not executable), and `mail-shortcuts.spec.ts` and `mail-read-page.spec.ts` (tests). No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` (v8), `npm run coverage`, run in `web/`, full unit suite.
- Run start/completion time and exit status: started 2026-11-08 14:34:17 +01:00, took 51.07 s, exit 0.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` sha256 f708fa74f678452630c327b23a3c993c7b99f501c6f9ad6f998e553c1a9b8579; `coverage-final.json` 2edbeb3c7e3c6eae62cb9b02deb2d3dee27763af643de6090ddb1ee1e7213c31; `lcov.info` 9d1ca252452ad7f9fa1fb91b870a50f87361cfc0cc9ec788dbf3e21541883d65. All three files have mtime 1794144908, written by this run.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The run rewrote `coverage/`, its summary matches the stdout of this run, and the snapshot hashes were verified after the run.
- Final run after latest test additions and production fixes: yes. All test additions came before the run, and there were no production fixes.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/read/mail-read-page.ts` | 300/306 (98.03%) | 192/209 (91.86%) | 64/65 (98.46%) | 246/246 (100%) |
| `web/src/app/features/mail/list/mail-shortcuts.ts` | 24/24 (100%) | 27/27 (100%) | 8/8 (100%) | 17/17 (100%) |
| `web/src/app/features/mail/list/mail-list-page.ts` | 248/249 (99.59%) | 167/174 (95.97%) | 63/64 (98.43%) | 207/207 (100%) |
| `web/src/app/features/mail/list/mail-list-session.ts` | 84/85 (98.82%) | 89/90 (98.88%) | 33/33 (100%) | 62/62 (100%) |
| `web/src/app/shell/page-shortcuts.ts` | 12/12 (100%) | 6/6 (100%) | 5/5 (100%) | 6/6 (100%) |
| `web/src/app/shell/shortcuts-dialog.ts` | 43/44 (97.72%) | 10/11 (90.9%) | 1/1 (100%) | 23/23 (100%) |
| Aggregate (summed counters, 6 files; no file missing from the report) | 711/720 (98.75%) | 491/517 (94.97%) | 174/176 (98.86%) | 561/561 (100%) |

Before my tests the mail-read-page.ts, list-page, session and shortcut files were already in the report. No baseline run was made, so the table shows the final state only.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-read-page.ts:251` branch 1 (`shown ? ... : shown` in markUnread) | Defensive: the message cannot be null here, because the line before already checked that the shown id equals `id` | Justified gap, not reachable |
| `mail-read-page.ts:84` host-binding function, stmt 84 | Compiler-generated `(document:keydown)` wrapper. The handler itself runs in many key tests, so this looks like a source-map artifact | Non-material |
| `mail-read-page.ts:99, 120, 134, 145, 149, 163, 174, 195, 202, 207, 430, 462, 464, 492, 496, 561` (branches and stmts) | Code from before this task: route-param fallbacks, image and allowlist guards, GTD and attachment guards, address and content-type fallbacks | Outside the task's changed lines; no test added |
| `mail-list-page.ts:48, 98, 102, 109, 120, 349, 424, 445` | Code from before this task (route snapshot fallbacks, select guard, scroll helpers) | Outside the task's changed lines |
| `mail-list-session.ts:159` branch 0 | Code from before this task (`fetchMore` guard) | Outside the task's changed lines |
| `shortcuts-dialog.ts:31` stmt, `:66` branch 0 | Compiler-generated template and class code, mapped onto the template outlet line and the class declaration. All rendering paths are tested | Non-material |

## Unable to test

- `sh scripts/check.sh` (AC4): not run here because it includes Gradle suites. The parent worker runs it as `.taskcheck`.
- The German build as a whole: `npm run build:de` fails on 16 `calendar.invite.*` units ("No translation found"). Those units come from the itip invitation dialog, which already exists at the base commit, so the failure is not caused by this task. The build reports no missing unit for this task's mail or shortcut strings. Follow-up belongs to the task that owns the calendar invitation dialog.
- Real browser behavior (Playwright) of the new buttons and keys: no criterion names e2e tests, and none were run. The memory note "grep e2e for changed selectors" applies: the read view's action bar gained three buttons before Archive, so the parent may want to grep `web/e2e` for selectors that rely on button order.

## Summary

Tests added and all validation passed. Task-scoped coverage is sufficient: every changed executable line is covered, and no production defects were found.
