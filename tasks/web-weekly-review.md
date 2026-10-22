---
title: Weekly review wizard
status: in_progress
owner: dell26/agent-2
deps: [review-api,web-inbox,web-cal-views,web-gtd-lists]
touches: [web/src/app/features/review]
prio: 2
lease_until: 2026-10-22T01:40:36Z
---
## Goal
Guided weekly review (SPEC.md §8.6).

## Acceptance criteria
- [ ] 8 steps of SPEC.md §8.6 with embedded inbox processing, keep/someday/delete actions, resumable; reflection saved as journal entry when journal exists
- [ ] Vitest component/service tests cover step navigation and resume; `cd web && npm run check` passes
- [ ] Playwright `e2e/review/review.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/review/review.spec.ts`, see p0-e2e-harness): complete a review end to end

## Notes
- ASSUMPTION (2026-10-22 03:18+0200 by dell26/agent-2): Step 1 'flagged mails': the mail API has no flagged filter, so Get clear embeds the Inbox processing and links to Mail for flagged mails instead of listing them
- ASSUMPTION (2026-10-22 03:18+0200 by dell26/agent-2): Reflection: appended as a '## Weekly review' section to today's journal entry (created if missing, mood/tags kept), saved before the review is completed; the draft is kept in localStorage per review id so a resumed review keeps it
- ASSUMPTION (2026-10-22 03:18+0200 by dell26/agent-2): Keep (stale next actions, someday, projects 'Looks good', waiting 'Still waiting') only removes the item from the step's list; nothing is saved, so a kept stale next action shows again next week
- ASSUMPTION (2026-10-22 03:18+0200 by dell26/agent-2): Calendar steps' quick-add creates the task in the Inbox (plain capture, no syntax parsing); projects step's 'Add' creates a NEXT task in the stalled project and sets it as next step
- ASSUMPTION (2026-10-22 03:18+0200 by dell26/agent-2): e2e/review runs in the Playwright project inbox-exclusive (with workers: 1) since its Get clear step processes the shared inbox to zero; playwright.config.ts edited outside the task's touches for that
