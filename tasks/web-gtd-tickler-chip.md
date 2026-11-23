---
title: Web: Tickler as list choice, 'new today' marker in Next
status: in_progress
owner: felix/agent-1
deps: []
touches: [web/src/app/features/gtd/task-detail,web/src/app/features/gtd/next,web/src/app/features/gtd/inbox,web/src/locale]
prio: 2
lease_until: 2026-11-23T18:35:02Z
---
## Goal
The web client gets the same change as Android task `and-gtd-tickler-chip` (read its Goal for the background):
1. In the task edit form, **Tickler** becomes a choice in the List selector instead of a separate date field. A tickler date
   overrides the list (hidden until the date, then the server moves the task to the Inbox and clears the scheduled date and
   waiting-for fields), so the form must not let you combine it with another list.
2. Scheduled tasks moved to Next on their date (`releasedOn` == today, already in the API) are marked **"new today"** in
   the Next Actions view and listed first, so they don't get lost in a long Next list.

Server and API stay unchanged. SPEC.md is updated by `and-gtd-tickler-chip`; don't edit it here.

## Acceptance criteria
- [x] Task edit form (`features/gtd/task-detail`): the List selector offers Inbox, Next, Waiting For, Scheduled,
      **Tickler**, Someday. Choosing Tickler shows a required date input (future date, same validation as before)
      and saves `list = INBOX` + that `ticklerDate` through the existing move API. The separate Tickler field is gone
  - verified (2026-11-23 18:39+0100 by felix/agent-1): cd web && npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts' --include='src/locale/**/*.spec.ts' -> 1009 passed (task-detail.spec 'offers Tickler among the lists, without a separate tickler field', 'moves the task to the Inbox with its tickler date' -> move body {list:INBOX,ticklerDate}, 'needs a tickler date in the future')
- [x] A task with a `ticklerDate` opens with Tickler selected and its date shown, whatever its stored list is.
      Choosing another list clears the tickler date; Scheduled still asks for its date. Saving without changes
      doesn't move or change the task
  - verified (2026-11-23 18:39+0100 by felix/agent-1): cd web && npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts' --include='src/locale/**/*.spec.ts' -> 1009 passed (task-detail.spec 'opens a task with a tickler as Tickler, whatever its list, and saves nothing unchanged', 'clears the tickler when another list is chosen; Scheduled asks for its date'; task-draft.spec moveFor/chooseList cases)
- [x] One name everywhere in the web app: "Tickler" (EN) / "Wiedervorlage" (DE) in the form, task detail and Inbox
      processing, with the hint "Hidden until then; on that day it comes back to the Inbox." (and German)
  - verified (2026-11-23 18:39+0100 by felix/agent-1): cd web && npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts' --include='src/locale/**/*.spec.ts' -> 1009 passed (form option/label 'Tickler'+'Tickler date', inbox-page.spec 'T puts the item in a tickler' asserts 'Tickler date' + hint; messages.de.xlf: Wiedervorlage / Wiedervorlage am / hint DE; locale messages-de.spec passes)
- [x] Next Actions view (`features/gtd/next`): open NEXT tasks with `releasedOn` == today (user's local date) show a
      "New today" / "Neu heute" badge (text, not color only) and are listed first, in their normal order among
      themselves; from the next day on they show and sort normally. Filters and presets still apply
  - verified (2026-11-23 18:40+0100 by felix/agent-1): cd web && npx ng test --watch=false --include='src/app/features/gtd/**/*.spec.ts' --include='src/locale/**/*.spec.ts' -> 1009 passed (next-view.spec 'new today': badge text 'New today' + first in stable order, with context filter; isNewToday/newTodayFirst next day sorts normally)
- [x] New strings are in `messages.xlf` and `messages.de.xlf`; unit tests cover the criteria above; `cd web && npm run check` passes
  - verified (2026-11-23 18:40+0100 by felix/agent-1): npm run i18n:extract; cd web && npm run check -> lint 0 warnings, 199 files / 4995 tests passed, ng build OK
- ASSUMPTION (2026-11-23 18:34+0100 by felix/agent-1): Tickler date input is labeled 'Tickler date'/'Wiedervorlage am' in both the form and Inbox processing (replacing 'Back in the inbox on'), so the feature has one name; the hint text is a shared i18n unit gtd.tickler.hint.
- ASSUMPTION (2026-11-23 18:34+0100 by felix/agent-1): Choosing Tickler again after another list restores the task's stored tickler date (empty for a new tickler); an empty tickler shows the error 'Pick the date it comes back.' and blocks Save. Date input min is tomorrow (validation unchanged: > today unless unchanged).
- ASSUMPTION (2026-11-23 18:34+0100 by felix/agent-1): 'New today' badge rendered in next-view's list item (not in shared TaskRow, which is outside this task's touches), with an accent edge; client-side stable partition puts releasedOn==localDate() first.
