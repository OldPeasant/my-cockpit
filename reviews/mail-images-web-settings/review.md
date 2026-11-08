# Code Review

Date: 2026-11-08

Task: mail-images-web-settings - Web mail images follow the synced image settings

Scope: the committed task changes `git diff 20422f99a..HEAD`. These are the task commits 16a83eda8, e33e622c9 and 2572a9f91, rebased from 8e96e6038, 055a84c7b and b106f5a06. `git diff b106f5a06 2572a9f91 -- web` is empty, so the code is the same. The review skipped the PNG screenshots, and also skipped the uncommitted spec changes the test-writer had in the working tree during the review.

Requirements:
- tasks/mail-images-web-settings.md (acceptance criteria 1-6, notes, the 3 recorded assumptions)
- SPEC.md §4.1, §4.7 (mailImagePolicy / mailImageAllowedSenders)
- AGENTS.md project notes, docs/ARCHITECTURE.md (i18n workflow, conventions)
- Server contract: server/.../core/settings/domain/SettingDefinition.kt, api/SettingsSchemas.kt (TextList: distinct, non-blank, max 1000 x 320)

Git range:
- Branch: main
- HEAD: 26b40f11b215c66b813762c1db4cb49f85bc5835 (last task code commit 2572a9f91659d290607f0efbfe64a9bd998110a0)
- Base/start: 20422f99a5de45eb8259d3e7168c3866bf499dac
- Commits reviewed: 3 task commits (the other commits in the range only touch tasks/ or belong to other tasks)

Summary: 0 P0, 0 P1, 1 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A sender change built while the settings are not loaded can replace the whole synced sender list**
    - File: `web/src/app/features/mail/read/image-allowlist.ts:72-77`
    - Category: Correctness
    - Problem: `change()` calls `await this.ready()`. `ready()` swallows a failed settings load and returns. Then `change()` builds the new list from `this.senders()`, which is `[]` while the settings are `null`, and sends `settings.update({ mailImageAllowedSenders: [address] })`. `SettingsService.save()` loads the settings again when none are cached (`this.current() ?? (await this.load())`). If that second load succeeds, for example after a short network blip, the PATCH carries a fresh ETag. So it is accepted, and the stored list becomes just `[address]`, dropping every other entry saved from web or Android. The read view reaches this path when the user clicks "Always load from this sender" after the settings could not be loaded. The test-writer's uncommitted spec "does not replace the stored senders with a list built before the settings were loaded" covers this exact case.
    - Why it matters: `mailImageAllowedSenders` is synced across devices, so this would silently delete the user's allowed senders. The timing window is narrow, but the result is data loss with no error shown.
    - Suggested fix: in `change()`, after `ready()`, stop when `this.settings.settings()` is still `null`. Throw or reject so the caller's existing `.catch` handles it, and never compute a full replacement list from the empty fallback. Keep the regression test.

## P3 Low

- [ ] **CR-2 | With policy "allow", the read view still offers "Stop" for a listed sender, which no longer stops anything**
    - File: `web/src/app/features/mail/read/mail-read-page.ts:333`, `web/src/app/features/mail/read/mail-read-page.html:185`
    - Category: Correctness
    - Problem: under `allowAll`, `loadImages` is set to `true`. If the sender is also in `mailImageAllowedSenders`, the template shows "Images from this sender load automatically. Stop". "Stop" removes the sender, but images keep loading because of the policy.
    - Why it matters: the control looks like it works but does nothing the user can see, which is confusing. It is not a data problem.
    - Suggested fix: show the "allowed sender" row only when `policy() !== 'allow'`, or show different text that points to the setting.

- [ ] **CR-3 | `storedSenders()` doc comment does not match its behaviour**
    - File: `web/src/app/features/mail/read/image-allowlist.ts:126-140`
    - Category: Quality
    - Problem: the comment says it returns `null` "when there is none (or storage is unavailable)". When `localStorage.getItem` throws, the `catch` returns `[]`, not `null`. `migrate()` then calls `removeItem`, which throws again and is caught. The `this.migration = null` reset runs synchronously, before `ready()`'s `??=` stores the promise, so the retry described in the catch comment never happens on that path. The behaviour itself is harmless.
    - Why it matters: future changes to the migration retry logic could be based on the wrong comment.
    - Suggested fix: return `null` from the `catch` when reading fails, or correct the comment.

- [ ] **CR-4 | Evidence for criterion 4 overstates what the Playwright run exercised**
    - File: `web/e2e/mail-images/image-settings.spec.ts:69-72`; recorded evidence for criterion #4 in `tasks/mail-images-web-settings.md`
    - Category: Tests
    - Problem: the evidence says "image proxy upstream stubbed via page.route". In fact `page.route('**/api/v1/mail/image-proxy**')` answers the browser's request to the Cockpit proxy endpoint itself, so the server's `/api/v1/mail/image-proxy` is never called. The run does verify that the read view requests `remoteImages=PROXY`, that the frame renders proxied and `cid:` images, and that the settings flow works. It does not show that the real proxy serves the image. The disclosure exists but describes the stub inaccurately.
    - Why it matters: the task notes ask to fix "Load images" for real mails (proxy errors, CSP). A proxy failure would not show up in this run.
    - Suggested fix: reword the evidence to "the browser request to /api/v1/mail/image-proxy is fulfilled by page.route (no internet)". If possible, point to where the server proxy itself is tested (server tests or the fix-mail-images-firefox evidence).

## Review conclusion

Material issues found; fix before completing the task.

Criteria 1, 2, 3, 5 and 6 are implemented as specified, including the recorded assumptions: domain matching, "Stop" removing both address and domain entries, and a single PROXY load when the policy is "allow". Hand-added xlf units use custom ids that match the templates, with German targets. Moving the spec to the `inbox-exclusive` project is consistent with the other specs that change the shared account. CR-1 should be fixed (and its regression test committed) before `done`. CR-2 to CR-4 are optional cleanups.
