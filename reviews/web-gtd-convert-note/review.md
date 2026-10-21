# Code Review

Date: 2026-10-21

Task: web-gtd-convert-note - Inbox/task detail: to reference

Scope: review round 3 on the final code, `git diff d12d5eb4..HEAD` (HEAD 0ec8b9eb), production and spec files under `web/`. Focus on the CR-5/CR-6 fix commit 2f4ad12c and the three specs added in 0ec8b9eb, plus a re-check of the whole task against its acceptance criteria. The fix commit the parent and fixes.md name, 96c82af2, has the same web patch as 2f4ad12c (`git patch-id --stable`: dfcd8c5e… for both), so it is the same change after a rebase. The other commits in the range are `tasks/`/`reviews/` only (heartbeats, verify, assume from other agents and earlier review reports).

Requirements:
- tasks/web-gtd-convert-note.md (both acceptance criteria and the three recorded assumptions)
- SPEC.md §5.3 (Inbox processing action "To reference (note)")
- api/openapi.yaml `POST /api/v1/tasks/{id}/convert-to-note` (If-Match, NoteResponse)
- docs/ARCHITECTURE.md (web conventions: Prettier with `web/.prettierrc`), AGENTS.md
- reviews/web-gtd-convert-note/review-2.md (CR-5, CR-6), fixes.md, tests.md

Git range:
- Branch: main
- HEAD: 0ec8b9eb1d988debafaae5aa4e33d1317eeb5aae
- Base/start: d12d5eb4a2e21d90cc8289fd5a1abfc2707357f1
- Commits reviewed: 8 task commits that touch web/ or reviews/ (b29f14f8, b7193b91, c0011b78, 2f270ec3, 812ebfdc, 2f4ad12c, 57de9173, 0ec8b9eb). The rest of the range is heartbeat/verify/assume commits from other agents.
- Uncommitted changes: none at the start of the review.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-7 | New TaskStore spec is not Prettier-formatted**
    - File: `web/src/app/features/gtd/data/task-store.spec.ts:497`
    - Category: Quality
    - Problem: `npx --prefix web prettier --check web/src/app/features/gtd/data/task-store.spec.ts` fails. The only difference is on a line this task added (spec `keeps a converted task out of a list answer requested before the conversion`): `callsOf(listTasks).at(-1)!.resolve(page([a]));`, which Prettier wraps into a three-line chain. The other changed files in `gtd/inbox`, `gtd/task-detail` and `gtd/data` pass the check.
    - Why it matters: ARCHITECTURE.md says web code is formatted with Prettier. `ng lint` does not enforce it, so `npm run check` passes, but the next agent who formats this file gets an unrelated diff.
    - Suggested fix: `npx --prefix web prettier --write web/src/app/features/gtd/data/task-store.spec.ts`. This only changes formatting, so the review evidence stays valid.

## Review conclusion

No material issues found.

CR-7 is a formatting cleanup. The parent can fix it, or accept it and give a reason.

What I verified:
- **CR-5 fix (task-detail.ts):** `convertingId` holds the id of the task being converted, and `converting = computed(() => convertingId() === taskId())`, so the template, the `save()` guard and the change-event reload guard all work per task. On success, navigation happens only when `task.id === this.taskId()`, the same check as the failure path and `save()`. `finally` clears `convertingId` only if it still holds this task's id, so a late answer for t1 does not clear a later conversion of t2. I traced four cases: switch while running (t2 is free, no navigation), switch back (running state shows again, then navigates), convert t1 and then t2 (only the t2 answer navigates), and a late failure (no error shown on t2). Each case behaves correctly, and each has a spec.
- **CR-6 fix (inbox-page.ts):** each `run()` takes a sequence number before it awaits. `lastNote` is set (note or null) only when `seq === runSeq`. A failure leaves the line unchanged. `processed` still counts every success, so progress is right however the answers are ordered. A failed later action also stops an earlier slow success from writing the line, which fits "describes only the latest action". The fixer noted that `startProcessing()` does not advance `runSeq`. I do not count this as a defect: a conversion still running from the previous session is still the latest action, and its link is true.
- **Round 3 specs:** the three new specs check real behavior through the DOM, the mocked API and router spies. They check the reference button text and disabled state, the navigation calls, the `Open note` href, the title and the progress. Nothing internal is mocked. No existing assertion was weakened (the 2f4ad12c..HEAD spec delta only adds code).
- **Acceptance criterion 1:** the inbox `R` action (now enabled in `inbox-actions.ts`) calls `TaskStore.convertToNote`. The store removes the item optimistically, sends `If-Match`, rolls back on error and returns the note. The page shows an `Open note` link to `/notes/:id`. The task detail asks for confirmation (a separate message when there are unsaved changes), calls `convertTaskToNote` with `If-Match` and silent errors, and navigates to `/notes/:id`. Both behaviors match the recorded assumptions. The `/notes/:id` route comes with web-notes-editor. This is recorded as an assumption, so navigation is verified through the link href and router calls, not end to end.
- **Acceptance criterion 2:** there are specs for the store (`convertToNote`: optimistic removal, rollback and rethrow, a stale refetch), the service, the inbox page (convert call, queue/left/progress updates, rollback, ordering) and the task detail.
- **Tests I ran myself:** `npm --prefix web run test:ci -- --include=…/inbox-page.spec.ts --include=…/task-detail.spec.ts --include=…/task-store.spec.ts --include=…/task-detail.service.spec.ts --include=…/inbox-actions.spec.ts` at HEAD 0ec8b9eb: 5 files, 220 tests passed. I did not re-run `npm run check` in full. tests.md reports it passing (83 files, 1550 tests).
- **Security and integration:** the generated client is used unchanged, the endpoint and headers match the OpenAPI contract, user content is shown only through Angular interpolation, and nothing is logged. No secrets appear.

Open items for the parent, not counted as findings:
- `python tasks.py verify web-gtd-convert-note` still shows both criteria unverified. Record the evidence (commands and results) before `done`. For criterion 1, state that opening the note was checked through the link href and router calls only.
- The task note, fixes.md and tests.md name commit hashes from before the rebase (331a24a6, 96c82af2, 79a8ad13). `reviewed_revision` in quality.json must be the current final hash (0ec8b9eb, or whatever HEAD is after any CR-7 formatting commit).
