-- Set when a restore could not bring back the TOTP secret (backup without passphrase, SPEC.md §10.2): the next login
-- gets a session that can only enroll TOTP again, which clears the flag.
alter table app_user add column totp_reenroll_required boolean not null default false;
