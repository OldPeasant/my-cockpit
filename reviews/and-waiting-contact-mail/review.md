# Code Review

Date: 2026-11-09

Task: and-waiting-contact-mail - Android Draft email: To from the linked waiting-for contact

Scope: final task code, git diff a95fa744f67f90916b6100a637151a8153e8ee80..c48d73bd01a569d40eb6927ee2a8b4b437c93f70, excluding tasks/ and reviews/ (implementation commit f2a077e6a, test-writer commit 6c900fb6b, fix commit c48d73bd0). Re-review after the fixes for CR-1 and CR-2 of the earlier review.

Requirements:
- tasks/and-waiting-contact-mail.md (acceptance criteria and recorded assumption)
- SPEC.md §5.3 (waiting "Follow up" then "Draft email" prefills To from the linked contact)
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- reviews/and-waiting-contact-mail/fixes.md (claimed fixes)
- Web reference web/src/app/features/gtd/lists/waiting-page.ts (contact.emails[0])

Git range:
- Branch: main
- HEAD: c48d73bd01a569d40eb6927ee2a8b4b437c93f70
- Base/start: a95fa744f67f90916b6100a637151a8153e8ee80
- Commits reviewed: 3 code commits (f2a077e6a, 6c900fb6b, c48d73bd0); fae4609b3 and 0d725793d touch only tasks/

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Static review only: Gradle was not run, as instructed. No uncommitted changes were reviewed.

Verified:
- Criterion 1: GtdTask.waitingForContactId (UUID?, default null), CachedTaskEntity.waitingForContactId (String?), the cache mappers (TaskResponse.toEntity, CachedTaskEntity.toDomain) and TaskResponse.toTask carry the field. GtdCacheDatabase version 3 with exported schema 3.json containing the new TEXT column; GtdDataModule uses fallbackToDestructiveMigration(dropAllTables = true), so a v2 cache is dropped and refilled (cache-only data, matches the recorded assumption). The mapper is the only CachedTaskEntity construction site.
- Criterion 2: followUpRecipient takes the linked contact's first email (ContactsResult.Ok, emails.firstOrNull()), otherwise falls back to emailIn(waitingForText); Offline/Failed results and contacts without addresses fall back. ContactsRepository.get goes through load, which maps network/HTTP errors to results, so the button coroutine does not crash on failure. followUpMailUri/followUpMailIntent keep their old default (address from the text), so existing callers are unchanged.
- Earlier CR-1 (double taps open several mails) is fixed: WaitingRoute keeps a remembered per-task set; a tap only launches when the task id is newly added, and the id is removed in finally after the intent was started, the lookup failed, or the scope was cancelled (leaving composition), so later taps work again. All access is on the main thread, so a plain set is adequate. WaitingRouteDraftMailTest "taps while the contact is still loading open a single mail" suspends the lookup on a CompletableDeferred, clicks twice, asserts no intent before completion, exactly one SENDTO after, one lookup, and that a later tap drafts again.
- Earlier CR-2 (firstEmail untested) is resolved: ListViewModelsTest builds WaitingViewModel through the @Inject constructor with a mocked ContactsRepository and covers Ok with two emails (first one, get called once), Ok without emails, Offline and Failed(404) falling back to the text, and null without text. This exercises the production contacts::firstEmail wiring.
- Criterion 3 (tests): mapper round trip with and without contact id (GtdMappersTest), JSON parsing through the cache (TaskRepositoryTest) and the live list (TaskListsRepositoryTest), recipient selection (WaitingDraftEmailTest, ListViewModelsTest) and the route-level intent for both the contact and the fallback case (WaitingRouteDraftMailTest). Whether `sh scripts/check.sh` passes was not run in this review; that is the parent worker's evidence.
- Architecture: the gtd list view model depends on the contacts module only through ContactsRepository (a service), consistent with module boundaries; no new state owner was introduced. No email addresses or contact data are logged. Test data uses example.com/example.org addresses.

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
