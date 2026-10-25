# Code Review

Date: 2026-10-25

Task: web-mail-contacts-autocomplete - Mail compose: contacts autocomplete

Scope: `git diff ef381c761..e6d8483ac -- web` (web/src/app/features/mail/compose), committed changes only; round 2 on the final code

Requirements:
- tasks/web-mail-contacts-autocomplete.md acceptance criteria
- SPEC.md §7 ("Recipient autocomplete from Contacts + recent correspondents")
- api/openapi.yaml `GET /api/v1/contacts/autocomplete` (operationId `autocompleteContacts`, `q` required, `limit` 1-50) and `ContactSuggestionDto` (`contactId`, `email`, `name`)
- docs/ARCHITECTURE.md, AGENTS.md project notes, web/.prettierrc
- Round 1 reports: reviews/web-mail-contacts-autocomplete/review.md (revision d68acec38), fixes.md, tests.md

Git range:
- Branch: main
- HEAD: e6d8483ac2b5909b466210b09ed44d7259157ef5
- Base/start: ef381c76119c78d9a371ada7f632edec3cc2e86f
- Commits reviewed: 3 (d68acec38, 785a3b3a8, e6d8483ac)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## Review notes

- AC1: `MailComposeService.suggestions` calls the generated `autocompleteContacts` (GET `/api/v1/contacts/autocomplete`) with `q` and `limit: SUGGESTION_LIMIT` through the generated `Api` client with `silent()` errors. The DTO mapping (`email` trimmed to `address`, entries without an address dropped, `name ?? ''`, `contactId` only when set) and the case-insensitive dedupe that keeps the first, best-ranked entry are correct. The server's merged order is kept. A blank query never reaches the service, because `RecipientInput.typed` returns early, which satisfies the required `q`.
- Component: contact suggestions get a decorative `aria-hidden` SVG and a visually hidden, i18n-tagged "Contact:" label. `choose` drops `contactId` before adding the chip, so the `Recipient`/draft model is unchanged. The `seq` guard for out-of-order lookups and the error path that clears suggestions are unchanged and still correct.
- AC2: test-writer added service specs for the merged order, missing, blank and duplicate addresses (also after trimming), an empty or null body, a silent failure that rejects, and that the old correspondents endpoint is no longer called. It also added component specs for the contact marker, its screen-reader label, choosing a contact with and without a name, and hiding contacts that are already recipients. The tests assert behavior through HTTP and the DOM.
- Verification run in this review: `npx ng test --watch=false --include='src/app/features/mail/compose/**/*.spec.ts'` -> 5 files, 126 tests passed. `npx eslint src/app/features/mail/compose` -> clean. `npx prettier --check src/app/features/mail/compose` -> clean. The full `npm run check` was not run here; the parent worker must record that evidence for AC2.
- No new security surface: interpolation only, no logging, and no new endpoints.

## Round 1 findings

- CR-1 (P3, doc comment over 100 columns in `recipient-input.ts`): resolved in e6d8483ac. The class doc comment (lines 56-61) is now wrapped at 100 columns with the wording unchanged.

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
