# Code Review

Date: 2026-10-20

Task: web-task-detail - Task detail editor

Scope: `git diff faf560c2..HEAD -- web/` (task-detail feature folder plus the `tasks` route line in `web/src/app/features/gtd/gtd.routes.ts`)

Requirements:
- tasks/web-task-detail.md acceptance criteria and recorded assumptions (routed page /tasks/:id, plain Markdown textarea, waiting-for as free text, move-then-patch save, client-side RRULE validator)
- SPEC.md §5.2, §4.2
- docs/ARCHITECTURE.md, AGENTS.md "Project notes"
- Server rules: server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskService.kt (move, validate), TaskRecurrence.kt
- reviews/web-task-detail/tests.md (TW-1 and the open question about the checklist)

Git range:
- Branch: main
- HEAD: 444d8ccab1f89806002d46f9305c2f64e97970ea
- Base/start: faf560c2cb8d28dd5218795b7ba326cf7eecb103
- Commits reviewed: 3 (813f5a7d, da9707fe, 444d8cca); no uncommitted changes

Summary: 0 P0, 0 P1, 2 P2, 2 P3

Checked against the server's move/patch rules and found consistent:
- `moveFor` sends list, scheduledDate (only for SCHEDULED) and ticklerDate together, and never sends waiting-for fields, matching `TaskService.move`.
- `patchFor` sends scheduled and waiting fields only while both the moved task and the draft are on that list, so `validate` cannot reject them as "only allowed for the ... list".
- `recurrenceMode` is cleared together with the rule. The client RRULE check is at least as strict as `TaskRecurrence.problem` (FREQ set, no time parts, COUNT >= 1).
- If-Match uses the version from the move response for the following PATCH. A 412 from either the move or the PATCH sets the conflict banner and reloads.

TW-1 (test-writer) is not repeated here. It is still open at HEAD, and its failing test makes `cd web && npm run check` fail, so acceptance criterion 4 is not met until TW-1 is fixed.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Background reload overwrites edits typed while it runs, and a stale reload can replace a newer save**
    - File: `web/src/app/features/gtd/task-detail/task-detail.ts:155-158`, `web/src/app/features/gtd/task-detail/task-detail.ts:281-289`
    - Category: Correctness
    - Problem: A TASK change event starts `load(id, false)` only when the form is not dirty and no save is running. These conditions are checked only when the request starts. When the GET returns, `load` always runs `task.set(task)` and `draft.set(draftOf(task))`. `loadSeq` only orders loads against each other, not against edits or saves. The server publishes the SSE event after the commit (`SseEventHub`, `@TransactionalEventListener`), so every save the user makes, and every checklist change (`TaskService.changed`), triggers this reload right after the HTTP response. Two consequences:
        (a) The user saves with Ctrl+S and keeps typing. The keystrokes typed while the GET runs are silently dropped.
        (b) A reload is in flight, and the user edits and saves before it returns. The older GET response then replaces `task` with the version before the save, so the form briefly shows the saved change as reverted. If the user edits again before the next event, the save is sent with an outdated If-Match and gets a false "changed elsewhere" 412.
    - Why it matters: This loses user input without any notice, and the conflict banner appears when nobody else changed the task. The existing test (`reloads on a change event unless the user is editing`) only checks the dirty state before the event, not while the GET runs.
    - Suggested fix: When a background load returns, re-check `dirty()`/`saving()` and drop the result if either is set. Also invalidate pending loads when a save starts or ends, for example by incrementing `loadSeq` in `save()`. Alternatively, apply a background response only if `task.version` is newer than the current one. Add a test where the user types, or a save completes, while an event-triggered GET is pending.

- [ ] **CR-2 | Checklist toggle and remove are not serialized: a quick double toggle or a toggle during a reorder ends in a false "changed elsewhere"**
    - File: `web/src/app/features/gtd/task-detail/task-checklist.ts:70-85`, `web/src/app/features/gtd/task-detail/task-checklist.ts:92-131`; `web/src/app/features/gtd/task-detail/task-checklist.html:162-167`, `web/src/app/features/gtd/task-detail/task-checklist.html:194-197`
    - Category: Correctness
    - Problem: This confirms the test-writer's open question. `toggleItem` and `removeItem` do not check `busy()`, and the checkbox and remove button are not disabled while a change runs. `toggleItem` replaces the item optimistically with `{ ...item, done: !item.done }` and keeps the old `version`. A second click before the first PATCH returns therefore sends If-Match with a version the server has already incremented. The result is a 412, the "The checklist was changed elsewhere" banner and a reload. A toggle during `reorder` has the same effect: `reorder` loops over its captured `items` with their old versions, so the PATCH for the toggled item gets a 412. Overlapping `change()` calls also share one `busy` flag. When the first call finishes it sets `busy` to false, which re-enables the reorder buttons and Add while the other request still runs.
    - Why it matters: Ordinary fast clicking in the user's own checklist shows a conflict message that is not true. It can also undo the user's last toggle through the reload or rollback. AC2 requires 412 handling for real concurrent changes, and here the client causes the 412 itself.
    - Suggested fix: Serialize checklist writes. Either disable the checkbox and remove button while `busy()` and return early in `toggleItem`/`removeItem` like `reorder` does, or queue the changes and always use the latest item from `items()`, including its server version, when sending. Add a test for a second toggle while the first is pending.

## P3 Low

- [ ] **CR-3 | Field errors other than the title are not linked to their inputs**
    - File: `web/src/app/features/gtd/task-detail/task-detail.html:86`, `:117`, `:134`, `:205`, `:231`, `:245`, `:278`
    - Category: Quality (accessibility)
    - Problem: Only the title connects its error through `aria-describedby` (`td-title-error`, line 63). Notes, scheduled date, waiting for, estimate, due time, tickler and the custom rule set `aria-invalid`, but their `<p class="field-error">` has no id and nothing references it. The custom-rule error (line 278) is also outside the rule's row. Screen-reader users hear "invalid" but not why.
    - Why it matters: Validation messages are part of the form-validation criterion, and the title already shows the intended pattern.
    - Suggested fix: Give each field error an id and set `[attr.aria-describedby]` on the matching input, as for the title. Move the custom-rule error into its row.

- [ ] **CR-4 | Save shortcut hint is hard-coded and wrong on macOS**
    - File: `web/src/app/features/gtd/task-detail/task-detail.html:317`
    - Category: Quality (i18n)
    - Problem: `<kbd>Ctrl ↵</kbd>` is not marked for i18n. `onKeydown` also accepts `metaKey` (Cmd), but macOS users still see "Ctrl".
    - Why it matters: This is a small inconsistency with the otherwise fully localized template.
    - Suggested fix: Mark the hint with an i18n id, or derive it from the platform as the app's shortcut help does. Alternatively, show the modifier only through an `aria-keyshortcuts`/title.

## Review conclusion

Material issues found; fix before completing the task. These are CR-1 and CR-2 (P2). In addition, the open TW-1 currently makes `npm run check` fail, so acceptance criterion 4 is not met yet.
