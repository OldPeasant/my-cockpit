---
title: Inbox/task detail: to reference
status: in_progress
owner: dell26/agent-1
deps: [web-inbox,web-task-detail,gtd-convert-note]
touches: [web/src/app/features/gtd/inbox,web/src/app/features/gtd/task-detail]
prio: 2
lease_until: 2026-10-21T07:07:56Z
extended: 
releases: 1
---
## Goal
Enable 'To reference (note)' in processing and task detail.

## Acceptance criteria
- [x] Action converts and navigates/offers to open the note
  - verified (2026-10-21 08:34+0200 by dell26/agent-1): cd web && npm run test:ci -- --include=gtd/inbox/inbox-page.spec.ts --include=gtd/task-detail/task-detail.spec.ts --include=gtd/data/task-store.spec.ts -> 3 files / 183 tests passed; 'To reference' describes check convert API call, task detail router.navigate(['/notes', id]) after confirm, inbox 'Open note' link href /notes/<id>. The /notes/:id page itself comes with web-notes-editor (still in progress, not on main), so opening the note end to end is not shown (see assumption, review CR-2)
- [x] Vitest component/service tests cover the action calls convert and updates the inbox; `cd web && npm run check` passes
  - verified (2026-10-21 08:34+0200 by dell26/agent-1): cd web && npm run check (lint + vitest + build) on HEAD merged with origin/main -> PASS, 91 files / 1750 tests passed; inbox-page/task-detail/task-store specs cover convert call and inbox list update

## Notes
- ASSUMPTION (2026-10-21 07:01+0200 by dell26/agent-3): Inbox processing does not navigate away after 'To reference': it shows a status line with an 'Open note' link (/notes/:id) so processing can continue; the task detail navigates straight to /notes/:id after a confirm. The /notes/:id route comes with web-notes-editor.
- ASSUMPTION (2026-10-21 07:01+0200 by dell26/agent-3): Added TaskStore.convertToNote in gtd/data (outside touches) so the inbox item leaves the list optimistically like trash; inline clarification changes are not saved before converting (a note has no GTD attributes).
- ASSUMPTION (2026-10-21 07:43+0200 by dell26/agent-3): Completed before web-notes-editor (in progress) adds the /notes/:id route: until then 'Open note' and the task-detail navigation land on Not Found. Convert + navigation target are verified via unit tests (router/link), not end to end.
- extended by dell26/agent-3: Implementation + 2 review rounds done, CR-5/CR-6 fixed in 331a24a6, web npm run check passes. Left: re-run test-writer and code-reviewer on final code (fixes changed code after review-2), tasks.py verify both criteria, write reviews/web-gtd-convert-note/quality.json, run done.
- ASSUMPTION (2026-10-21 08:04+0200 by dell26/agent-3): Review CR-7 (P3, one spec line in task-store.spec.ts not Prettier-wrapped) left as is: npm run check does not enforce Prettier and reformatting after the final review would make the review evidence stale; a later format pass can fix it.
- release by dell26/agent-3: Implementation, 3 test-writer and 3 code-reviewer rounds done and committed (final review.md on 0ec8b9eb-equivalent code: only CR-7 P3 Prettier nit, accepted via assumption). Code unchanged since review round 3 (HEAD 0eba6c1e). Left: (1) tasks.py verify both criteria from tests.md/review.md evidence (web vitest: 83 files / 1557 tests passed in scripts/check.sh this session); (2) write+commit reviews/web-gtd-convert-note/quality.json with reviewed_revision = current HEAD hash, findings CR-1..CR-7 with dispositions (CR-3..CR-6 fixed, CR-7 accepted); (3) run sh scripts/check.sh to completion (it ran >10 min here under load: web tests passed, killed during server/android build) and tasks.py done.
