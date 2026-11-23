---
title: Web: Tickler as list choice, 'new today' marker in Next
status: todo
owner:
deps: []
touches: [web/src/app/features/gtd/task-detail,web/src/app/features/gtd/next,web/src/app/features/gtd/inbox,web/src/locale]
prio: 2
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
- [ ] Task edit form (`features/gtd/task-detail`): the List selector offers Inbox, Next, Waiting For, Scheduled,
      **Tickler**, Someday. Choosing Tickler shows a required date input (future date, same validation as before)
      and saves `list = INBOX` + that `ticklerDate` through the existing move API. The separate Tickler field is gone
- [ ] A task with a `ticklerDate` opens with Tickler selected and its date shown, whatever its stored list is.
      Choosing another list clears the tickler date; Scheduled still asks for its date. Saving without changes
      doesn't move or change the task
- [ ] One name everywhere in the web app: "Tickler" (EN) / "Wiedervorlage" (DE) in the form, task detail and Inbox
      processing, with the hint "Hidden until then; on that day it comes back to the Inbox." (and German)
- [ ] Next Actions view (`features/gtd/next`): open NEXT tasks with `releasedOn` == today (user's local date) show a
      "New today" / "Neu heute" badge (text, not color only) and are listed first, in their normal order among
      themselves; from the next day on they show and sort normally. Filters and presets still apply
- [ ] New strings are in `messages.xlf` and `messages.de.xlf`; unit tests cover the criteria above; `cd web && npm run check` passes
