# Code Review

Date: 2026-11-08

Task: and-task-detail-dates - Android task detail: scheduled, tickler, recurrence, all lists

Scope: `git diff f35bb0f4..801e378d` (task commits 129d0f7c, 4d7e4c4f, 801e378d), committed changes only

Requirements:
- tasks/and-task-detail-dates.md (acceptance criteria and recorded assumptions)
- SPEC.md, docs/ARCHITECTURE.md, AGENTS.md project notes (conventions)
- Server contract: `TaskService.move` / PATCH validation (server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/TaskService.kt), `ChecklistService` position update
- Web parity reference: web/src/app/features/gtd/task-detail/task-draft.ts, recurrence.ts

Git range:
- Branch: main
- HEAD: 801e378d
- Base/start: f35bb0f45616621be7a38d2fc2983bc2945db22e
- Commits reviewed: 3

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Verification notes

- AC1 (scheduled/tickler set and cleared, picker + quick chips): `DateField` in TaskDetailListFields.kt offers Tomorrow / In a week / In a month chips (same set as inbox processing, `strings_inbox.xml`), a picker and a clear button. Scheduled date and SCHEDULED list are coupled (`TaskDraft.scheduledOn`/`onList`), which matches the server rule that `scheduledDate` is only allowed on SCHEDULED. The tickler is always resent with a move, because the server clears an omitted tickler; a past tickler on a move is caught client-side (`FieldProblem.NotFuture`), matching the server's "must be in the future".
- AC2 (all five lists, waiting-since): all lists are offered; a move goes through `/move` (`relist`), then a PATCH on the moved version, mirroring the web's `task-draft.ts`. Moving to WAITING sends the person with the move; waiting-since is patchable only for a task already on WAITING (server sets today on a move to WAITING). A PATCH refused after a successful move keeps the moved task in state, so a retry does not move again.
- AC3 (recurrence): presets none/daily/weekdays/weekly/monthly/yearly/custom, interval (INTERVAL=n, 1..999), FIXED/AFTER_COMPLETION; anything outside the simple shape round-trips as custom text. `sameAs`/`rule()` normalisation avoids spurious PATCHes; mode is sent as null when there is no rule (server requires rule and mode together).
- AC4 (checklist reorder): long-press drag by whole rows plus Move up / Move down custom accessibility actions; positions saved as 0..n with each item's own version. The server's checklist position update is a plain set, so sequential per-item updates are correct; a refused update stops the loop and the server checklist is reloaded.
- AC5: every new string key exists in both `values/` and `values-de/` (checked by diff of the added lines).
- AC6: new tests cover draft rules, move/patch sequencing and failure paths, repeat parsing, Compose list/date/repeat fields, accessibility actions and drag. No acceptance criterion has recorded evidence yet (`tasks.py verify and-task-detail-dates` shows all unticked); the parent must record the ktlint/test/lint and `scripts/check.sh` runs before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Drag gesture keeps a stale checklist end index after items are added**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailScreen.kt:644`
    - Category: Correctness
    - Problem: `pointerInput(item.id, index, enabled)` restarts only when these keys change, but the gesture's `onDragEnd` clamps with `last` (`detail.checklist.lastIndex`) captured when the coroutine started. Adding an item at the end does not change any existing row's `id`/`index`, so the running gesture handler still clamps to the old last index: dragging an existing item past the bottom lands it one place above the new last item.
    - Why it matters: after adding an item, the user cannot drag an earlier item to the very end until the row recomposes with new keys (e.g. after another reorder). The accessibility actions are not affected (the semantics block is re-evaluated).
    - Suggested fix: add `last` (or `detail.checklist.size`) to the `pointerInput` keys, or skip the clamp in the screen and rely on `reordered`, which already clamps `to` into the list.

- [ ] **CR-2 | Suppression annotation that suppresses nothing**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModel.kt:86`
    - Category: Quality
    - Problem: `@Suppress("LongParameterList")` was added to `TaskDraft`, but the Android build has no detekt (no `detekt` in `android/build.gradle.kts`, `android/app/build.gradle.kts` or `libs.versions.toml`), and it is the only such suppression in `android/app/src/main`.
    - Why it matters: dead annotation, and the project convention is to fix lint warnings rather than suppress them; it suggests a check that does not exist.
    - Suggested fix: remove the annotation.

## Review conclusion

No material issues found.
