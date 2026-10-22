-- Passkeys (WebAuthn credentials) of the owner (SPEC.md §4.1), an alternative to password + TOTP.
-- Only public data is stored: the credential id and the attested credential data (AAGUID, id, COSE public key).
-- sign_count and the backup flags are updated on every use.
create table auth_passkey (
    id                       uuid primary key,
    created_at               timestamptz not null,
    updated_at               timestamptz not null,
    version                  bigint      not null,
    user_id                  uuid        not null references app_user (id) on delete cascade,
    name                     text        not null,
    credential_id            bytea       not null,
    attested_credential_data bytea       not null,
    sign_count               bigint      not null,
    uv_initialized           boolean     not null,
    backup_eligible          boolean     not null,
    backup_state             boolean     not null,
    transports               text,       -- comma-separated AuthenticatorTransport values, null if unknown
    last_used_at             timestamptz
);

create unique index auth_passkey_credential_id on auth_passkey (credential_id);
create index auth_passkey_user_id on auth_passkey (user_id);
