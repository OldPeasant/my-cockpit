# Code Review

Date: 2026-10-25

Task: web-mail-contacts-autocomplete - Mail compose: contacts autocomplete

Scope: `git diff ef381c761..d68acec38` (web/src/app/features/mail/compose), committed changes only

Requirements:
- tasks/web-mail-contacts-autocomplete.md acceptance criteria
- SPEC.md §7 (line 402: "Recipient autocomplete from Contacts + recent correspondents")
- api/openapi.yaml `GET /api/v1/contacts/autocomplete` (operationId `autocompleteContacts`, `q` required, `limit` 1-50) and `ContactSuggestionDto` (`contactId`, `email`, `name`)
- docs/ARCHITECTURE.md (stack, i18n, conventions), AGENTS.md project notes, web/.prettierrc

Git range:
- Branch: main
- HEAD: d68acec387f4092641115741ea04bf7c5e43630a
- Base/start: ef381c76119c78d9a371ada7f632edec3cc2e86f
- Commits reviewed: 1 (d68acec38)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Review notes

- AC1: `MailComposeService.suggestions` now calls the generated `autocompleteContacts` function (path `/api/v1/contacts/autocomplete`, GET) with `q` and `limit: SUGGESTION_LIMIT` (8, within 1-50), via the generated `Api` client and with `silent()` errors, matching the existing service conventions. The `ContactSuggestionDto` mapping is correct: `email` -> `address` (trimmed, entries without an address are dropped as the contract allows `email: null` for a contact without address), `name ?? ''`, and `contactId` is kept only when set. The server's merged order (contacts first, then correspondents) is preserved, and repeated addresses are deduplicated case-insensitively, keeping the first entry. A blank query never reaches the service because `RecipientInput.typed` returns early, which fits the endpoint's required `q`.
- The component marks contact suggestions with a decorative SVG (`aria-hidden`) and a visually hidden, i18n-tagged "Contact:" label. `choose` drops `contactId` before adding the chip, so the `Recipient` model and the draft payload stay unchanged. `track suggestion.address` stays unique because the service deduplicates.
- Out-of-order results are still handled by the existing `seq` guard, and a failed lookup clears the suggestions. Neither changed.
- AC2: the service specs cover the merged order, dropped and blank addresses, and duplicates. The component spec covers the contact marker and choosing a contact. I ran `npx ng test --watch=false --include='src/app/features/mail/compose/**/*.spec.ts'` -> 5 files, 119 tests passed (the working tree also had an uncommitted test-writer edit to `mail-compose.service.spec.ts`, so the run included it). I also ran `npx eslint src/app/features/mail/compose` -> clean and `npx prettier --check src/app/features/mail/compose` -> clean. I did not run the full `npm run check` in this review; the parent worker must record that evidence.
- No other code uses `RecipientSuggestion` or the old correspondents call, so the change stays within the task's `touches`. It adds no security-relevant surface: there is no new HTML injection (interpolation only) and no logging.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Component doc comment line exceeds the 100-column project width**
    - File: `web/src/app/features/mail/compose/recipient-input.ts:59`
    - Category: Quality
    - Problem: The edited class doc comment joins two sentences on one 152-character line ("typing: contacts (marked with a person icon) first, ... so they can be fixed."). Prettier does not reflow comments, so the check does not catch it.
    - Why it matters: It is inconsistent with `printWidth: 100` and the wrapped comments around it, and it makes the text harder to read in diffs.
    - Suggested fix: Wrap the comment at 100 columns, as the previous version did.

## Review conclusion

No material issues found.
