# Code Review

Date: 2026-11-09

Task: and-waiting-contact-mail - Android Draft email: To from the linked waiting-for contact

Scope: commits between the task base a95fa744f67f90916b6100a637151a8153e8ee80 and task commit 20acec0b79fc9af27e7aa1f93c4c87e345a6cd08 (git diff a95fa744..20acec0b). HEAD f2a077e6a has the same code (its diff from 20acec0b only touches tasks/and-waiting-contact-mail.md). Uncommitted test-writer edits to GtdMappersTest.kt are outside this scope and were not reviewed as part of the implementation.

Requirements:
- tasks/and-waiting-contact-mail.md (acceptance criteria and recorded assumption)
- SPEC.md §5.3 (waiting "Follow up" then "Draft email" prefills To from the linked contact)
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- Web reference implementation web/src/app/features/gtd/lists/waiting-page.ts (uses contact.emails[0])

Git range:
- Branch: main
- HEAD: f2a077e6a582fbd1490345705d623a92cca1c3cb
- Base/start: a95fa744f67f90916b6100a637151a8153e8ee80
- Commits reviewed: 1 (20acec0b, code-identical to f2a077e6a)

Summary: 0 P0, 0 P1, 1 P2, 1 P3

Static review only (Gradle was not run, as instructed).

Verified without findings:
- Criterion 1: GtdTask.waitingForContactId (UUID?, default null), CachedTaskEntity.waitingForContactId (String?), both mappers (TaskResponse.toEntity / CachedTaskEntity.toDomain) and TaskResponse.toTask in TaskLists.kt carry the field. GtdCacheDatabase is bumped to version 3, the exported schema 3.json contains the new TEXT column, and GtdDataModule already uses fallbackToDestructiveMigration(dropAllTables = true), so existing v2 caches are dropped and refilled instead of crashing (cache-only data, as the assumption states). The only CachedTaskEntity constructor call site is the mapper.
- Criterion 2: followUpRecipient uses the linked contact's first email (ContactsResult.Ok, emails.firstOrNull()), falling back to emailIn(waitingForText) when there is no link, the contact cannot be loaded (Offline/Failed) or has no email. This matches the web behavior (emails[0]). ContactsRepository.load catches IO/HTTP/serialization errors and rethrows CancellationException, so the coroutine started from the button does not crash the app on a network failure. The Draft email button is shown for every waiting task, so linked tasks without an address in the text can still draft a mail.
- Hilt: ContactsRepository is an @Singleton @Inject class; existing tests build WaitingViewModel through the internal constructor, whose contactEmail defaults to returning null.
- No email address or contact data is logged.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Draft email: no feedback or duplicate guard while the contact loads**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/WaitingScreen.kt:60`
    - Category: Correctness
    - Problem: draftMail now runs `scope.launch { ... viewModel.mailRecipient(task) ... }`, which waits for a live `GET /contacts/{id}` before starting the mail intent. Nothing shows that the tap was registered, and nothing stops more taps while the request runs. On a slow network every extra tap starts another coroutine, and each one later launches its own ACTION_SENDTO, so the user gets several compose windows or chooser dialogs in a row. Before this change the intent was built synchronously, so this could not happen.
    - Why it matters: Duplicate compose windows on a slow or flaky connection, the typical case for a live fetch with no cache. This is the overlapping-action defect the review guidelines name.
    - Suggested fix: Ignore taps while a draft for that task is still resolving, for example by keeping the running Job (or a per-task "resolving" flag in remembered state or the ViewModel) and skipping the launch while it is active. Optionally disable the button or show progress while it runs. Add a test that two quick taps launch one intent.

## P3 Low

- [ ] **CR-2 | Contact-to-address mapping is untested**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/gtd/lists/ListViewModels.kt:179`
    - Category: Tests
    - Problem: The new tests in WaitingDraftEmailTest only exercise followUpRecipient with a lambda. The production lambda, the private `ContactsRepository.firstEmail` (Ok with several emails returns the first, Ok with no emails returns null, Offline/Failed returns null and falls back to the text), and the @Inject wiring `contacts::firstEmail` are never executed by a test.
    - Why it matters: A regression in this mapping, such as picking the wrong email or treating a Failed result as an address, would go unnoticed. This is the part of criterion 2 that talks to the contacts module.
    - Suggested fix: Make firstEmail internal (or test it through WaitingViewModel.mailRecipient) and cover Ok-with-emails, Ok-without-emails and Offline against a fake ContactsApi or MockWebServer, as other contacts tests do.

## Review conclusion

Material issues found; fix before completing the task.
