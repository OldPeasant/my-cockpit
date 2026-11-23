# Code Review

Date: 2026-11-23

Task: and-gtd-tickler-chip - Android: Tickler as list choice, 'new today' marker in Next

Scope: `git diff c4b38a016..HEAD -- android SPEC.md` (committed changes only; static review, Gradle not run per parent instruction)

Requirements:
- tasks/and-gtd-tickler-chip.md acceptance criteria and recorded assumption
- SPEC.md §5.1 / §5.3
- AGENTS.md project notes (strings EN/DE, lint, conventions)

Git range:
- Branch: main
- HEAD: 06a4d696c0435a1a274233a63b3ca314693bf5e2
- Base/start: c4b38a01604c4325040f5e24b58e651b8c797045
- Commits reviewed: 5 (0d14ce0d7, fd5a765f3, 2dfad2f5c, 88bde0bbc, 06a4d696c; plus merge 65bed3ed7 bringing them in)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## Criteria check

- SPEC §5.1/§5.3: both rules documented (form chip order, `list = INBOX` + `ticklerDate`, tickler wins, other chip clears, "New today" first, filters apply, next-day normal). Met.
- Form chips Inbox, Next, Waiting For, Scheduled, Tickler, Someday; Tickler requires a date (Required) and a future one on a move (NotFuture); saves `TaskMove(INBOX, ticklerDate)`; separate "Comes back" field removed (tickler date field only while Tickler is chosen). Met (`TaskDetailListFields.kt:68-110`, `TaskDetailViewModel.kt:116-172`).
- Tickler wins on open (`TaskDraft.of(detail)` sets `tickler`), other chip clears via `onList`/`scheduledOn`, Scheduled still requires a date, unchanged save sends neither move nor patch (`move` compares the stored list and tickler). Met; covered by TaskDetailListFieldsTest and TaskDraftListTest.
- One name: `task_detail_tickler`, `task_detail_tickler_date`, `inbox_action_tickler`, `inbox_prompt_tickler` and hints in EN/DE use Tickler/Wiedervorlage; no "Comes back"/"Kommt zurück" remains in resources. Met.
- Next "New today": text label with content description, sorted first with a stable sort after filtering (`NextViewModel.kt:221`, `398-408`), only `list == NEXT && releasedOn == today`. Met.
- Strings in EN and DE: all new keys present in both. Test coverage: see CR-1.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Inbox tickler prompt and Next ViewModel wiring are untested**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/inbox/InboxScreen.kt:598`, `android/app/src/main/java/ch/sonensei/cockpit/gtd/next/NextViewModel.kt:221`
    - Category: Tests
    - Problem: No test references the new Inbox prompt title ("Tickler: hidden until") or its hint `inbox_prompt_tickler_hint` (grep over `src/test` and `src/androidTest` finds them only in TaskDetailListFieldsTest, for the form hint). The "New today" ordering is tested only through the pure helpers `isNewToday`/`newTodayFirst` (NextNewTodayTest); no NextViewModel test checks that `uiState.tasks` puts released-today tasks first and that an active filter or preset still excludes a released-today task that does not match.
    - Why it matters: Criterion 6 asks that unit/Compose tests cover criteria 4 and 5; the Inbox-processing naming/hint and "filters and presets still apply to them" are part of those criteria and could regress (for example, sorting before filtering or dropping the hint) without a failing test.
    - Suggested fix: Add an InboxScreen Compose test opening the Tickler date prompt and asserting the title and hint text; add a NextViewModel test with a fake repository emitting tasks with `releasedOn = today` (fixed clock) asserting order in `uiState.tasks` and that a non-matching released-today task is filtered out.

## P3 Low

- [ ] **CR-2 | Stale "Pick the day it comes back." error after switching away from Tickler and back**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/task/TaskDetailViewModel.kt:873`
    - Category: Correctness
    - Problem: `changed(TaskField.TICKLER, a, b)` compares only `ticklerDate`. After saving with Tickler chosen but no date (Required problem), picking another chip sets `tickler = false` while `ticklerDate` stays `null`, so the TICKLER problem is not removed from `state.problems`. Choosing Tickler again shows the Required error immediately, before any save attempt. (Save itself recomputes problems, so nothing wrong is sent.)
    - Why it matters: Minor UX inconsistency with the rule in `edit` that "a field changed by the user no longer shows its old problem"; the tickler choice is now part of that field.
    - Suggested fix: Make the TICKLER case `a.ticklerDate != b.ticklerDate || a.tickler != b.tickler`.

## Review conclusion

Material issues found; fix before completing the task. (Only a P2 test gap and a P3 polish item; no correctness, integration or security defects in the reviewed scope.)
