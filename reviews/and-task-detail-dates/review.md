# Code Review

Date: 2026-11-08

Task: and-task-detail-dates - Android task detail: scheduled, tickler, recurrence, all lists

Scope: `git diff 0a78a3fc..HEAD` (task commits 24fd02be, efe2d9e2, 17fe0466, 40c88f43, e95f8a12), committed changes only; Android code under `android/app/src/main/java/ch/sonensei/cockpit/gtd/task`, its tests, `res/values{,-de}/strings_task_detail.xml`

Requirements:
- tasks/and-task-detail-dates.md (acceptance criteria and recorded assumptions)
- SPEC.md, docs/ARCHITECTURE.md, AGENTS.md project notes (conventions)
- Server contract: `TaskService.move` / `update` validation (server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskService.kt), `TaskRecurrence.problem`, `ChecklistService` position update
- Web parity reference: web/src/app/features/gtd/task-detail/recurrence.ts, task-draft.ts

Git range:
- Branch: main
- HEAD: e95f8a12543526e547254b3ac47c0ba67962032b
- Base/start: 0a78a3fcba636583c679aa13acb93cc6a1580d96
- Commits reviewed: 5

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification notes

- Previous review (revision 801e378d): CR-1 is fixed. `pointerInput(item.id, index, last, enabled)` in TaskDetailScreen.kt now restarts the gesture when the checklist size changes, and a regression test was added ("after an item is added, dragging an earlier item past the bottom lands it on the new last place"). CR-2 is fixed too: `TaskDraft` no longer has the `@Suppress("LongParameterList")` annotation.
- AC1: `DateField` (TaskDetailListFields.kt) has quick chips for Tomorrow / In a week / In a month, a date picker and a clear button, for both the scheduled date and the tickler. Scheduled date and the SCHEDULED list are linked (`TaskDraft.scheduledOn`/`onList`). This matches the server rule that `scheduledDate` is only allowed on SCHEDULED, and it is recorded as an assumption. Every move resends the tickler, because `TaskService.move` clears a tickler it does not receive. A past tickler on a move is caught client-side (`FieldProblem.NotFuture`), the same check as the server's "must be in the future".
- AC2: all five lists are offered. A list or tickler change goes through `/move` (`relist`) and then a PATCH on the moved version. A move to WAITING carries the person. Waiting-since can be patched only for a task already on WAITING (the server sets today on a move to WAITING). If the PATCH is refused after a successful move, the moved task stays in state, so saving again does not move the task twice (tested).
- AC3: the presets are none, daily, weekdays, weekly, monthly, yearly and custom, with an interval (INTERVAL=n) and FIXED/AFTER_COMPLETION. Any other rule comes back unchanged as custom text. `sameAs` normalises the rule, so an unchanged rule sends no PATCH. The mode is sent as null when there is no rule (the server needs rule and mode together). Custom rules are sent normalised, the same way as the web's `normalizeRule`.
- AC4: rows are dragged by whole rows after a long press, and the Move up / Move down custom accessibility actions only appear where the item can move. Positions are saved as 0..n with each item's own version. Because the server sets positions directly, saving each item one after another is correct. A refused update stops the loop and the checklist is reloaded from the server.
- AC5: every key added to `values/strings_task_detail.xml` also exists in `values-de/strings_task_detail.xml`, plurals included (compared from the diff).
- AC6: tests cover the draft rules, the move/patch order and its failure paths, repeat parsing and writing, the Compose list, date and repeat fields, the accessibility actions and dragging. `python tasks.py verify and-task-detail-dates` shows no criterion recorded yet. Before `done`, the parent must record the runs of `./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` (gradle was not run in this review, as instructed).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | An existing preset rule with INTERVAL above 999 blocks every save of the task**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskRepeat.kt:84`
    - Category: Correctness
    - Problem: `RepeatDraft.of` reads any `INTERVAL >= 1` as a preset (for example `FREQ=WEEKLY;INTERVAL=1000` becomes WEEKLY with interval "1000"). `problem()` (line 62) refuses intervals outside 1..999, and `TaskDraft.problems()` always runs `repeat.problem()`, even when the repeat was not touched. The server accepts such a rule (`TaskRecurrence.problem` only parses it), and the web's custom-rule editor lets a user enter one.
    - Why it matters: on Android, such a task cannot be edited at all (title, dates, list) until the user changes or removes its repeat. The interval field also cuts input to 3 digits, so the value it shows cannot be kept.
    - Suggested fix: in `RepeatDraft.of`, return the custom draft when the interval is above `MAX_INTERVAL`, so the rule is kept as custom text. Alternatively, skip the interval check when the repeat is unchanged from the loaded one. Add a `TaskRepeatTest` case.

## Review conclusion

No material issues found.
