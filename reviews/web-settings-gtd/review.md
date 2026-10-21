# Code Review

Date: 2026-10-21

Task: web-settings-gtd - Settings: contexts and areas

Scope: task commit 79e2c255 on top of base 3aa4bbbd (`git diff 3aa4bbbd..79e2c255`), committed changes only

Requirements:
- tasks/web-settings-gtd.md acceptance criteria (1) and (2)
- SPEC.md §5.1 (Areas of focus, Contexts)
- api/openapi.yaml: /api/v1/contexts, /contexts/order, /contexts/{id}, /areas, /areas/order, /areas/{id}, GET /tasks, PATCH /tasks/{id}, GET /projects, PATCH /projects/{id}
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- Parent instructions: check reassignment-before-delete, error handling, accessibility

Git range:
- Branch: main
- HEAD: 79e2c255
- Base/start: 3aa4bbbd3a9abc422a576d0cf520bbcdebc6fb81
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 1 P2, 2 P3

Verification I ran (read-only):
- `npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'` -> 7 files, 112 tests passed
- `npx ng lint --max-warnings=0` -> all files pass
- `python tasks.py verify web-settings-gtd` -> both criteria not yet recorded (the parent records them)

What I checked: the reorder flow (optimistic move, `busy` gate, restore on failure, server order taken on success), the delete flow (count, dialog, reassignment, delete with If-Match, list update), the If-Match/ETag use on every write, the paging over task and project lists (limit 200 fits the API's 1-200 range), the `context`/`completed`/`areaId` query parameters against the OpenAPI spec, `color: null` clearing (the spec allows it for PATCH /contexts), the route wiring and its spec, and accessibility (labelled lists, arrow buttons as a keyboard alternative to drag, aria-labels on icon buttons and color inputs, a labelled radio group, `role="alert"` errors). They match the contract. I found no security issues: no secrets, no unsafe HTML, and errors are not toasted twice.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | "Move to another context" skips tasks that are in a tickler, and they lose the context**
    - File: `web/src/app/features/settings/gtd/gtd-settings.service.ts:64-91` (also `:117-147` for areas)
    - Category: Correctness
    - Problem: The reassignment uses `GET /api/v1/tasks?context=<id>` for open and done tasks. According to api/openapi.yaml (`listTasks` description), that endpoint leaves out tasks in a tickler (`ticklerDate` after today) and tasks in the Trash. These tasks are never patched to the replacement context. The following `DELETE /contexts/{id}` then removes the context from them on the server ("it is removed from its tasks"). The dialog count leaves them out too. The same happens to trashed projects when an area is deleted with "Move them to another area", because `listProjects` leaves out the Trash.
    - Why it matters: The user picks "Give them another context instead", but some tasks silently end up with no context. The user cannot see this until the tickler comes due. The ticklered tasks are exactly the ones a user is not looking at, so the loss is unlikely to be noticed. A restored trashed task also comes back without the context.
    - Suggested fix: Do the reassignment on the server in one transaction, for example `DELETE /contexts/{id}?replaceWith=<id>` and `DELETE /areas/{id}?moveTo=<id>`. That covers hidden and trashed items and also removes the partial-failure window. It is a server/API change outside this task's `touches`, so add a follow-up task. Until then, record the limitation with `tasks.py assume`, and/or have the dialog say that tasks hidden in a tickler or in the Trash only lose the context.

## P3 Low

- [ ] **CR-2 | A failed reorder overwrites a rename or color change made while it was running**
    - File: `web/src/app/features/settings/gtd/gtd-settings.ts:153-164` (with `gtd-settings.html:42-59`, `:196-206`)
    - Category: Correctness
    - Problem: `reorder` saves a snapshot `list` before the request. On failure it calls `setList(kind, saved ?? list)`. The name inputs and color pickers are not disabled while `busy` is set, so a rename or color change can finish while the reorder is still running and call `replace(...)`. A failed reorder then puts back the old snapshot. The UI shows the old name or color and keeps the old `version`, so the next edit of that item gets a 412.
    - Why it matters: A saved change looks reverted, and the next edit gets a "changed elsewhere" error. This needs a fast user plus a failing reorder, so the impact is small.
    - Suggested fix: On failure, restore only the order and keep the current items. For example, sort the current `this.list(kind)` by the ids of the old snapshot. Or disable the row inputs while the list is `busy`.

- [ ] **CR-3 | A failed reassignment blames the list and reloads the wrong data**
    - File: `web/src/app/features/settings/gtd/gtd-settings.ts:269-281`, `:312-318`
    - Category: Quality
    - Problem: During delete-with-reassignment, a 404 or 412 from a task or project `PATCH` (the task was edited elsewhere meanwhile) goes through `save()`. It shows "It was changed elsewhere in the meantime; the list now shows the current state." and reloads the contexts or areas list, which has not changed. Some items may already be moved, and the context or area still exists.
    - Why it matters: The message is misleading, and the reload does nothing useful. A retry does work, because it collects the remaining tasks again with fresh versions, so this is only a UX issue.
    - Suggested fix: Tell the two cases apart. For a failure during reassignment, show for example "Some tasks changed meanwhile; nothing was deleted. Try again." Or let CR-1's server-side reassignment make this case go away.

## Review conclusion

Material issues found; fix before completing the task. CR-1 needs a decision. Fix it, or record it as an assumption and create a follow-up server task. CR-2 and CR-3 are minor.
