# Code Review

Date: 2026-11-08

Task: and-task-detail-dates - Android task detail: scheduled, tickler, recurrence, all lists

Scope: all task commits `53345f25..0f8ca9e7a` (8 commits, deeea8918..0f8ca9e7a), limited to
`android/app/src/main/java/ch/sonensei/cockpit/gtd/task`, `android/app/src/test/java/ch/sonensei/cockpit/gtd/task`
and `android/app/src/main/res/values{,-de}/strings_task_detail.xml`. Committed changes only; the working tree was clean.

Requirements:
- `tasks/and-task-detail-dates.md` acceptance criteria 1-6 and the four recorded assumptions (scheduled date coupled to
  the SCHEDULED list; save = one move then a PATCH, Waiting needs a person, waiting-since only editable when already
  waiting; presets with INTERVAL plus custom rule as text with a FREQ check; long-press row drag plus Move up/down actions)
- `AGENTS.md` project notes and conventions, `docs/QUALITY.md` report contract
- Server contract checked against `server/.../gtd/domain/TaskService.kt` (`move`) and `ChecklistService.kt` (position update)
- Previous review of this task (earlier revision), finding CR-1 (INTERVAL above 999 blocks saving)

Git range:
- Branch: main
- HEAD: 0f8ca9e7a81cd32e9c407923f2100b94cb07c8c7
- Base/start: 53345f2560dd5dd55d2ae7936bdbbe70f7f5dda8
- Commits reviewed: 8

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Verification of the previous finding

- Previous CR-1 (P3, preset rule with INTERVAL above 999 made the task unsaveable): fixed in aef1e37b7.
  `RepeatDraft.of` (`TaskRepeat.kt:84`) now accepts only `INTERVAL` in `1..MAX_INTERVAL` as a preset and keeps any other
  value as a custom rule, so `problem()` no longer flags the unchanged rule and the editor shows it as text.
  `TaskRepeatTest` covers the boundary on both sides (`INTERVAL=999` stays a DAILY preset, `INTERVAL=1000` becomes
  custom with the original text and mode).

## What was checked

- Correctness of the form model (`TaskDraft.onList`, `scheduledOn`, `problems`, `move`, `patch(task, recurrence)`): the
  move always resends the tickler (the server clears an omitted one), sends `scheduledDate` only for SCHEDULED and
  `waitingForText` only for WAITING, matching `TaskService.move` validation; the PATCH only sends list-bound fields
  while the moved task is on that list; recurrence is compared by normalized rule and mode, so a rule like
  `FREQ=DAILY;INTERVAL=01` causes no spurious PATCH.
- Save flow in `TaskDetailViewModel.save`: a successful move updates `detail` before the PATCH, so a refused PATCH
  leaves a consistent screen and a retry does not repeat the move; failures end `busy` through `refused`/`afterRefusal`;
  `busy` blocks double submits.
- Checklist reorder: `reordered` clamps the target, `moveChecklistItem` saves only changed positions sequentially
  (the server sets `position` without shifting siblings, so per-item versions stay valid), and reloads the checklist
  from the server on success and failure. Drag gesture keys include index, size and enabled state; accessibility
  actions are offered only where a move is possible and only when editable.
- Display: `repeatText` renders presets and intervals with plurals, custom rules verbatim, and the mode.
- Strings: all 35 new keys (30 strings, 5 plurals) exist in both `values/` and `values-de/` with matching format
  arguments.
- No secrets, logging of note contents, or unsafe input handling introduced.

Command run:
- `cd android && ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.gtd.task.*'` -> 12 suites, 223 tests,
  0 failures, 0 errors, 0 skipped.

Note for the parent worker (not a code finding): `python tasks.py verify and-task-detail-dates` still shows all six
criteria unrecorded; record the evidence before `done`. Criterion 6 names `ktlintCheck`, `lintDebug` and
`sh scripts/check.sh`, which this review did not run.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
