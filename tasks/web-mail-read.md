---
title: Mail reading view
status: in_progress
owner: dell26/agent-2
deps: [web-mail-list,web-links-panel,mail-gtd]
touches: [web/src/app/features/mail/read]
prio: 1
lease_until: 2026-10-21T16:21:23Z
---
## Goal
Read mail safely (SPEC.md §7.3).

## Acceptance criteria
- [x] Sanitized HTML in a sandboxed iframe (no scripts, CSP), 'load images' toggle (per sender allowlist), attachments (download, preview, save to Files)
  - verified (2026-10-21 18:04+0200 by dell26/agent-2): e2e: npm run e2e -- e2e/mail-read/read.spec.ts (podman e2e stack, GreenMail) -> pass: script in mail does not run, remote images only after 'Load images'; Vitest at 33cbef07: mail-frame.spec (sandbox without allow-scripts/allow-forms, CSP in srcdoc), mail-read-page.spec (load images, 'Always load from this sender' allowlist, attachment download link, image/text preview, Save to Files) -> 13 files, 276 tests passed
- [x] Buttons: → Inbox task, → Waiting For, Create note; linked items panel
  - verified (2026-10-21 18:04+0200 by dell26/agent-2): npx ng test --include='src/app/features/mail/**/*.spec.ts' at 33cbef07 -> 276 passed, incl. 'GTD actions': → Inbox task, → Waiting For, Create note create the item and reload the linked items panel (app-linked-items with item EMAIL/<id>)
- [x] Vitest component/service tests cover iframe sandbox attributes, image toggle; `cd web && npm run check` passes
  - verified (2026-10-21 18:04+0200 by dell26/agent-2): Vitest mail-frame.spec + mail-read-page.spec (sandbox attributes, image toggle) pass; sh scripts/check.sh (includes cd web && npm run check: api:check, lint, tests 2358 passed, build) at 33cbef07 -> all checks passed
- [x] Playwright `e2e/mail-read/read.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/mail-read/read.spec.ts`, see p0-e2e-harness): script in mail does not run; images load only after toggle
  - verified (2026-10-21 17:57+0200 by dell26/agent-2): cd web && npm run e2e -- e2e/mail-read/read.spec.ts (podman-compose e2e stack, deploy/docker-compose.e2e.yml, GreenMail) at 08edb6f2 -> 22 passed incl. [chromium] mail-read/read.spec.ts 'script in mail does not run; images load only after the toggle'

## Notes
- ASSUMPTION (2026-10-21 17:26+0200 by dell26/agent-2): Per-sender 'always load images' allowlist is kept client-side in localStorage (cockpit.mail.loadImagesFrom): the API has no allowlist endpoint; a server-side list can replace it later
- ASSUMPTION (2026-10-21 17:26+0200 by dell26/agent-2): Save to Files downloads the attachment in the browser and uploads it to the Files root via POST /api/v1/files (no server copy endpoint); attachments above 50 MB would need the resumable upload and fail with 413
- ASSUMPTION (2026-10-21 17:26+0200 by dell26/agent-2): Attachment preview covers raster images (inline=true URL) and text/* (first 100k chars); other types offer only download and save to Files
- ASSUMPTION (2026-10-21 17:26+0200 by dell26/agent-2): Read view route /mail/messages/:id (the server's deep link); the list opens a message with 'o' or double-click since single click only moves the cursor (existing list behavior); this edits mail.routes.ts and list/ outside touches
- ASSUMPTION (2026-10-21 17:26+0200 by dell26/agent-2): Iframe sandbox is 'allow-same-origin allow-popups allow-popups-to-escape-sandbox' without allow-scripts: same origin lets proxied and cid: image requests carry the session cookie; plus CSP meta script-src 'none'. Opening an unread message marks it READ
- ASSUMPTION (2026-10-21 17:35+0200 by dell26/agent-2): Save to Files stores into the drive root for now, not /Attachments/Mail (SPEC §8.2): the folder is created server-side only; follow-up task mail-attachment-to-files adds a server endpoint
