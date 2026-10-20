# Test Report

Date: 2026-10-20

Task: web-inbox - Inbox processing mode

Scope: task commits `0792ca45574b8ac3252236886e108c9c959a8165..b3317b1144ffffefff7d2490aa99d24b58c364c0` on `main`. That is 5 commits, all prefixed `web-inbox:` (e0d1233, a1e8c8f, 24d2864, 063283b, b3317b1), plus the test-writer's uncommitted test additions in `web/src/app/features/gtd/inbox/inbox-page.spec.ts`. The working tree also had an uncommitted change to `reviews/web-inbox/review.md` that the test-writer did not make, probably from a parallel code-reviewer run. It is not part of the tested inputs.

Requirements:
- `tasks/web-inbox.md` acceptance criteria and recorded assumptions (shortcut letters, To project/Tickler/Waiting semantics, a one-PATCH draft, a tickler filter in `matchesTask`, list click to `/tasks/{id}`)
- SPEC.md §5.3 (inbox processing), §4.2 (ETag/If-Match)
- docs/ARCHITECTURE.md "Coverage"
- `reviews/web-inbox/review.md` and `reviews/web-inbox/fixes.md` (CR-1 to CR-5 regression tests already present)

## Test files changed

- `web/src/app/features/gtd/inbox/inbox-page.spec.ts`

## Tests added or updated

Count: 5 (added; no existing test changed)

- `InboxPage > when the server refuses > does not move the item when creating its new project fails`
- `InboxPage > processing > saves the inline attributes before Do it`
- `InboxPage > processing > saves the inline attributes before a prompted action, with the prompt value`
- `InboxPage > processing > shows inbox zero when another device empties the inbox while processing`
- `InboxPage > processing > closes an open prompt when its item leaves the inbox elsewhere`

Tests already in the task (all still pass): `inbox-actions.spec.ts` (action table, key mapping, clarification patch), `inbox-page.spec.ts` (49 component tests before this run), `task-store.spec.ts` (tickler filter and tickler move/rollback), `app.spec.ts` (lazy `/inbox` route), `app.i18n.spec.ts` (moved to `/someday`), and `e2e/gtd-inbox/inbox.spec.ts`.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: Inbox list, and 'Process' mode with one item and 'x items left' | `lists the inbox and starts processing with P`, `shows an empty inbox without a process button`, `starts from the Process inbox button and goes back with Back to list`, `shows the progress and the items left until inbox zero`, `shows inbox zero when another device empties the inbox while processing` (new), `shows the notes of the item` | `cd web && npx ng test --watch=false --include src/app/features/gtd/inbox/inbox-page.spec.ts` | Pass |
| AC1: actions with shortcuts: Do it, Next, Waiting, Someday, Schedule, Tickler, To project (existing/new), Trash | `key %s moves the item with %o` (n, m), `D completes the item`, `X and Delete move the item to the Trash`, `S asks for a date, Enter schedules`, `T puts the item in a tickler...`, `W asks for the person (optional)`, `P moves the item to an existing project`, `P can create a new project for the item`, `Enter on the project choice moves the item`, `runs an action from its button`, `actionForKey` table tests | same plus `inbox-actions.spec.ts` (in `npm run check`) | Pass |
| AC1: 'To reference' and 'To calendar' present but disabled | `offers To reference and To calendar disabled, their keys do nothing`, `disables only To reference and To calendar`, `returns null for the disabled actions`, `marks the enabled action buttons with their shortcut` | same | Pass |
| AC2: Inline energy, creativity, contexts and estimate set before the action | `saves the inline attributes before the action`, `saves the inline attributes before Do it` (new), `saves the inline attributes before a prompted action, with the prompt value` (new), `sends only the attributes changed inline, keeping a concurrent change` (CR-1), `does not send an update when nothing was changed`, `clears an attribute with null`, `starts each item with its own attributes...`, `trashes without saving the draft attributes`, `clarificationPatch` unit tests | same | Pass |
| AC3: each action calls the right API; keyboard shortcuts | All tests above, plus the `keyboard` describe (dialogs/overlay, inputs, modifiers, repeat, upper case, prompt open, Escape) and the server-refusal tests (failed move, update, trash, complete, create project, retry after load error, out-of-order failure after the next item came up) | same | Pass |
| AC3: `cd web && npm run check` passes | whole web suite, 45 files / 757 tests, lint, api:check, e2e typecheck, build | `cd web && npm run check` | Pass (exit 0; the bundle-budget warning was there before this task) |
| AC4: Playwright `e2e/gtd-inbox/inbox.spec.ts` passes against the e2e stack: 3 items processed to an empty inbox | `inbox processing > process 3 items to an empty inbox` | `cd web && npm run e2e -- e2e/gtd-inbox/inbox.spec.ts` (deploy/docker-compose.e2e.yml via podman-compose, server profile e2e, fresh Postgres, chromium) | Pass: 6 passed (the inbox test on chromium plus 5 phase-demos project tests that the runner always includes), 2026-10-20 22:36-22:37 |
| Assumption: future ticklerDate hidden from lists (`matchesTask`) | `hides a task whose tickler date is still to come, on every list (server rule)`, `hides a task put in a tickler from the Inbox...`, `brings a task back to the Inbox when its tickler move fails` | `npm run check` | Pass |
| Assumption: list title click navigates to `/tasks/{id}` | `opens a task from the list` | focused command | Pass |
| Route `/inbox` shows InboxPage | `lazy-loads the inbox page at /inbox` | `npm run check` | Pass |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include src/app/features/gtd/inbox/inbox-page.spec.ts`

Result:

Pass (1 file, 54 tests)

## Full test execution

Command:

`cd web && npm run check`

Result:

Pass (exit 0: 45 test files / 757 tests, lint clean, build OK)

E2E: `cd web && npm run e2e -- e2e/gtd-inbox/inbox.spec.ts`: Pass (6 passed)

## .taskcheck

Result:

Pass (`sh scripts/check.sh`: tfp gate tests, server check, web check with 757 tests, android ktlint/unit/lint; "all checks passed", finished 2026-10-20 22:38)

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured as a separate metric. By inspection of the source-mapped uncovered locations, the only changed line in `task-store.ts` (the tickler check in `matchesTask`) has no uncovered statement or branch.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `web-inbox`. The base `0792ca45574b8ac3252236886e108c9c959a8165` and HEAD `b3317b1144ffffefff7d2490aa99d24b58c364c0` came from the parent. The task commits are the 5 `web-inbox:` commits in `git log base..HEAD`. The file scope is `git diff --stat base..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD b3317b1 plus uncommitted `web/src/app/features/gtd/inbox/inbox-page.spec.ts` with sha256 `6e39f10f19227bd3428118b16b56c6504c0fede00b1dbd0d9fbbb368a76b4f98`. The production files were unchanged before and after the runs: `inbox-page.ts` cf333b7e…, `inbox-actions.ts` 95058347…, `inbox-page.html` 9112df0b…, `task-store.ts` 8a0e9348… (sha256 prefixes).
- Exact included files: `web/src/app/features/gtd/inbox/inbox-page.ts`, `web/src/app/features/gtd/inbox/inbox-actions.ts`, `web/src/app/features/gtd/inbox/inbox.routes.ts`, `web/src/app/features/gtd/data/task-store.ts`. Excluded: `inbox-page.html` and `inbox-page.scss` (template and styles; v8 does not count them separately, and the template's behavior runs through the component tests), the `*.spec.ts` files, `e2e/gtd-inbox/inbox.spec.ts` (test code), and `reviews/*` (docs). No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with `@vitest/coverage-v8` 5.0.5. The command was `npm run coverage` (`ng test --watch=false --coverage ...`) in `web/`, over the full unit suite.
- Run start/completion time and exit status: 2026-10-20 22:35:26 to 22:35:51 +02:00. 45 files / 757 tests passed. Report regenerated.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-final.json` (sha256 f4a67fa9dfb12693d9c910d5e040ec0c4d79eb837c94147bf6d3adc9f0804c10) and `web/coverage/web/coverage-summary.json` (sha256 4d6fe72f3fa989665df1fdc48b1511937d1fa440b3b1055e6d558081cdcc7f1c). Their hashes differ from those of the earlier pre-addition run (1e4a5a45… / 45413dc1…), so the report was rewritten.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The test count went from 752 to 757, the inbox-page branch count changed (83 to 84 of 88), the report hashes changed, and the production hashes were unchanged.
- Final run after latest test additions and production fixes: yes. It ran after all 5 additions, on HEAD b3317b1, which includes fixes CR-1 and CR-3 to CR-5.

Values below use covered/total (percentage).

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/gtd/inbox/inbox-page.ts` | 149/153 (97.38%) | 84/88 (95.45%) | 36/37 (97.29%) | 123/124 (99.19%) |
| `web/src/app/features/gtd/inbox/inbox-actions.ts` | 22/22 (100%) | 22/22 (100%) | 7/7 (100%) | 18/18 (100%) |
| `web/src/app/features/gtd/inbox/inbox.routes.ts` | 1/1 (100%) | N/A (0/0) | N/A (0/0) | 1/1 (100%) |
| `web/src/app/features/gtd/data/task-store.ts` | 67/67 (100%) | 75/78 (96.15%) | 21/21 (100%) | 52/52 (100%) |
| **Aggregate (4 files, summed counters)** | 239/243 (98.35%) | 181/188 (96.28%) | 64/65 (98.46%) | 194/195 (99.49%) |

All 4 files in scope were measured; none are missing. The template and styles are excluded, as explained above.

Before the added tests, the inbox-page.ts branches were 83/88. The new test "shows inbox zero when another device empties the inbox while processing" covers the `total === 0` branch of `progress`.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `inbox-page.ts:160` (`if (!task) return` in `choose`) | Defensive guard: the action buttons only render while there is a current item, and `onKeydown` checks `current()` first | Left uncovered: not reachable through the UI |
| `inbox-page.ts:218` (`if (!task) return` in `run`) | Defensive guard: `run` is only called from `choose` or `confirmPrompt` while an item is shown | Left uncovered: not reachable through the UI |
| `inbox-page.ts:253/283` (`default:` of `apply`) | The `reference`/`calendar` actions are disabled and `trash` is handled before `apply` | Left uncovered: unreachable until later tasks enable those actions |
| `inbox-page.ts:122` (`viewChild` arrow) and `:40` branch | Angular decorator/compiler-emitted code | Left uncovered: generated glue. The prompt-field focus is checked by `S asks for a date` (`document.activeElement`) and `Enter on the project choice` |
| `task-store.ts:52, 91, 109` (one `??`/`||` alternative each) | Pre-existing code that this task did not change | Out of task scope |

## Unable to test

- None of the acceptance criteria. The e2e ran against the real compose stack (podman-compose) with chromium.
- The template's visual layout (SCSS) is not asserted. That is not required by the criteria.

## Summary

Tests added and all validation passed: 5 new component tests, focused 54/54, `npm run check` passed, the e2e spec passed against the e2e stack, and `.taskcheck` passed. No production defects found. Task-scoped coverage is 98.35% statements and 96.28% branches; the only uncovered code is defensive or unreachable.
