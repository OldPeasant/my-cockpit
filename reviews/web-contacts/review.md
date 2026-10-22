# Code Review

Date: 2026-10-22

Task: web-contacts - Contacts UI

Scope: final revision, `git diff 1f6a57005..HEAD` (task commits 13df9e2eb..6042bedc3 merged in ebc35ae6c): `web/src/app/features/contacts/`, `web/e2e/contacts/contacts.spec.ts`, one line in `web/src/app/app.spec.ts`, plus earlier review and fix reports. Production code was reviewed at HEAD. Test files a concurrent test-writer may add later are not part of this review.

Reviewed revision: ebc35ae6c159616eeb2b6426648a575e6d7eb6e8

Requirements:
- tasks/web-contacts.md acceptance criteria and Notes (assumptions)
- SPEC.md §8.3 Contacts
- AGENTS.md "Project notes", docs/ARCHITECTURE.md conventions
- api/openapi.yaml (contacts operations, `birthday` as `format: date`), `ContactController.kt` (If-Match / ETag handling)
- Earlier rounds: previous reviews/web-contacts/review.md and reviews/web-contacts/fixes.md

Git range:
- Branch: main (local clone of the claimed task)
- HEAD: ebc35ae6c159616eeb2b6426648a575e6d7eb6e8
- Base/start: 1f6a57005 (claim commit)
- Commits reviewed: 7 task commits (13df9e2eb, e3c67bdfd, 630a2f1f8, 26fbebb03, 3a3dd131f, fc3ad426d, 6042bedc3) and the merge ebc35ae6c
- Uncommitted changes: none at review start

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## Status of earlier findings

- CR-1 (P1, criterion 3 needed `contacts-birthdays` on main): resolved. `contacts-birthdays` is a dependency in `tasks/web-contacts.md:5` and is done on main. The parent reports `npm --prefix web run e2e -- e2e/contacts/contacts.spec.ts` against the e2e stack -> 1 passed, on main with the dependency merged. The spec (`web/e2e/contacts/contacts.spec.ts`) imports a vCard through the real file chooser, checks list, search, detail, export and the Birthdays-calendar entry `${name} (37)` on 2027-03-14. That matches criterion 3. The parent still has to record this with `tasks.py verify` (no criterion has evidence recorded yet).
- CR-2..CR-5: resolved in the previous round. I re-checked them in the code at HEAD: `toForm`/`derivedName` (`contact-form-model.ts:41-76`), id-keyed form refill (`contact-form.ts:48-60`), corrected doc comment of `eventLink` (`contact-display.ts:69-76`), and the sync label without empty parentheses (`contact-detail.html:37-45`, `contacts-page.html:40-42`).
- CR-6 (P3, misleading "Save again" message when the conflict reload fails): not changed. The code is the same, and a test now pins the behaviour (`contact-detail.spec.ts:364`). Restated as CR-1 below, so the parent can decide on it.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | If the reload after a conflict fails, the message still says "Save again"**
    - File: `web/src/app/features/contacts/contact-detail.ts:181-186`, `:214-220`
    - Category: Correctness
    - Problem: On 409/412 the page shows "...Save again to replace that version..." and calls `reloadContact()`. If that GET fails with anything other than 404, the error is swallowed and `contact` keeps the stale version. Each later Save sends the old If-Match, gets 412 and shows the same advice again.
    - Why it matters: No data is lost (the edits stay in the form), but the advice cannot work until the page is reloaded, and the user does not see why.
    - Suggested fix: When the reload fails for another reason, show `message(error)` or a "could not load the current version" text instead of the "Save again" advice. Or reload again before the next Save.

- [ ] **CR-2 | A save or conflict reload that finishes after a route change writes the previous contact into the page**
    - File: `web/src/app/features/contacts/contact-detail.ts:177-179`, `:216`
    - Category: Correctness
    - Problem: `load()` guards its writes with `loadSeq`, but `save()` (`this.contact.set(await this.service.update(...))`) and `reloadContact()` do not. The component is reused for `/contacts/:id` -> `/contacts/:otherId` (same route config). An example is a CONTACT link in the linked-items panel that is clicked while a save is running. If the PATCH or the GET of the old contact resolves after `load()` for the new id, the old contact is shown under the new route. `ref()` and `exportUrl()` follow the route id, so the header and the linked items or export then belong to different contacts. A following Edit would PATCH the old contact.
    - Why it matters: Rare, because it needs a navigation during a request in flight. The result is a page that mixes two contacts.
    - Suggested fix: Capture `const seq = this.loadSeq` before the request in `save()` and `reloadContact()`, and skip the `contact`/`editing`/`loadError` writes when `seq !== this.loadSeq`. That is the pattern `load()` and `changedElsewhere()` already use.

## Review conclusion

No material issues found.

The criteria are covered:
- Criterion 1: list with debounced search in `?q=` and request identity; detail with Waiting For, upcoming events, recent mails and linked items; inline edit with quoted If-Match and conflict handling; vCard import (multipart) and export (URL from `exportContacts.PATH`); Google/MS sync status from OAuth connections, as the recorded assumption describes.
- Criterion 2: Vitest specs cover each detail section, the sync states, edit/conflict/create/delete, and out-of-order loads. The parent reports that `npm --prefix web run check` passes.
- Criterion 3: see CR-1 of the earlier round above.

Security checks found nothing. Notes Markdown goes through the escaping `renderMarkdown`. `mailto:` and `tel:` hrefs use fixed prefixes, and `telUrl` keeps only digits and `+`. Export ids are URL-encoded. Nothing is logged.

Conventions are followed: the feature stays in its folder, it uses the generated API client, error handling and i18n ids follow the other web features, and errors are silent with inline display, as the service documents. The two P3 items are optional polish.
