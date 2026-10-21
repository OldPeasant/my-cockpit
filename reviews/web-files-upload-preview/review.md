# Code Review

Date: 2026-10-21

Task: web-files-upload-preview - Files: upload and preview

Scope: final code of the whole task, `git diff 9d1c49cc..HEAD -- web`. Focus on what changed since the previous re-review (then d0bd6037 + 7ebfa162; those hashes no longer exist after the WIP merge bd8ded2b). That means the DropHint component and commit eba1547c, which shows a queued state for capture attachments (previous P3 CR-1). Production files read in full: files/upload/{upload.service, file-drop, drop-hint (.ts/.scss)}, files/preview/preview-dialog.ts, capture/capture-dialog.{ts,html}. I also read the files-page diff (html/scss/ts). Committed specs only. Playwright, Docker and scripts/check.sh were not run, as the parent asked.

Requirements:
- tasks/web-files-upload-preview.md (acceptance criteria 1-5 and the recorded assumptions)
- SPEC.md section 8.2 (Files) and section 4.5 (quick capture)
- AGENTS.md project notes, docs/ARCHITECTURE.md (stack, i18n, conventions)
- The previous review in this file (CR-1..CR-6 on the first revision, then CR-1 (P3) of the re-review) and reviews/web-files-upload-preview/fixes.md

Git range:
- Branch: main
- HEAD: a7e14ab2443dbea4b471a663c99e8cfdcce8c5d8
- Base/start: 9d1c49ccf7abf95a915adbf7f0ec2669d0926f59 (origin/main at claim)
- Commits reviewed: 11 (658018df..a7e14ab2, including merge bd8ded2b)
- Uncommitted changes: none at review start

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Earlier findings: status

- First review CR-1 (P1, capture spec/criterion 4 tests): still resolved.
- First review CR-2 (P2, responsive layout deleted): still resolved. The `files-page.scss` diff does not touch the media query.
- First review CR-3 (P2, concurrent PDF renders): still resolved.
- First review CR-4 (P2, capture saved without failed attachments): still resolved. `capture-dialog.ts:145` blocks submit on `failedFiles()`.
- First review CR-5 (P3, cancel during create/complete): still resolved. `upload.service.ts:224` and `:266` are non-abortable, and `:270-274` discards an upload that was created after a cancel.
- First review CR-6 (P3, live regions): still resolved.
- Re-review CR-1 (P3, queued capture attachments showed "0 %" without a waiting state): resolved by eba1547c. `capture-dialog.html:97-99` adds a `queued` case ("Waiting", `@@capture.fileQueued`) inside the `role="status"` region. The percentage now shows only while `state() === 'uploading'` (`:111`), so `failed` and `cancelled` no longer show a stale percentage either. The new spec "shows Waiting, not a percentage, for an attachment queued behind running uploads" checks that the third file waits behind `PARALLEL_UPLOADS`, that no request is sent for it, and that it moves to uploading and then to Attached. The other part of the suggestion (letting capture attachments skip the drive queue) was not done. That is acceptable: Save is still blocked while the attachment waits, but the user can now see why.

## New code checked without findings

- DropHint (`files/upload/drop-hint.ts`, `.scss`): a standalone OnPush component with host `aria-hidden="true"` and `pointer-events: none`. Because of `pointer-events: none`, it cannot swallow dragleave or drop events of the `FileDrop` host. It is absolutely positioned (`inset: -6px`) in `.main`, which is now `position: relative` with a `min-height`, so an empty folder still offers a usable drop area. It is rendered only while `drop.dragging()` is true. Its colors use theme tokens (`--app-accent`, `--mat-sys-title-small`). The string is externalized (`@@files.upload.dropHint`).
- FileDrop: ignores drags that carry no files and ignores dragleave into child elements. `preventDefault` on drop runs only when files were dropped. The files-page template wires it with `#drop="appFileDrop"` and `(appFileDrop)="upload($event)"`.
- Files page: the shared `.crumbs ol, .entries` list reset and the `.open { all: unset }` reset keep the anchor's former look (inherited color, no underline). The `&:focus-visible` outline still follows in the same rule. The upload refresh is filtered to the shown folder and debounced. The picker input is reset after each pick.
- Capture dialog: the template change only touches the status of each file. `uploading()` still counts queued jobs, so Save waits for them, as the recorded assumption says.
- Focused vitest: `npm --prefix web run test:ci -- --include=src/app/features/capture --include=src/app/features/files` gives 17 files and 288 tests passed at a7e14ab2.

Evidence note (not a finding): `python tasks.py verify web-files-upload-preview` shows criteria 1-4 still unrecorded at review time. Criterion 5 (Playwright) was recorded at d0bd6037. The only production change since then is the capture status template (eba1547c), which `e2e/files-upload/upload.spec.ts` does not exercise, so that evidence still applies to the upload and preview flows.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Cancelling a single-request upload after the server stored it shows "Cancelled" while the file stays in the drive**
    - File: `web/src/app/features/files/upload/upload.service.ts:185`
    - Category: Correctness
    - Problem: `sendWhole` sends the multipart `POST /files` as an abortable request (the default `abortable = true` in `send`, `:315`). `cancel()` marks the job `cancelled` and unsubscribes. Suppose the body is already fully sent and the server is storing the file or has stored it: the file exists in the drive, but the job shows "Cancelled". `discard()` does nothing because there is no `uploadId`, and `uploaded` is never emitted. For create and complete of the chunked path, the code already handles this ("a cancelled write may already have been committed", previous CR-5). The small-file path does not, though it is the common path (every file of 50 MB or less, and every capture attachment).
    - Why it matters: The window is small (between the end of the body and the response), so the impact is low. When it happens, the user sees "Cancelled", but the file appears in the folder after the next refresh. In capture, a removed attachment then stays in the drive root. That part is already accepted by the recorded assumption for cancelled captures.
    - Suggested fix: Treat the response of the multipart POST like `complete`. Once the upload progress has reached the full body size, stop aborting the request on cancel and let it finish. If it succeeds, mark the job `done`, or keep it `cancelled` and emit `uploaded`, so the listing refreshes. Alternatively, record the behavior as an assumption. Add a spec for "cancel after the body was sent".

## Review conclusion

No material issues found. All earlier findings are resolved in the current code, including the re-review's P3 CR-1 (queued state for capture attachments, with a spec). The one new finding is low priority and can be fixed or recorded as an assumption at the parent worker's discretion. The parent worker still has to record evidence for criteria 1-4 (`tasks.py verify`).
