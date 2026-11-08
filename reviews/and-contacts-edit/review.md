# Code Review

Date: 2026-11-08

Task: and-contacts-edit - Android contacts: create, edit, delete

Scope: the task's committed changes under `android/`.

- Task commits after the rebase:
    - f0937c6ef: tests, with 3 failing on purpose;
    - ba4aef0ad: fixer, final production code;
    - f2257aac7: more tests;
    - c36374f73: lint fix in a test.
    - Before the rebase these were e7ff7d8f3, df6952c67 and 6e40c2dd5.
- Task files only, compared against base 51965a212:
    - `android/.../contacts`;
    - the contacts string files;
    - `ui/navigation/CockpitDestination.kt`;
    - the CaptureButtonTest and FabClearanceTest own-FAB copies.
- Other agents' work on main (mail-load-older) is not reviewed.
    - `git diff 6e40c2dd5 c36374f73` changes no task file, so the rebase kept the task code as reviewed.
    - The only differences are main's mail files.
- This is a re-review of the final code after the fixes. It checks that CR-1..CR-9 and TW-1..TW-3 are resolved, and briefly reviews the tests added afterwards (test files only; production code is unchanged since the fixer commit).

Requirements:
- tasks/and-contacts-edit.md: acceptance criteria 1-5, plus the recorded assumptions:
    - Edit and Delete for every source, with a source line and a 403/405 message;
    - birthday as ISO text, photo id carried through;
    - Contacts in `withOwnFab`, which is outside the touches;
    - CR-8 accepted.
- SPEC.md §9.2 and §4.2 (ETag/If-Match, Problem Details); docs/PARITY.md, row "Contacts view-only"
- AGENTS.md project notes: EN+DE strings, lint, no logging of contents
- Web reference: web/src/app/features/contacts/contact-form-model.ts (`derivedName`, `toForm`)
- Server contract:
    - generated ContactsApi: PATCH with If-Match, ContactRequest;
    - ContactService.derivedName and validate;
    - ProblemDetailsAdvice: `errors` list of FieldError(field, message).

Git range:
- Branch: main
- HEAD: c36374f737e876645773f55d79326fc91f2b730e
- Base/start: 51965a2123e13d9f5babc4cd3dbc23b26251e33b, the task base. main was merged in through a rebase.
- Commits reviewed: 7 task commits, counted before the rebase: 52ac12361, b50dbaa4f, f1318e81e, ee30b903b, e7ff7d8f3, df6952c67, 6e40c2dd5.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Build evidence: the reviewer did not run Gradle. The parent reports this run on the final test code: `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` -> BUILD SUCCESSFUL, 4783 tests, 0 failures.

## Status of earlier findings

- CR-1: resolved.
    - `ContactDetailRoute` takes `editViewModel: ContactEditViewModel? = null`.
    - It gets an edit view model from Hilt only in a `GeneratedComponentManagerHolder` activity (ContactsScreen.kt:184-185).
    - Outside Hilt, Edit and Delete are hidden (`:254-255`).
    - Test: ContactEditScreenTest "outside a Hilt activity the detail route opens the contact without edit actions instead of failing".
- CR-2: resolved. These test classes cover the new behavior:
    - ContactFormTest;
    - ContactEditViewModelTest;
    - ContactEditScreenTest;
    - ContactsRepositoryTest;
    - ContactsRouteInAppTest;
    - ContactsStringsTest.
- CR-3: resolved.
    - The form session is a `rememberSaveable` token (ContactsScreen.kt:144).
    - `start` does nothing for the same session, and it never resets a save that is running (ContactsViewModels.kt:177-183).
    - Both cases have VM tests.
    - A Compose test, "what was typed into the form survives a configuration change", uses `scenario.recreate()`.
- CR-4 and TW-2: resolved.
    - The edit view model is keyed per contact, and new contacts share one (`editViewModelKey`, ContactsScreen.kt:131).
    - `cancel()` clears the problem, error, detail and session (ContactsViewModels.kt:189).
    - The form closes only on a `saved` that belongs to its own session (ContactsScreen.kt:148).
    - Test: "a delete refused on one contact shows no error on another contact".
- CR-5: resolved.
    - A delete conflict reloads the detail (ContactsScreen.kt:205-207) and shows its own text, `contacts_delete_conflict`, in EN and DE.
    - Test: "a delete that hit a change made elsewhere reloads the contact, and Delete again uses its current version". It checks that:
        - the contact is fetched twice;
        - the first delete sends the old version and the second sends the reloaded version;
        - the screen then returns to the list.
- CR-6 and TW-1: resolved. `derivedName` (ContactForm.kt:130-136) derives the display name in the same order as the server and the web.
- CR-7: resolved.
    - Problem Details are read as `errors[].field` plus `message`, else `detail`. This matches the server's FieldError.
    - The details stay in memory only and are never logged.
    - Tests:
        - repository: field errors, fallback to `detail`, and a 403 body is not read;
        - view model;
        - screen;
        - route: the server's error is shown in the form.
- CR-8: accepted by the parent and recorded as an assumption. Through the CR-7 fix, the 400 field error is now shown.
- CR-9: resolved.
    - Notes use `trimEnd()` (ContactForm.kt:90).
    - The German strings use „…“ quotes, and ContactsStringsTest checks this.
- TW-3: resolved.
    - `Contacts` is in `CockpitDestination.withOwnFab`, and the test copies of that set were updated.
    - This change is outside the touches and is recorded as an assumption.
- F-1 (found by the fixer): values-de uses `Straße`.
- CR-1 of the previous re-review (P3, no route test of the delete-conflict reload): resolved by the ContactsRouteInAppTest test listed under CR-5 above.

## New tests (df6952c67 and 6e40c2dd5, now f2257aac7 and c36374f73)

I reviewed these briefly and found no issues.

- The tests check visible behavior and the calls made to the repository, not internals.
- Only the repository is mocked, so the route, the Hilt keys and the view model logic run for real.
- The conflict test chains `andThen` on both `get` and `delete`, so it really checks which version the second Delete sends.
- The VM test "a form started again while its save runs" checks that the save request is sent only once (`coVerify(exactly = 1)`).

## Acceptance criteria

1. Create and edit, with repeatable emails, phones and addresses, plus names, organization, birthday and notes: met.
    - The whole form is sent by PATCH with If-Match.
    - The title and photo are carried through.
2. Delete with confirmation, to the Trash: met.
    - A dialog asks first, then the app sends DELETE with If-Match, shows a toast and closes the detail.
    - A conflict reloads the contact.
3. Read-only sources: met as the recorded assumption describes.
    - The detail shows a source line.
    - A 403/405 maps to `contacts_not_allowed`, which names the source.
4. EN and DE strings: met.
    - All keys are in both files.
    - ContactsStringsTest checks the keys, placeholders and quotes.
5. Tests, ktlintCheck, testDebugUnitTest and lintDebug: green, as reported by the parent.
    - The reviewer saw no `sh scripts/check.sh` result for HEAD.
    - The parent must run it and record the result with `tasks.py verify`.

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
