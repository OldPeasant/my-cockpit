# 0003: Microsoft integration is deferred (built, but hidden)

Status: accepted (owner decision, 2026-10-26)

## Context

SPEC.md planned Microsoft 365/Outlook next to Google: OAuth via an Azure app, Graph calendar sync, Graph contacts
and IMAP/SMTP with XOAUTH2 for mail. Most of it is already built (cal-microsoft, the Microsoft part of
oauth-connections, MICROSOFT account type in mail, MICROSOFT enum values in the calendar, mail and contacts
migrations, web settings). The owner does not need Microsoft for now and may re-add it later.

## Decision

- No further Microsoft work: the open tasks contacts-microsoft, h-oauth-azure and h-ms-real are dropped.
- The built code stays, but Microsoft is switched off by one server setting (default off): the API does not offer or
  accept Microsoft connections, calendar sources or mail accounts, and the web and Android clients don't show
  Microsoft options (task microsoft-hide).
- Enum values, columns and applied migrations stay unchanged (never edit applied migrations).
- SPEC.md marks the Microsoft parts as deferred and points here.

## Consequences

- Re-enabling Microsoft means: turn the setting on, register the Azure app (docs/oauth-setup.md), re-add tasks for
  Microsoft contacts and the real-account checks.
- Existing Microsoft code keeps its tests, so it doesn't rot while hidden; it adds some build and test time.
