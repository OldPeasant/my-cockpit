# Code Review

Date: 2026-11-23

Task: and-gtd-tickler-chip - Android: Tickler as list choice, 'new today' marker in Next

Scope: `git diff c4b38a016..d67981b30 -- android SPEC.md` (committed changes only; static review, Gradle not run per parent instruction). Round 1 reviewed up to 06a4d696c; round 2 reviewed the delta `git diff 06a4d696c..d67981b30 -- android` on top of it.

Requirements:
- tasks/and-gtd-tickler-chip.md acceptance criteria and recorded assumption
- SPEC.md §5.1 / §5.3
- AGENTS.md project notes (strings EN/DE, lint, conventions)
- reviews/and-gtd-tickler-chip/fixes.md (claimed fix of CR-2)

Git range:
- Branch: main
- HEAD: d67981b3001b19e0b139bd3248cc188bb51707ad
- Base/start: c4b38a01604c4325040f5e24b58e651b8c797045
- Commits reviewed: 7 (round 1: 0d14ce0d7, fd5a765f3, 2dfad2f5c, 88bde0bbc, 06a4d696c via merge 65bed3ed7; round 2: f2f53f918 tests only, d67981b30 CR-2 fix plus regression test)
- Uncommitted changes: none at review start

Summary: 0 P0, 0 P1, 0 P2, 0 P3 open (round 1 findings CR-1 P2 and CR-2 P3 are resolved, see below)

## Criteria check

- SPEC §5.1/§5.3: both rules documented (form chip order, `list = INBOX` + `ticklerDate`, tickler wins, other chip clears, "New today" first, filters apply, next-day normal). Met.
- Form chips Inbox, Next, Waiting For, Scheduled, Tickler, Someday; Tickler requires a date (Required) and a future one on a move (NotFuture); saves `TaskMove(INBOX, ticklerDate)`; separate "Comes back" field removed. Met (`TaskDetailListFields.kt`, `TaskDetailViewModel.kt`).
- Tickler wins on open, other chip clears, Scheduled still requires a date, unchanged save sends neither move nor patch. Met; covered by TaskDetailListFieldsTest and TaskDraftListTest (round 2 added cases for tickled Waiting/Scheduled/Someday/Inbox tasks, scheduled date while Tickler chosen, and no patch of list-bound fields).
- One name: Tickler/Wiedervorlage in form, detail view and Inbox processing, EN and DE; no "Comes back"/"Kommt zurück" left. Met; now asserted by TaskDetailStringsTest, TaskDetailContentTest and InboxScreenTest.
- Next "New today": label with content description, released-today tasks first via stable sort after filtering, only `list == NEXT && releasedOn == today`. Met; now covered end to end through NextViewModel (NextViewModelNewTodayTest).
- Strings in EN and DE: all new keys present in both, asserted in TaskDetailStringsTest.

## Round 1 findings, status at d67981b30

- [x] **CR-1 | Inbox tickler prompt and Next ViewModel wiring are untested** (P2) - Resolved.
    - `InboxScreenTest.kt:205` opens the Tickler prompt and asserts the title "Tickler: hidden until" (matches `strings_inbox.xml:52`) and the hint (`strings_inbox.xml:53`), and that the Schedule prompt shows no hint (matches `InboxScreen.kt:592-600`).
    - `NextViewModelNewTodayTest.kt` drives the real `NextViewModel` (constructor signature matches `NextViewModel.kt:165-173`) with a fixed clock: released-today first in stable order, context and energy filters still hide non-matching released-today tasks, a preset still applies, next day keeps normal order, and "today" is the local zone date (Europe/Zurich across UTC midnight).
- [x] **CR-2 | Stale "Pick the day it comes back." error after switching away from Tickler and back** (P3) - Resolved.
    - `TaskDetailViewModel.kt:873` now reads `TaskField.TICKLER -> a.ticklerDate != b.ticklerDate || a.tickler != b.tickler`, so `edit` (`TaskDetailViewModel.kt:468-475`) drops the TICKLER problem when the chip choice changes. This mirrors how SCHEDULED and WAITING_FOR already include the list change.
    - Regression test `TaskDetailViewModelRelistTest.kt:205` saves Tickler without a date (Required), picks Next (problems empty), picks Tickler again (still empty, nothing sent). It exercises the exact path of the finding.

## Round 2 delta observations

- The production change is the single line above; no other main-source changes in the delta.
- New tests assert user-visible behavior (texts, selection state, sent `TaskMove`/patch, `uiState.tasks` order) rather than internals; mocks are limited to repositories feeding flows and do not hide the behavior under test. No existing test was removed or weakened.
- Test data is fictitious (example names, made-up tasks).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None open.

## P3 Low

None open.

## Review conclusion

No material issues found. CR-1 and CR-2 from round 1 are resolved in d67981b30; the final revision meets the task acceptance criteria on static review (test execution is left to the parent worker and the test-writer, Gradle was not run here).
