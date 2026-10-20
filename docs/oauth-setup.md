# OAuth setup: Google and Microsoft

My Cockpit connects to Google and Microsoft accounts with your own OAuth apps (SPEC.md §7.1). You register one app
per provider, put its client id and secret into the server environment, and then connect accounts in
Settings → Integrations. One connection per account is shared by calendar, mail and contacts, so the consent
screen asks for all three at once.

A provider without a client id and secret is disabled (`GET /api/v1/oauth/providers` shows `configured: false`).

## Redirect URI

Both providers need the exact redirect URI of your server:

```
https://<COCKPIT_DOMAIN>/api/v1/oauth/google/callback
https://<COCKPIT_DOMAIN>/api/v1/oauth/microsoft/callback
```

For local development (`--spring.profiles.active=dev`, browser on the Angular dev server) the server uses
`http://localhost:4200` instead, so also register:

```
http://localhost:4200/api/v1/oauth/google/callback
http://localhost:4200/api/v1/oauth/microsoft/callback
```

The base URL comes from the property `cockpit.oauth.public-url` (default `https://${COCKPIT_DOMAIN}`). Set it
explicitly (e.g. `COCKPIT_OAUTH_PUBLICURL=https://cockpit.example.org:8443`) if the server is reached on another
origin, such as a non-standard port.

## Google (Calendar, Contacts, Gmail via IMAP/SMTP)

1. Open the [Google Cloud console](https://console.cloud.google.com/) and create a project, e.g. "My Cockpit".
2. **APIs & Services → Library**: enable the **Google Calendar API** and the **People API**. Gmail is used through
   IMAP/SMTP with XOAUTH2, so the Gmail API is not needed.
3. **APIs & Services → OAuth consent screen** (Google Auth Platform):
   - User type **External** (or **Internal** for a Workspace domain).
   - App name, support email, developer contact.
   - **Data access / Scopes**: add `openid`, `.../auth/userinfo.email`, `.../auth/userinfo.profile`,
     `https://www.googleapis.com/auth/calendar`, `https://www.googleapis.com/auth/contacts` and
     `https://mail.google.com/`.
   - **Audience**: add your own Google account as a test user. Leave the app in *Testing* or publish it
     to *In production*. In *Testing*, Google expires refresh tokens after 7 days, so the connection then shows
     "reconnect required". Publishing an app for your own use with these restricted scopes shows the
     "unverified app" warning on the consent screen, which you can accept for your own account.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID**:
   - Application type **Web application**.
   - **Authorized redirect URIs**: the Google URI(s) from above.
5. Copy the client id and the client secret into the server `.env`:

```
GOOGLE_CLIENT_ID=1234567890-abc.apps.googleusercontent.com
GOOGLE_CLIENT_SECRET=GOCSPX-...
```

The server asks Google for offline access with a consent prompt every time, so each connect returns a refresh
token. Disconnecting in My Cockpit revokes the token at Google.

## Microsoft (Outlook/Microsoft 365: Graph calendar and contacts, IMAP/SMTP)

1. Open the [Microsoft Entra admin center](https://entra.microsoft.com/) → **Identity → Applications → App
   registrations → New registration**.
   - Name, e.g. "My Cockpit".
   - **Supported account types**: "Accounts in any organizational directory and personal Microsoft accounts" for
     both Outlook.com and work accounts, or "this organizational directory only" for one Microsoft 365 tenant.
   - **Redirect URI**: platform **Web**, the Microsoft URI from above (add the dev URI under **Authentication**).
2. **Certificates & secrets → New client secret**. Copy the secret **value** (not its id). Secrets expire: note
   the date and create a new one before then.
3. **API permissions → Add a permission**, all *Delegated*:
   - Microsoft Graph: `openid`, `email`, `profile`, `offline_access`, `Calendars.ReadWrite`, `Contacts.ReadWrite`.
   - For mail, also Microsoft Graph `IMAP.AccessAsUser.All` and `SMTP.Send` (listed under Microsoft Graph, they
     grant the `https://outlook.office.com/...` scopes the server requests).
   - In a work tenant, an admin may have to **Grant admin consent**.
4. Copy **Application (client) ID** and, for a single-tenant app, the **Directory (tenant) ID** into `.env`:

```
MS_CLIENT_ID=00000000-0000-0000-0000-000000000000
MS_CLIENT_SECRET=...
MS_TENANT=common
```

`MS_TENANT` is `common` (default: work and personal accounts), `consumers`, `organizations` or your tenant id.

Microsoft issues access tokens per resource: the server uses the one refresh token for a Graph token (calendar,
contacts) and a separate Outlook token (IMAP/SMTP). Microsoft has no token revocation endpoint: after
disconnecting in My Cockpit, remove the app under <https://account.live.com/consent/Manage> (personal) or
<https://myapps.microsoft.com> (work) if you want to revoke its access completely. For IMAP/SMTP with OAuth on a
Microsoft 365 mailbox, IMAP and "Authenticated SMTP" must be enabled for the mailbox.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | empty (Google disabled) | Google OAuth app |
| `MS_CLIENT_ID`, `MS_CLIENT_SECRET` | empty (Microsoft disabled) | Microsoft OAuth app |
| `MS_TENANT` | `common` | Microsoft authority (tenant) |
| `COCKPIT_DOMAIN` | `localhost` | builds the redirect URI `https://<domain>/api/v1/oauth/<provider>/callback` |
| `COCKPIT_MASTER_KEY` | required | encrypts the stored refresh tokens (AES-256-GCM) |

After changing them, restart the server.

## Security notes

- Refresh tokens are stored encrypted with `COCKPIT_MASTER_KEY` and never returned by the API. Access tokens are
  only kept in memory and refreshed shortly before they expire.
- Each connect flow uses PKCE (S256) and a single-use `state` bound to your browser session; it expires after 10
  minutes. A callback with a state from another session is rejected.
- A refresh token the provider rejects (`invalid_grant`, e.g. revoked, password changed, expired test app) marks the
  connection **reconnect required**: connect the same account again to repair it.
