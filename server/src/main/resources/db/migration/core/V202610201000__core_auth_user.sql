-- The single owner account (SPEC.md §4.1), created by the setup flow.
-- totp_confirmed_at is null while setup is pending (TOTP not yet confirmed); the account is active once set.
create table app_user (
    id                uuid primary key,
    created_at        timestamptz not null,
    updated_at        timestamptz not null,
    version           bigint      not null,
    username          text        not null,
    password_hash     text        not null,
    totp_secret       text        not null, -- AES-256-GCM ciphertext (core.crypto), never plaintext
    totp_confirmed_at timestamptz
);

-- Single-user application: at most one row, also under concurrent setup requests.
create unique index app_user_single_row on app_user ((true));

-- Recovery codes for a lost TOTP device: only Argon2id hashes are stored; used_at marks a consumed code.
create table recovery_code (
    id         uuid primary key,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version    bigint      not null,
    user_id    uuid        not null references app_user (id) on delete cascade,
    code_hash  text        not null,
    used_at    timestamptz
);

create index recovery_code_user_id on recovery_code (user_id);
