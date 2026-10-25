import { TaskResponse } from '../../api/generated';
import { MailSummary } from '../mail/list/mail.service';

/** What the New view shows: everything, only the GTD Inbox or only the unified mail inbox. */
export type NewFilter = 'all' | 'inbox' | 'mail';

export const NEW_FILTERS: readonly NewFilter[] = ['all', 'inbox', 'mail'];

/** The URL query parameter of the filter (`/new?source=mail`); absent means `all`. */
export const FILTER_PARAM = 'source';

/** One row of the New view: a GTD Inbox task or a message of the unified inbox. */
export type NewRow =
  | {
      readonly kind: 'task';
      readonly key: string;
      readonly at: string;
      readonly task: TaskResponse;
    }
  | {
      readonly kind: 'mail';
      readonly key: string;
      readonly at: string;
      readonly mail: MailSummary;
    };

/** The filter a query parameter names; anything else is `all`. */
export function parseFilter(value: string | null | undefined): NewFilter {
  return NEW_FILTERS.includes(value as NewFilter) ? (value as NewFilter) : 'all';
}

/**
 * The rows of `filter`, newest first: tasks by creation, mails by arrival (by their `Date` header
 * when the arrival is unknown, see {@link mailAt}).
 *
 * Mails come in pages (newest first), tasks all at once. While older mails are still to load
 * (`moreMail`), tasks older than the oldest loaded mail are held back: shown now, they would end up
 * above mails of the next page, and loading more would insert rows above them instead of
 * appending. They show once the mail pages reach them (or the last page is loaded).
 */
export function mergeNew(
  tasks: readonly TaskResponse[],
  mails: readonly MailSummary[],
  moreMail: boolean,
  filter: NewFilter = 'all',
): NewRow[] {
  const rows: NewRow[] = [];
  if (filter !== 'mail') {
    const oldestMail = moreMail && filter === 'all' ? oldest(mails) : undefined;
    for (const task of tasks) {
      if (oldestMail !== undefined && time(task.createdAt) < oldestMail) continue;
      rows.push({ kind: 'task', key: `task:${task.id}`, at: task.createdAt, task });
    }
  }
  if (filter !== 'inbox') {
    for (const mail of mails) {
      rows.push({ kind: 'mail', key: `mail:${mail.id}`, at: mailAt(mail), mail });
    }
  }
  return rows.sort((a, b) => time(b.at) - time(a.at) || a.key.localeCompare(b.key));
}

/**
 * The date a mail sorts by: its arrival (`receivedAt`, which the server may leave empty) or else
 * its `Date` header, like the server's list order falls back from the arrival to a stored date.
 */
function mailAt(mail: MailSummary): string {
  return [mail.receivedAt, mail.sentAt].find((at) => parse(at) !== undefined) ?? '';
}

/**
 * The time of the oldest dated mail; `-Infinity` (hold nothing back) when none is loaded yet.
 * Mails without any date are left out, so they don't turn off the hold-back.
 */
function oldest(mails: readonly MailSummary[]): number {
  const times = mails.map((mail) => parse(mailAt(mail))).filter((ms) => ms !== undefined);
  return times.length ? Math.min(...times) : -Infinity;
}

function time(iso: string): number {
  return parse(iso) ?? 0;
}

function parse(iso: string | null | undefined): number | undefined {
  const ms = iso ? Date.parse(iso) : NaN;
  return Number.isNaN(ms) ? undefined : ms;
}
