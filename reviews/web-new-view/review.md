# Code Review

Date: 2026-10-26

Task: web-new-view - Combined New view (GTD inbox + mail inbox)

Scope: final revision a954b1800021d140a1399c27856d14f115494a99 of the task (base 464e31b760b54e108001f2f5da4c3d6defac7483). This is a re-review. The previous round reviewed c5811bbf (all code commits 1bb76906, 3c747664, 3f14a29a, b975de5e, 2ca73f94 and merge c5811bbf) and found no issues. This round reviews the delta `git diff c5811bbf..a954b180`: b484d22f (one line in web/e2e/new/new.spec.ts) and a954b180 (new web/src/app/features/new/new.routes.spec.ts, two new tests in new-page.spec.ts, and the reviews/web-new-view/tests.md report). Only committed code was reviewed. The working tree was clean.

Requirements:
- tasks/web-new-view.md acceptance criteria 1-4 and the recorded assumptions: nav placement in the Home group, hold-back of older tasks while more mail can load, and keys 1/2/3, `?source=`, j/k/o/Enter/t with a flat unified inbox
- SPEC.md §5.3 "New view"; docs/ARCHITECTURE.md (web conventions, Coverage); AGENTS.md "Project notes"
- The previous review round (c5811bbf) and reviews/web-new-view/tests.md
- What the parent worker supplied: the e2e run result, and the test-writer's `npm run check` result

Git range:
- Branch: main
- HEAD: a954b1800021d140a1399c27856d14f115494a99
- Base/start: 464e31b760b54e108001f2f5da4c3d6defac7483 (delta reviewed from c5811bbfe7f2bfaa4e0e70dc1fe8359843b73917)
- Commits reviewed: 2 new (b484d22f, a954b180), on top of the 6 reviewed in the previous round

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## What was checked

- **Production code unchanged:** `git diff --stat c5811bbf a954b180` lists only web/e2e/new/new.spec.ts, web/src/app/features/new/new-page.spec.ts, web/src/app/features/new/new.routes.spec.ts and two files under reviews/. No file under web/src outside `*.spec.ts`, no server/API file and no generated file changed. The previous round's findings on the production code still apply.
- **b484d22f (e2e race fix):** Before reading the nav labels with `allTextContents()`, which does not wait, the spec now waits with `expect(nav.filter({ hasText: /^\s*New\s*$/ })).toBeVisible()`. This web-first assertion retries, so it fixes the empty-array race without weakening the order check. The anchored regex matches only the "New" link, so strict mode is not violated. The parent reports `npm run e2e -- --project=chromium e2e/new/new.spec.ts` against the podman-compose e2e stack -> 31 passed, including the New view spec.
- **new.routes.spec.ts:** It checks the nav order dashboard/new/inbox, the Home group membership (the recorded assumption), the `/new` path and label, and that the real `routes` lazy-load the New page with title "New", no coming-soon placeholder, and an active nav link with `aria-current="page"`. The Api fake throws on unexpected calls, so the test cannot pass by accident. The test asserts behaviour, not implementation details.
- **new-page.spec.ts additions:** "keeps live rows of the hidden source out of the filtered list" covers SSE updates of both sources while the Mail filter is active and checks that switching to GTD Inbox shows the new task. "holds back a live capture older than the loaded mail until its page loads" covers the hold-back assumption for SSE-added tasks across paging. Both assert visible row order. tests.md says the first failing run of the second test came from a wrong fixture date: the test-writer changed the fixture, not the expectation. I confirmed that the final expectation matches the documented hold-back rule (the old task appears only after mail m4 loads, at its date position). This also resolves the uncommitted failing test noted in the previous round.
- **Test execution (this review):** `cd web && npx ng test --watch=false --include='src/app/features/new/**/*.spec.ts'` -> 3 files, 68 tests passed. The test-writer reports `npm run check` -> pass, 3630 tests, and whole-file coverage of the task's production files in tests.md.
- **Security/data:** The new tests use only fictitious data. No secrets, logging or rendering changes.

## Evidence notes for the parent worker

- `python tasks.py verify web-new-view` shows all four criteria unverified at a954b180. Record the evidence before `done`. For criterion 4, give the actual e2e command and stack (`npm run e2e -- --project=chromium e2e/new/new.spec.ts` against the podman-compose e2e stack -> 31 passed).
- tests.md notes a flaky timeout outside this task's scope: app.spec.ts "builds every navigation section..." hit its 5000 ms limit once under the instrumented coverage run, and this task adds one more section to that loop. It passed in `npm run check`. It is not a finding against this task, but if it recurs in `.taskcheck`, a follow-up task to raise that test's timeout may be needed.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
