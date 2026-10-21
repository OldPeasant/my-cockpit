---
title: Mail reading view
status: in_progress
owner: dell26/agent-2
deps: [web-mail-list,web-links-panel,mail-gtd]
touches: [web/src/app/features/mail/read]
prio: 1
lease_until: 2026-10-21T15:46:04Z
---
## Goal
Read mail safely (SPEC.md §7.3).

## Acceptance criteria
- [ ] Sanitized HTML in a sandboxed iframe (no scripts, CSP), 'load images' toggle (per sender allowlist), attachments (download, preview, save to Files)
- [ ] Buttons: → Inbox task, → Waiting For, Create note; linked items panel
- [ ] Vitest component/service tests cover iframe sandbox attributes, image toggle; `cd web && npm run check` passes
- [ ] Playwright `e2e/mail-read/read.spec.ts` passes against the e2e stack (`npm run e2e -- e2e/mail-read/read.spec.ts`, see p0-e2e-harness): script in mail does not run; images load only after toggle

## Notes
