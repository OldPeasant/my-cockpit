# Code Review

Date: 2026-10-22

Task: p5-smoke - Phase 5 demo script

Scope: re-review on the final code. Committed changes of task p5-smoke, 769a79ea93bf64c0eb175f7323aed4c3d9dc2200..6a51d132432ebfa61649728d8c6d74b786182e53. The focus is the changes since the first review: the test-writer's tightening (1e73867fa, which is eee409f30 after a rebase) and the fixer's CR-1..CR-3 fixes (6a51d1324), in web/e2e/phase5/demo.spec.ts and docs/ARCHITECTURE.md. The code reviewed first at 7542b1ed8 matches its rebased commit f5cb540a8: `git diff 7542b1ed8 f5cb540a8` touches only tasks/. The working tree was clean. E2E tests were not run in this review, as the parent asked.

Reviewed revision: 6a51d132432ebfa61649728d8c6d74b786182e53

Requirements:
- tasks/p5-smoke.md (acceptance criterion and the recorded assumptions, including the corrected step-6 assumption)
- SPEC.md §13.2 Phase 5, steps 1-6
- AGENTS.md, docs/ARCHITECTURE.md (e2e section, environment table), docs/QUALITY.md
- reviews/p5-smoke/tests.md and reviews/p5-smoke/fixes.md
- web/src/app/features/mail/compose/mail-compose-page.{ts,html} (save status), server mail/compose/DraftService.kt (send resyncs Sent), web/playwright.config.ts (timeouts)

Git range:
- Branch: main
- HEAD: 6a51d132432ebfa61649728d8c6d74b786182e53
- Base/start: 769a79ea93bf64c0eb175f7323aed4c3d9dc2200
- Commits reviewed: 4 task code commits (2f08c9d17, f5cb540a8, 1e73867fa, 6a51d1324), plus heartbeat/assume commits that touch only tasks/

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Status of earlier findings

- [x] **CR-1 (P2) | Step 6 forced a mail sync the server already does after Send.** Resolved. In step 6 (demo.spec.ts:317-322), the `syncMail` call and its helper are gone, and no `/api/test/mail/sync` call is left in web/e2e/phase5. The comment now describes the product correctly: `DraftService.send` resyncs Sent. The spec opens the reply from the cockpit's Sent folder with no hook, so it now covers the post-send resync. tasks/p5-smoke.md has a correction note for the step-6 assumption. The original wrong line is still there above it, but the correction says what changed. This is acceptable because humans review assumptions.
- [x] **CR-2 (P3) | E2E_SMTP_PORT missing from docs/ARCHITECTURE.md.** Resolved. It is now in the environment table (docs/ARCHITECTURE.md:106) and pinned to 18093 in the manual compose example (docs/ARCHITECTURE.md:178). That port does not collide with the other pinned ports, 18090-18092.
- [x] **CR-3 (P3) | The "Draft saved" check was satisfied before any edit, and the draft text was not checked.** Resolved. The status assertion alone still matches the status from before the edit, because `apply` also sets `saved` on creation. But the spec now polls GreenMail Drafts until exactly one reply draft exists, then asserts it contains `Here are the numbers ${run}.` and the attachment (demo.spec.ts:252-267). The draft created at the start has no typed text, so this check really shows that the edit was stored. It is race-free: `pickFile` stores the content and the attachment synchronously before `.attachments` updates, and the poll tolerates the transient append/delete of draft versions. The fixer was right not to assert "Unsaved changes" right after typing, because the 10 s autosave can fire in that window.

## Review of the new changes

- Step 4 sanitization (`frameMarkup`, demo.spec.ts:435-449): it counts script elements, `on*` attributes and the script's source marker in the frame body, before and after "Load images". The existing `frameFlags`/`frameImages` helpers already read `contentDocument`, so the frame is same-origin readable and the helper does not return the -1 fallback by accident. If it did, `toEqual({scripts: 0, ...})` would fail loudly instead of passing.
- Step 4 direct-fetch listener (demo.spec.ts:195-225): page-level `request` events include iframe subresources. The proxy route is `/api/v1/mail/image-proxy`, so stubbed proxy requests do not match `https://images.example.test/`. The listener is removed with `page.off` on success. On failure the shared page stays dirty, but the serial describe stops at that point anyway.
- Step 5 Sent content (demo.spec.ts:288-294): Sent is appended before the send response (earlier review), so the immediate fetch is race-free. The text has no non-ASCII characters and the lines are short, so the raw MIME contains it literally. The peer-mailbox check at line 283 already relied on the same property.
- Flakiness: no new fixed sleeps. Every new wait is a Playwright auto-retrying assertion or an `expect.poll` with an explicit 30 s timeout, within the 30 s test timeout and the 10 s expect timeout (web/playwright.config.ts:18-19). I found no new order-dependence across kept-stack reruns: everything is still keyed by `run`.
- `APIRequestContext` is still imported and used by `apiOf`. No dead code is left from the removed helper.

Evidence note for the parent, not a finding: `python tasks.py verify p5-smoke` still shows criterion 1 unverified. The fixer reports `npm --prefix web run e2e -- --project=phase-demos` -> 30 passed (phases 0, 1, 2, 4, 5) on this code. Record that run, naming the command and its result, before `done`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found. All three earlier findings (CR-1, CR-2, CR-3) are fixed at 6a51d132432ebfa61649728d8c6d74b786182e53. The test-writer's tightenings are correct, and I found no new flakiness risk.
