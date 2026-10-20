---
title: Weekly review wizard
status: todo
owner:
deps: [review-api,web-inbox,web-cal-views,web-gtd-lists]
touches: [web/src/app/features/review]
prio: 2
---
## Goal
Guided weekly review (SPEC.md §8.6).

## Acceptance criteria
- [ ] 8 steps of SPEC.md §8.6 with embedded inbox processing, keep/someday/delete actions, resumable; reflection saved as journal entry when journal exists
- [ ] Vitest component/service tests cover step navigation and resume; `cd web && npm run check` passes
- [ ] Playwright `e2e/review/review.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/review/review.spec.ts`, see p0-e2e-harness): complete a review end to end

## Notes
