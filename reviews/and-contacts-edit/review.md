# Code Review

Date: 2026-11-08

Task: and-contacts-edit - Android contacts: create, edit, delete

Scope: the committed task changes under `android/`, `git diff 51965a212 HEAD -- android`. The task commits are 52ac12361, b50dbaa4f, f1318e81e, ee30b903b and e7ff7d8f3. e7ff7d8f3 is the fixer commit and holds the final code. For `android/`, HEAD (merge 6f8b9e0ec) has the same content as e7ff7d8f3. This is a re-review of the final code after the fixes. It checks that CR-1..CR-9 and TW-1..TW-3 are resolved and looks for new issues.

Requirements:
- tasks/and-contacts-edit.md: acceptance criteria 1-5 and the recorded assumptions:
    - Edit and Delete for every source, plus a source line and a 403/405 message;
    - birthday as ISO text, photo id carried through;
    - Contacts added to `withOwnFab` outside the touches;
    - CR-8 accepted.
- SPEC.md §9.2, §4.2 (ETag/If-Match, Problem Details); docs/PARITY.md (row "Contacts view-only")
- AGENTS.md project notes (EN+DE strings, lint, no logging of contents)
- Web reference: web/src/app/features/contacts/contact-form-model.ts (`derivedName`, `toForm`)
- Server contract:
    - generated ContactsApi: PATCH plus If-Match, ContactRequest;
    - ContactService.derivedName/validate;
    - ProblemDetailsAdvice `errors` list of FieldError(field, message).

Git range:
- Branch: main
- HEAD: 6f8b9e0ec04f76c443d426ea1f336f32eea2649f (final code commit e7ff7d8f3ca3ea04e497a2b9cca0fd61d71dd294)
- Base/start: 51965a2123e13d9f5babc4cd3dbc23b26251e33b
- Commits reviewed: 5

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Uncommitted changes exist in the working tree. They are test additions in ContactEditScreenTest.kt and ContactsStringsTest.kt, presumably from the test-writer re-run. They are not part of this review.

Build evidence: the parent ran `./gradlew ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport --continue`. Its log in .tmp/and-gradle.log ends with "BUILD SUCCESSFUL" and "EXIT 0". That run included the uncommitted test edits. The reviewer did not run Gradle itself.

## Status of earlier findings

- CR-1: resolved.
    - `ContactDetailRoute` takes `editViewModel: ContactEditViewModel? = null`.
    - It gets one from Hilt only when the activity is a `GeneratedComponentManagerHolder` (ContactsScreen.kt:184-185). Outside Hilt, Edit and Delete are hidden (`:254-255`).
    - BirthdayContactTest therefore no longer calls hiltViewModel().
- CR-2: resolved. ContactFormTest, ContactEditViewModelTest, ContactEditScreenTest, ContactsRepositoryTest, ContactsRouteInAppTest and ContactsStringsTest cover the new behavior.
- CR-3: resolved.
    - The form session is a `rememberSaveable` token (ContactsScreen.kt:144).
    - `start` does nothing for the same session, and it never resets a save that is running (ContactsViewModels.kt:177-183).
    - ContactEditViewModelTest has a VM-level test for this.
- CR-4 and TW-2: resolved.
    - The edit view model is keyed per contact and once for new contacts (`editViewModelKey`, ContactsScreen.kt:131).
    - Cancel and Back call `cancel()`, which clears the problem, error, detail and session (ContactsViewModels.kt:189).
    - The form closes only on a `saved` from its own session (ContactsScreen.kt:148).
    - Cases (a)-(e) from the first review no longer apply: per-contact keys keep a delete or save outcome on its own contact, and a leftover `saved` from another session is ignored.
- CR-5: resolved in code.
    - On a delete conflict the detail reloads (ContactsScreen.kt:205-207).
    - The detail shows its own text, `contacts_delete_conflict` (EN and DE), at ContactsScreen.kt `deleteErrorText`.
    - The next Delete uses the reloaded `shown.version`.
    - See CR-1 below for the missing test of the reload.
- CR-6 and TW-1: resolved. `derivedName` (ContactForm.kt:130-136) uses the same order as the server and the web: given and family name, else the organization, else the first email.
- CR-7: resolved.
    - On a 400 the repository reads the Problem Details `errors[].field` and `message`, else `detail`. This matches the server's FieldError shape. The body is kept in memory only and is never logged.
    - A save refused with 400 shows `contacts_save_invalid_detail` (EN and DE). A delete answered with 400 still maps to FAILED.
- CR-8: accepted by the parent and recorded as an assumption. Because of CR-7, the 400 now shows the server's field error.
- CR-9: resolved.
    - Notes use `trimEnd()` and are null when blank (ContactForm.kt:90).
    - The German strings use „…“ quotes (values-de/strings_contacts.xml:5, 65, 66).
- TW-3: resolved. `Contacts` is in `CockpitDestination.withOwnFab`, and the copies of that set in CaptureButtonTest and FabClearanceTest were updated. This change is outside the touches and is recorded as an assumption.
- F-1 (found by the fixer): `Straße` in values-de, matching the web translation.

## Acceptance criteria

1. Create and edit with repeatable emails, phones and addresses, plus names, organization, birthday and notes: met.
    - ContactEditScreen.kt, ContactForm.toRequest/of.
    - The whole form is sent through PATCH with If-Match. Title and photo are carried through, so no field is cleared by accident.
2. Delete with confirmation, to the Trash: met.
    - `DeleteDialog`, then `ContactEditViewModel.delete` sends DELETE with If-Match.
    - On success it shows a toast and closes the detail. A conflict reloads the contact.
3. Read-only sources: met as the recorded assumption describes.
    - The source line reads "Kept in the cockpit only" or "Synced with X".
    - A 403/405 maps to `contacts_not_allowed`, naming the source.
4. EN and DE strings: met. Every new key exists in both files, and ContactsStringsTest checks that the keys and placeholders match.
5. Tests, ktlintCheck, testDebugUnitTest and lintDebug: the Gradle run is green (see above).
    - The reviewer did not see a `sh scripts/check.sh` result for this revision.
    - The parent must record that with `tasks.py verify`.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | No route-level test checks that a delete conflict reloads the contact**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt:205`
    - Category: Tests
    - Problem: The CR-5 fix depends on `LaunchedEffect(editState.error) { if (error == CONFLICT) viewModel.refresh() }` in `ContactDetailRoute`. No committed test checks this:
        - ContactsRouteInAppTest covers delete success, cancelling the dialog and a 403 refusal, but not a 409/412 conflict.
        - The uncommitted ContactEditScreenTest addition checks only the banner text.
        - The fixer's report also lists this as uncovered.
    - Why it matters: If the reload is lost in a later change, every repeated Delete fails again with 412 until the contact is reopened, and no test fails. That was the original defect.
    - Suggested fix: Add a test to ContactsRouteInAppTest:
        - let the fake repository's delete fail once with 412, then load a newer version;
        - confirm the delete;
        - assert that the contact was fetched again;
        - assert that a second confirmed Delete sends the new version and returns to the list.

## Review conclusion

No material issues found. All earlier findings CR-1..CR-9 and TW-1..TW-3 are resolved or accepted as recorded. The only remaining finding is a small test gap (P3).
