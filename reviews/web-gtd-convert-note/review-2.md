# Code Review

Date: 2026-10-21

Task: web-gtd-convert-note - Inbox/task detail: to reference

Scope: final code at HEAD for the task, `git diff 7ce21cc0..HEAD -- web`. The commits are 4e0c4b53 (implementation), 59c9af2b (tests), 21ae7bdb (fixes for CR-3/CR-4 of review-1.md) and 3e5896a2 (test-writer: 5 new specs and Prettier-only reformatting). The previous version of this report covered 21ae7bdb. The delta since then (`git diff 21ae7bdb..HEAD -- web`) touches only `inbox-page.spec.ts` and `task-detail.spec.ts`; no production code changed. On the parent's request, this revision also judges the two undefined behaviors listed in tests.md ("Bugs found").

Requirements:
- tasks/web-gtd-convert-note.md (acceptance criteria and the two recorded assumptions)
- SPEC.md §5.3 (Inbox processing action "To reference (note)") and the §5 Reference row
- api/openapi.yaml `POST /api/v1/tasks/{id}/convert-to-note` (convertTaskToNote, If-Match, NoteResponse)
- docs/ARCHITECTURE.md, AGENTS.md conventions
- reviews/web-gtd-convert-note/review-1.md, fixes.md, tests.md
- Existing component conventions: `TaskDetail.save()` drops late answers for a previous task (`task-detail.ts:294`, `:301`). The inbox `run()` comment says the status line "describes only the latest action" (`inbox-page.ts:241`).

Git range:
- Branch: main
- HEAD: 3e5896a2 (web-gtd-convert-note: more tests (test-writer), test and review reports)
- Base/start: 7ce21cc072525cedb46378ee80989f449382cd1a
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-5 | Task detail: a conversion that succeeds after the editor switched to another task still opens the old task's note and blocks the new task meanwhile**
    - File: `web/src/app/features/gtd/task-detail/task-detail.ts:259` (and `:252`/`:263`, the component-wide `converting` flag)
    - Category: Correctness
    - Problem: `toReference()` captures `task` and, on success, always runs `router.navigate(['/notes', note.id])`. It does not check `task.id === this.taskId()`. The catch branch has that check (`:261`), and `save()` uses the same check for both success and failure (`:294`, `:301`). The editor can switch tasks during a conversion: `TaskDetailPage` reuses the component when `/tasks/:id` changes, for example after a click on a linked task in Linked items (`linked-items.html:119`, `routerLink` to the deep link) or browser back/forward. When that happens: (a) `converting` stays `true` for the new task, so the new task's button shows "Moving to reference…" and its save is refused (`:278`); (b) when the old request answers, the user is taken from the new task to the old task's note. Anything typed into the new task in the meantime is dropped without the `DISCARD_CONFIRM` prompt that `close()` shows (I found no `canDeactivate` guard in `web/src/app`).
    - Why it matters: an older request's result takes over the screen of a newer selection. The project's review checklist treats this as a defect, and the component itself guards against it in `save()` and in the failure path. Data loss is limited to edits typed during the conversion window, but the new task looks blocked and the jump is unexpected. It is a real defect, not just an open spec question: the spec does not need to define this case for the result to be inconsistent with the component's own handling of late answers.
    - Suggested fix: navigate only when `task.id === this.taskId()` (the conversion still went through; the old task's Trash event or 404 handles the old view), and tie the running state to the converting task. For example, keep the id of the converting task in a signal (`convertingId`, null when idle) and derive `converting = computed(() => convertingId() === this.taskId())`, or reset it when `taskId` changes. Add a spec next to `shows no error for the new task when a conversion of the previous task fails late`: switch t1 to t2 while the conversion runs, resolve it, then expect no navigation and an enabled button/save for t2. Closing the editor during a conversion (`close()` is not disabled) is a different case: the user has left this view on purpose after confirming. Navigating to the note there is defensible and not part of this finding.

## P3 Low

- [ ] **CR-6 | Inbox: overlapping actions that answer out of order leave a status line that is not about the latest action**
    - File: `web/src/app/features/gtd/inbox/inbox-page.ts:235`, `:242`
    - Category: Correctness
    - Problem: inbox processing does not wait for an action's answer before showing the next item, so overlapping actions are a normal path. `lastNote` is written in answer order, not start order. Two cases follow. Two conversions answering in reverse order show the first item's note, not the latest one (the new spec `counts two overlapping conversions that answer in reverse order` deliberately does not assert which). A conversion of t1 that answers after a later action on t2 (for example Trash) has already succeeded and cleared the line (`:242`) puts back "Moved to reference …" for t1. Both go against the comment at `:241` ("describes only the latest action") and the intent of the CR-3 fix.
    - Why it matters: low impact. The line is always true and its link opens a real note with the same title (`inbox-page.html:68-74`), every note is still in Notes, and no data is wrong or lost. The user may just not get the link for the item they processed last. This is a cosmetic out-of-order result, so P3.
    - Suggested fix: give each `run()` a sequence number (`const seq = ++this.runSeq`), and set or clear `lastNote` only when `seq === this.runSeq` at the time of the answer. Assert the shown note in the reverse-order spec and add a "reference slow, trash fast" spec.

## Review conclusion

Material issues found; fix before completing the task.

CR-5 (P2) should be fixed: the fix is a one-line guard plus per-task running state, and it matches what `save()` already does. CR-6 (P3) may be fixed in the same pass or recorded by the parent as accepted with a reason.

What I checked in this revision:
- Delta 21ae7bdb..HEAD: only spec files changed. The Prettier changes (`HOME = {`, wrapped promise lines) do not change any assertion. The five new specs test real behavior through the DOM and the mocked API, not internals: a background reload started before the conversion is dropped (`loadSeq++`), a DELETED event before the answer still leads to exactly one navigation, 400 field errors are shown without a navigation, overlapping inbox conversions are counted, and a failed conversion keeps the previous note link. None of them hides behavior behind mocks. `npm --prefix web run test:ci -- --include=…/inbox-page.spec.ts --include=…/task-detail.spec.ts` at HEAD 3e5896a2: 2 files, 134 tests passed.
- The production code is unchanged since the previous version of this report. Its observations still hold (TaskStore `convertToNote` with optimistic removal and rollback, inbox `reference` action and `lastNote` handling, task detail confirm/guards/`failed()` path, generated client used as is, no injection, no logging, no secrets). The two findings above came from looking again at the out-of-order cases that tests.md raised.

Accepted or open items, not counted as findings:
- review-1 CR-2: the `/notes/:id` route comes with web-notes-editor (recorded as an assumption). When recording criterion 1, state that opening the note was checked through the link href and the router calls, not end to end.
- `python tasks.py verify web-gtd-convert-note` still shows both criteria unverified. The parent worker must record evidence (commands and results) before `done`.
