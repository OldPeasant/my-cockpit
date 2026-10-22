# Code Review

Date: 2026-10-22

Task: web-contacts - Contacts UI

Scope: committed task changes `git diff 0727115b..HEAD -- web/` (contacts feature folder, `web/e2e/contacts/contacts.spec.ts`, one line in `web/src/app/app.spec.ts`)

Requirements:
- tasks/web-contacts.md acceptance criteria and Notes (assumptions)
- SPEC.md §8.3 Contacts
- api/openapi.yaml (`/api/v1/contacts*`, `/api/v1/oauth/connections`, `OAuthConnectionResponse`)
- AGENTS.md "Project notes", docs/ARCHITECTURE.md conventions
- Sibling features for conventions (bookmarks, calendar deep links, gtd/projects markdown)
- Server behaviour for PATCH semantics: `server/.../contacts/domain/ContactService.kt`

Git range:
- Branch: main (local clone of the claimed task)
- HEAD: 5c3a3052d9fd6961e49f77f316ed715613e9a928
- Base/start: 0727115b9ba3c778fd4107dc6289d5e8d1c464d4
- Commits reviewed: 4 (71958e54, d59975d5, 4e48e229, 5c3a3052); no uncommitted changes

Summary: 0 P0, 1 P1, 2 P2, 2 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | The e2e spec in criterion 3 needs unmerged work and the task does not depend on it**
    - File: `web/e2e/contacts/contacts.spec.ts:69`, `tasks/web-contacts.md:5`
    - Category: Integration
    - Problem: The spec's last step checks a "Birthdays" calendar entry (`${name} (37)` on 2027-03-14). On this branch and on main, nothing implements the birthdays calendar. `tasks/contacts-birthdays.md` is still `in_progress` (owner agent-2), and the server has no birthdays code under `contacts/`. The task's `deps` are `[p0-web-shell, contacts-aggregate, web-links-panel, p0-e2e-harness]`, which leaves out `contacts-birthdays`. The recorded evidence ran on a scratch worktree merged with `origin/wip/contacts-birthdays`, not on the stack this task would ship. The assumption in the Notes admits that "the spec fails on main until contacts-birthdays is done".
    - Why it matters: Criterion 3 says the spec passes against the e2e stack. If the task is completed now, main gets an e2e spec that fails, and nothing on the board orders the two tasks. The evidence was gathered on a different configuration from the one being delivered.
    - Suggested fix: Make the task depend on `contacts-birthdays` and finish it only after that task is on main, then re-run the spec on the merged main. Or hold the task until then. Do not record criterion 3 as verified with evidence from the wip merge.

## P2 Medium

- [ ] **CR-2 | Editing a name never updates a derived display name**
    - File: `web/src/app/features/contacts/contact-form-model.ts:39` and `:74-80`, `web/src/app/features/contacts/contact-form.html:25-35`
    - Category: Correctness
    - Problem: The edit form fills "Shown as" with `contact.displayName`. `toRequest(form, true)` always sends that value back. The server (`ContactService.apply`, lines 164-165) derives a new display name only when `displayName` is cleared. So if a contact's name was derived ("Ada Example") and the user changes the last name to "Lovelace", the request still contains `displayName: "Ada Example"`. The list, the heading, search and autocomplete keep the old name. The placeholder "Leave empty to use the name" only shows once the user empties the field by hand, so the user has no hint about this.
    - Why it matters: A normal rename edit leaves the contact under its old name everywhere. The user sees this as the edit not working.
    - Suggested fix: Remember whether the display name was derived when the form was filled (it equals the name built from given/family name, organization or email). If it was and the user did not touch "Shown as", send `null` so the server derives it again. Or leave the field empty when it equals the derived name. Add a form-model test for the rename case.

- [ ] **CR-3 | After a save conflict, the user's edits are replaced without warning and a second Save stores nothing**
    - File: `web/src/app/features/contacts/contact-detail.ts:182` and `:207-212`, `web/src/app/features/contacts/contact-form.ts:48-56`
    - Category: Correctness
    - Problem: On a 409/412, `reloadContact()` sets `contact` to the server version. The form's effect then runs `form.set(toForm(contact))` and discards everything the user typed. The only feedback is "The contact could not be saved: <detail>". If the user presses Save again, the request sends the server version with the new If-Match, so it succeeds without the user's change. The unit test `keeps editing after a conflict...` (`contact-detail.spec.ts:278-300`) shows this: the retry succeeds, but the test never checks that the typed title "Engineer" is still in the request body. It is not.
    - Why it matters: The user loses their input without noticing (the retry reports success), on exactly the path optimistic locking is meant to protect.
    - Suggested fix: Keep the user's form values and take only the new version for the next If-Match. For example, the page keeps the reloaded version separately and the form does not refill on that reload. Or at least tell the user plainly that their changes were replaced by the current version. Make the test check the retry body.

## P3 Low

- [ ] **CR-4 | Upcoming event links open the series start, not the listed occurrence**
    - File: `web/src/app/features/contacts/contact-display.ts:69-72`
    - Category: Correctness
    - Problem: `eventLink` drops `occurrence` and links to `/calendar/events/{eventId}`. That redirects to `/calendar?event=id`, and the calendar resolves it to the event's own first occurrence (`calendar-view.ts:261-273`, `occurrenceOfEvent(await eventService.get(id))`). For a recurring meeting in the "Upcoming" list, the link jumps to the date the series started, possibly months ago. The doc comment ("the calendar opens it at its date") does not match this.
    - Why it matters: A misleading jump from the detail page, and the comment does not describe the actual behaviour.
    - Suggested fix: Pass the occurrence date if the calendar supports it (e.g. `/calendar?date=<occurrence date>&event=<id>`). Otherwise correct the comment and record the limitation.

- [ ] **CR-5 | The sync label can render empty parentheses**
    - File: `web/src/app/features/contacts/contact-detail.html:38-40`, `web/src/app/features/contacts/contacts-page.html:40`
    - Category: Quality
    - Problem: `OAuthConnectionResponse.email` and `displayName` are both optional in `api/openapi.yaml` (only `createdAt, id, provider, scopes, status, version` are required). When neither is set, the detail shows "Synced with Google ()" and the list shows an empty `.who` span.
    - Why it matters: A small presentation defect for accounts whose provider shares no email address.
    - Suggested fix: Leave out the parenthesised part (and the list span) when both values are missing.

## Review conclusion

Material issues found; fix before completing the task.

Notes on areas checked without findings:
- PATCH null semantics: emptied fields are sent as `null` and lists as `[]`, matching the spec text.
- If-Match is quoted from `version` on PATCH and DELETE.
- vCard import is sent as multipart with part `file`.
- Export uses `exportContacts.PATH` with repeated `ids`.
- Notes are rendered through the escaping `renderMarkdown` (same helper the bookmarks and files features use) plus Angular's sanitizer.
- `mailto:` and `tel:` hrefs are built from fixed prefixes.
- List and detail loads are guarded by sequence numbers.
- All errors leave a retry path.
- i18n ids are set on all user-facing strings.
- Routes `/tasks/:id`, `/mail/messages/:id`, `/calendar/events/:id` and `/settings/integrations` exist.
- Vitest specs cover every detail section (fields, waiting for, events, mails, sync status, related error and retry, edit, create, delete, change events).
