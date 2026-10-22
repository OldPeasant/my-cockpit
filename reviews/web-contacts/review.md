# Code Review

Date: 2026-10-22

Task: web-contacts - Contacts UI

Scope: committed task changes `git diff dc313393..c3d041c9` (all six "web-contacts:" commits): `web/src/app/features/contacts/`, `web/e2e/contacts/contacts.spec.ts`, one line in `web/src/app/app.spec.ts`, plus the previous review and fix reports. Re-review after the fixer: checks that CR-2..CR-5 are resolved, and looks for new issues in the final code. Uncommitted spec edits by a concurrent test-writer are not part of this review.

Requirements:
- tasks/web-contacts.md acceptance criteria and Notes (assumptions)
- SPEC.md §8.3 Contacts
- api/openapi.yaml (`/api/v1/contacts*`, `updateContact` 409/412/428, `/api/v1/oauth/connections`)
- AGENTS.md "Project notes", docs/ARCHITECTURE.md conventions
- Server derivation rule `server/.../contacts/domain/ContactService.kt` (`apply`, `derivedName`)
- Calendar deep link behaviour `web/src/app/features/calendar/views/calendar-view.ts` (`openLinked`)
- `tasks/contacts-birthdays.md` on origin/main (status of the dependency)

Git range:
- Branch: main (local clone of the claimed task)
- HEAD: c3d041c976af9c9c016bc9140be91cb736dbcebd
- Base/start: dc3133938a83538e947b7cba21843141de5c17e4 (`git merge-base HEAD origin/main`)
- Commits reviewed: 6 (60341714, 27ade11c, 7d68546c, 85c2008b, c3ed3e4e, c3d041c9)
- Uncommitted changes: `tasks/web-contacts.md` (deps now list `contacts-birthdays`), not yet committed; spec files being edited by the test-writer. Neither is reviewed as code.

Summary: 0 P0, 1 P1, 0 P2, 1 P3

## Status of the previous findings

- CR-1 (P1, e2e birthday step needs `contacts-birthdays`): still open, restated below. The parent handles it.
- CR-2 (derived display name on rename): resolved. `toForm` (`contact-form-model.ts:41-44`) leaves "Shown as" empty when the stored name equals `derivedName()`, so a PATCH sends `displayName: null` and the server derives it again (`ContactService.kt:164-165`). `derivedName` (`contact-form-model.ts:68-76`) matches the server rule: given + family name, else organization, else first email. Absent parts are filtered the same way as `listOfNotNull`. Tests: `contact-form-model.spec.ts:75-110` and `contact-form.spec.ts:190-208` cover rename, own display name, clearing it and the derivation order. The known edge case (a hand-typed name equal to the derived one counts as derived) is harmless: the server derives the same string.
- CR-3 (edits lost after a conflict): resolved. The form refills only when the contact id changes (`contact-form.ts:48-60`). The detail keeps the reloaded version for the next If-Match and shows a dedicated conflict message (`contact-detail.ts:181-186`). The form is inside `@if (editing())` (`contact-detail.html:97`), so a later Edit after Cancel starts from the current version. Tests check that the typed title stays in the input and in the retry body with `If-Match: "3"` (`contact-detail.spec.ts`, conflict test), and that a reload keeps edits while another contact refills (`contact-form.spec.ts:180-230`). The retry overwrites the other side's changes to untouched fields. The message says this ("Save again to replace that version, or Cancel to see it"), so it is a deliberate choice.
- CR-4 (event link opens the series start): resolved as documented. I confirmed that `calendar-view.ts:262-273` opens `occurrenceOfEvent(event)` and has no occurrence deep link. Changing that is outside this task's `touches`. The doc comment at `contact-display.ts:69-73` now describes the real behaviour.
- CR-5 (empty parentheses in the sync label): resolved. The detail falls back to `@@contacts.detail.syncedNoAccountName` (`contact-detail.html:37-45`). The list omits `.who` (`contacts-page.html:40-42`). Both have tests.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Criterion 3's e2e spec depends on `contacts-birthdays`, which is not on main (restated)**
    - File: `web/e2e/contacts/contacts.spec.ts:69-78`, `tasks/web-contacts.md:5`
    - Category: Integration
    - Problem: The last step expects a Birthdays-calendar entry `${name} (37)` on 2027-03-14. On origin/main, `tasks/contacts-birthdays.md` is `status: todo` (released, finalization pending), so nothing on main serves that calendar. The recorded evidence came from a scratch merge with `origin/wip/contacts-birthdays`. The dependency has been added to `tasks/web-contacts.md` in the working tree only, not committed.
    - Why it matters: If this task is completed before `contacts-birthdays` lands, main gets an e2e spec that fails. Criterion 3 would also rest on evidence from a different configuration.
    - Suggested fix: As the parent plans: commit the `contacts-birthdays` dependency. Verify criterion 3 only after that task is on main, by running `npm run e2e -- e2e/contacts/contacts.spec.ts` on the merged main. The spec uses `toContainText`, so the planned title prefix `🎂 ` from `contacts-birthdays` still matches.

## P2 Medium

None.

## P3 Low

- [ ] **CR-6 | If the reload after a conflict fails, the conflict message still says "Save again"**
    - File: `web/src/app/features/contacts/contact-detail.ts:181-186` and `:214-220`
    - Category: Correctness
    - Problem: On 409/412 the page sets the message "...Your changes are kept: Save again to replace that version..." and then calls `reloadContact()`. If that GET fails with anything other than 404 (network error, 5xx), the error is swallowed and `contact` keeps the stale version. Every following Save sends the old If-Match, gets 412, and shows the same message again. The user never sees the reload failure.
    - Why it matters: A small resilience gap. No data is lost (the edits stay in the form), but the "Save again" advice cannot succeed until the page is reloaded.
    - Suggested fix: When the reload fails for another reason, show `message(error)` (or a "could not load the current version, try again" text) instead of the "Save again" advice. Or retry the reload on the next Save when the previous one failed.

## Review conclusion

Material issues found; fix before completing the task.

The only material item is CR-1, which concerns dependencies and ordering, not code. CR-2..CR-5 are resolved correctly. CR-6 is optional polish.

Areas re-checked without findings: request identity on the list and detail loads (`loadSeq`); `reloadQuietly` / `loadMore` interplay; the change event handling (DELETED while editing, no refresh while editing); If-Match quoting on PATCH and DELETE; multipart vCard import; export URL built from `exportContacts.PATH`; Markdown notes rendered through the escaping `renderMarkdown`; `mailto:`/`tel:` hrefs built from fixed prefixes; i18n ids on the new strings; routes `/contacts`, `/contacts/new` and `/contacts/:id`; the `app.spec.ts` placeholder list updated for `/contacts`.
