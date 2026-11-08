# Code Review

Date: 2026-11-08

Task: and-contacts-edit - Android contacts: create, edit, delete

Scope: committed task changes under `android/`, `git diff 45b5d48a..5f8c61811 -- android` (commits 1073bc818, 5f8c61811)

Requirements:
- tasks/and-contacts-edit.md acceptance criteria and its two recorded assumptions (Edit/Delete for every source with a source line and a 403/405 message; ISO birthday text field, photo id carried through)
- SPEC.md §4.2 (ETag/If-Match, Problem Details), AGENTS.md project notes (strings EN+DE, lint, conventions)
- Web reference: web/src/app/features/contacts/contact-form-model.ts, contact-detail.ts
- Server contract: generated ContactsApi / ContactRequest / ContactResponse; server ContactService.update/apply/validate (PATCH: only sent fields change, null clears, cleared displayName is derived again; a sent photoFileId is checked against the drive)

Git range:
- Branch: main
- HEAD: 5f8c61811c2ace22c688b80e04ae5222a778b06c
- Base/start: 45b5d48a683c20ae59293e21e3ff4c3360270974
- Commits reviewed: 2

Summary: 0 P0, 2 P1, 5 P2, 2 P3

Gradle could not be run by the reviewer; findings come from reading the code, the generated client and the server. Parent reported compileDebugKotlin and ktlintFormat as passing.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | ContactDetailRoute now always calls hiltViewModel(), which crashes the existing non-Hilt BirthdayContactTest**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt:167`
    - Category: Tests
    - Problem: The new parameter `editViewModel: ContactEditViewModel = hiltViewModel()` runs unconditionally. `BirthdayContactTest` (android/app/src/test/java/ch/sonensei/cockpit/calendar/edit/BirthdayContactTest.kt) renders `BirthdayContact` -> `ContactDetailRoute` inside a plain `ComponentActivity` and only injects a fake `ContactDetailViewModel`. In a non-Hilt activity, `hiltViewModel()` throws (the activity is not a GeneratedComponentManagerHolder). The same route already guards the links view model for exactly this reason (comment "Without Hilt (screen tests with a fake contact view model) there is no links panel"). `BirthdayContact` gives no way to pass an edit view model.
    - Why it matters: All four BirthdayContactTest cases are expected to fail, so criterion 5 (`testDebugUnitTest` and `scripts/check.sh` pass) is not met. Only compilation was checked.
    - Suggested fix: Create the edit view model only when the activity is a Hilt holder, the same way as `links`, and hide Edit/Delete when it is missing. Another option is to let `BirthdayContact` pass one through. Then run `./gradlew testDebugUnitTest` to confirm.

- [ ] **CR-2 | No tests for the new behavior in the reviewed commits**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactForm.kt:1`
    - Category: Tests
    - Problem: The two task commits change no test file. Nothing tests `ContactForm` (`of`, `toRequest`, `problem`), the `ContactEditViewModel` flows (save, conflict reload, 403/405 mapping, delete), the repository's If-Match writes, the form UI (repeatable rows, add and remove) or the delete confirmation and source line.
    - Why it matters: Criterion 5 asks for unit and Compose tests of the new behavior. Several defects below would show up in such tests.
    - Suggested fix: Have the test-writer add tests for these areas. At minimum: request mapping with photo and title kept, 409/412 reload, delete success and failure, and rotation and reuse of the view model (CR-3, CR-4).

## P2 Medium

- [ ] **CR-3 | Unsaved form edits are lost when the activity is recreated (rotation, theme or locale change)**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt:140`
    - Category: Correctness
    - Problem: `LaunchedEffect(contact?.id) { viewModel.start(contact) }` runs on every new composition. The manifest declares no `configChanges`, so a rotation recreates the activity. The activity-scoped `ContactEditViewModel` survives, and `editing` / `open == NEW` are restored by `rememberSaveable`. The new composition then calls `start()` again, which replaces the form with the stored contact (or an empty form) and drops everything typed so far. A save that is running at that moment also gets `saving = false`, so Save becomes enabled again mid-request.
    - Why it matters: The user loses typing without warning. A second Save while the first is still running can create duplicate contacts.
    - Suggested fix: Make `start` do nothing when the same contact (or the same new-form session) is already being edited, for example by keeping a session key in the view model or a `rememberSaveable` token. Do not restart while `saving` is true.

- [ ] **CR-4 | State of the activity-scoped ContactEditViewModel leaks across contacts and screens**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsScreen.kt:184` (also `:240-241`, `ContactsViewModels.kt:200-216`)
    - Category: Correctness
    - Problem: One `ContactEditViewModel` instance serves the Contacts tab, the calendar's BirthdayContact and contacts opened from search. Its `error`, `deleted`, `saving` and `saved` fields are not tied to a contact, and they are cleared only by `start`, `finished`, `save` or `delete`. Concrete cases:
        - (a) A save fails (for example offline), then the user taps Cancel. `onDone(null)` does not clear `error`, so the detail screen shows "Can't reach the server. Nothing was saved." as `actionError`. The same banner then appears on every other contact opened later.
        - (b) A delete fails on contact A, the user goes back and opens B. B shows A's "could not be deleted" banner.
        - (c) The user taps Delete and then presses Back while the request runs. Back stays enabled in the detail. `deleted = true` stays set because no detail is composed to consume it. The next contact detail opened anywhere, including from the calendar, fires `LaunchedEffect(editState.deleted)` at once: it shows "Moved {name} to the Trash" for the wrong contact and closes it.
        - (d) The same case with `saving`: B opens with Edit and Delete disabled and a progress bar.
        - (e) A pending `saved` left over after the screen was left (for example by switching tabs) closes the next form as soon as it opens. `LaunchedEffect(saved)` captured the old value in the same frame that `start()` resets it.
    - Why it matters: Misleading errors, wrong toasts and screens that close on their own. In (c) the screen claims a contact was trashed when it was not.
    - Suggested fix: Store the contact id with the outcome (`deletedId`, `errorFor`) and only act when it matches the contact shown. Clear `error` on cancel. Alternatively, scope the edit view model per contact with a `key`, as is done for the links view model.

- [ ] **CR-5 | A delete conflict shows the save-conflict text and retrying it can never work**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsViewModels.kt:200`
    - Category: Correctness
    - Problem: `delete()` maps 409/412 to `CONFLICT`. `deleteErrorText` then falls through to `saveErrorText`, which says "Your changes are kept: save again to replace that version", which makes no sense on the detail screen. Nothing is reloaded after the conflict. The next Delete again sends `shown.version` from the unrefreshed `ContactDetailViewModel` and fails with 412 again, until the user leaves and reopens the contact. The web (`contact-detail.ts` `remove`) reloads the contact after a delete conflict.
    - Why it matters: The user cannot delete a contact that was changed elsewhere, and the message tells them to do something that does not exist.
    - Suggested fix: On a delete conflict, refresh the detail view model (for example call `viewModel.refresh()` from the route) and show a delete-specific conflict text (EN and DE).

- [ ] **CR-6 | Display name derivation differs from server and web, so organization- or email-named contacts keep a stale name after edits**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactForm.kt:99`
    - Category: Integration
    - Problem: `ContactForm.of` treats the display name as derived only when it equals "given family". The server (`ContactService.derivedName`) and the web (`contact-form-model.ts` `derivedName`) fall back to the organization, then the first email address. For a contact without a name whose display name is its organization or email, Android fills the Display name field with that value and sends it back as an explicit `displayName`. If the user changes the organization (or the first email) in the same edit, the display name keeps the old value. The web would derive it again.
    - Why it matters: Lists, search and birthdays show an outdated name after a normal edit. Android and web behave differently, against the parity goal.
    - Suggested fix: Use the same derivation as the server and web (given + family, else organization, else first email, all trimmed). Leave the field empty when the display name matches it.

- [ ] **CR-7 | Server validation errors (400) show only "The contact could not be saved."**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactsViewModels.kt:227`
    - Category: Correctness
    - Problem: The client does not check email syntax or the entry limit. The server rejects these with 400 Problem Details and field errors (for example `emails[0].address is not an email address`). `saveError()` maps every other status to `FAILED`, so the form says only "The contact could not be saved." and does not say which field is wrong. The web shows the server's message (`error.userMessage`).
    - Why it matters: A mistyped email address is a common input error. The user cannot see why the save keeps failing.
    - Suggested fix: Add a `ContactSaveError.INVALID` for 400 with its own EN/DE text, or show the Problem Details detail. A basic email check in `ContactForm.problem()` would also help.

## P3 Low

- [ ] **CR-8 | Always sending photoFileId makes every Android edit fail when the photo file is gone from the drive**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactForm.kt:92`
    - Category: Integration
    - Problem: The generated serializer uses `encodeDefaults = true`, so `photoFileId` is always sent. The server sets `checkPhoto = fields.photoFileId != null` for any sent field and checks that the file still exists. Its own comment notes that "an unchanged photo may be gone from the drive meanwhile". The web leaves `photoFileId` out of updates and avoids this. On Android, a contact whose photo file was deleted can no longer be saved, and the user sees only the generic error.
    - Why it matters: This is an edge case, but then every edit of that contact fails, while the same edit works on the web.
    - Suggested fix: Accept it and record it with the existing assumption, or map the 400 `photoFileId` error to a clear message. A cleaner fix needs a request that can leave the field out (a server or client change, outside this task's touches).

- [ ] **CR-9 | Notes lose leading whitespace, and German strings mix quote styles**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/contacts/ContactForm.kt:89`; `android/app/src/main/res/values-de/strings_contacts.xml`
    - Category: Quality
    - Problem: `notesMd = notes.trimToNull()` also trims leading whitespace. That changes Markdown that starts with indentation, for example an indented code block. The web only uses `trimEnd()`. In the German strings, the new entries use «…» (`contacts_delete_text`, `contacts_trashed`, `contacts_empty`), while the existing entries in the same file use „…“.
    - Why it matters: Small content change in notes, and inconsistent copy in the German strings.
    - Suggested fix: Use `trimEnd()` for notes (null when blank). Use „…“ in the new German strings.

## Review conclusion

Material issues found; fix before completing the task.
