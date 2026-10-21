import { FormControl, FormGroup, NonNullableFormBuilder, Validators } from '@angular/forms';
import { MailServerRequest, MailServerResponse } from '../../../api/generated';

/** How the connection to a mail server is secured. */
export type Security = MailServerRequest['security'];

/** The two servers of an IMAP/SMTP account. */
export type Protocol = 'imap' | 'smtp';

/** The form of one server: host, port and security. */
export type ServerForm = FormGroup<{
  host: FormControl<string>;
  port: FormControl<number>;
  security: FormControl<Security>;
}>;

/** The usual port per protocol and security, offered when the security changes. */
export const DEFAULT_PORTS: Record<Protocol, Record<Security, number>> = {
  imap: { SSL_TLS: 993, STARTTLS: 143, NONE: 143 },
  smtp: { SSL_TLS: 465, STARTTLS: 587, NONE: 25 },
};

/** The server limits a cached sync window to 50 years. */
export const MAX_SYNC_WINDOW_MONTHS = 600;

/** Something that looks like one email address (the server checks it properly). */
export const EMAIL_PATTERN = /^\s*[^\s@]+@[^\s@]+\s*$/;

export function serverForm(
  fb: NonNullableFormBuilder,
  protocol: Protocol,
  server?: MailServerResponse,
): ServerForm {
  const security = server?.security ?? (protocol === 'imap' ? 'SSL_TLS' : 'STARTTLS');
  return fb.group({
    host: [server?.host ?? '', [Validators.required, Validators.maxLength(253)]],
    port: [
      server?.port ?? DEFAULT_PORTS[protocol][security],
      [Validators.required, Validators.min(1), Validators.max(65535)],
    ],
    security: fb.control<Security>(security),
  });
}

export function serverValue(form: ServerForm): MailServerRequest {
  const { host, port, security } = form.getRawValue();
  return { host: host.trim(), port: Number(port), security };
}

/** Trimmed text, or undefined when blank (the server then uses its default). */
export function optional(text: string): string | undefined {
  return text.trim() || undefined;
}
