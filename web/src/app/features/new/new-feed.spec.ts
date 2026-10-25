import { TaskResponse } from '../../api/generated';
import { summary } from '../mail/list/mail-testing';
import { MailSummary } from '../mail/list/mail.service';
import { mergeNew, parseFilter } from './new-feed';

function task(id: string, createdAt: string): TaskResponse {
  return {
    id,
    title: `Task ${id}`,
    list: 'INBOX',
    position: 1,
    contextIds: [],
    createdAt,
    updatedAt: createdAt,
    version: 1,
  };
}

const mail = (id: string, receivedAt: string): MailSummary => summary(id, { receivedAt });
const keys = (rows: { key: string }[]) => rows.map((row) => row.key);

describe('mergeNew', () => {
  const tasks = [
    task('t1', '2026-10-20T12:00:00Z'),
    task('t2', '2026-10-20T09:00:00Z'),
    task('t3', '2026-10-19T08:00:00Z'),
  ];
  const firstPage = [mail('m1', '2026-10-20T11:00:00Z'), mail('m2', '2026-10-20T10:00:00Z')];
  const secondPage = [mail('m3', '2026-10-19T09:00:00Z'), mail('m4', '2026-10-18T09:00:00Z')];

  it('merges tasks by creation and mails by arrival, newest first', () => {
    expect(keys(mergeNew(tasks, [...firstPage, ...secondPage], false))).toEqual([
      'task:t1',
      'mail:m1',
      'mail:m2',
      'task:t2',
      'mail:m3',
      'task:t3',
      'mail:m4',
    ]);
  });

  it('holds back tasks older than the loaded mail while more mail can load', () => {
    expect(keys(mergeNew(tasks, firstPage, true))).toEqual(['task:t1', 'mail:m1', 'mail:m2']);
  });

  it('only appends when the next mail page loads', () => {
    const before = keys(mergeNew(tasks, firstPage, true));
    const after = keys(mergeNew(tasks, [...firstPage, ...secondPage], true));
    expect(after.slice(0, before.length)).toEqual(before);
    expect(after).toEqual([
      'task:t1',
      'mail:m1',
      'mail:m2',
      'task:t2',
      'mail:m3',
      'task:t3',
      'mail:m4',
    ]);
  });

  it('shows every task while no mail is loaded', () => {
    expect(keys(mergeNew(tasks, [], true))).toEqual(['task:t1', 'task:t2', 'task:t3']);
  });

  it('filters by source', () => {
    expect(keys(mergeNew(tasks, firstPage, true, 'inbox'))).toEqual([
      'task:t1',
      'task:t2',
      'task:t3',
    ]);
    expect(keys(mergeNew(tasks, firstPage, true, 'mail'))).toEqual(['mail:m1', 'mail:m2']);
  });

  it('shows a task exactly as old as the oldest loaded mail, holds back one just older', () => {
    const atOldest = task('same', '2026-10-20T10:00:00Z');
    const justOlder = task('older', '2026-10-20T09:59:59.999Z');
    expect(keys(mergeNew([atOldest, justOlder], firstPage, true))).toEqual([
      'mail:m1',
      'mail:m2',
      'task:same',
    ]);
  });

  it('holds nothing back once the last mail page is loaded, or without mails', () => {
    expect(keys(mergeNew(tasks, firstPage, false))).toEqual([
      'task:t1',
      'mail:m1',
      'mail:m2',
      'task:t2',
      'task:t3',
    ]);
    expect(keys(mergeNew([], [], false))).toEqual([]);
  });

  it('keeps every row of the selected source as new rows arrive (SSE refetch)', () => {
    const before = keys(mergeNew(tasks, firstPage, true));
    const after = keys(mergeNew([task('t0', '2026-10-20T13:00:00Z'), ...tasks], firstPage, true));
    expect(after).toEqual(['task:t0', ...before]);
  });

  it('sorts an unparsable date last instead of failing', () => {
    expect(keys(mergeNew([task('bad', 'not a date')], firstPage, false))).toEqual([
      'mail:m1',
      'mail:m2',
      'task:bad',
    ]);
  });

  describe('mails without an arrival date', () => {
    // The server may leave `receivedAt` empty although the generated type says `string`.
    const undated = (id: string, sentAt: string) =>
      summary(id, { receivedAt: null as unknown as string, sentAt });

    it('sorts and dates them by their Date header', () => {
      const rows = mergeNew(tasks, [undated('u', '2026-10-20T11:30:00Z'), ...firstPage], false);
      expect(keys(rows).slice(0, 3)).toEqual(['task:t1', 'mail:u', 'mail:m1']);
      expect(rows[1].at).toBe('2026-10-20T11:30:00Z');
    });

    it('uses the Date header for the hold-back too', () => {
      const page = [mail('m1', '2026-10-20T11:00:00Z'), undated('u', '2026-10-20T10:00:00Z')];
      expect(keys(mergeNew(tasks, page, true))).toEqual(['task:t1', 'mail:m1', 'mail:u']);
    });

    it('leaves a mail without any date out of the hold-back', () => {
      const page = [...firstPage, undated('u', '')];
      expect(keys(mergeNew(tasks, page, true))).toEqual([
        'task:t1',
        'mail:m1',
        'mail:m2',
        'mail:u',
      ]);
    });
  });

  it('orders equal times by source and id', () => {
    const at = '2026-10-20T10:00:00Z';
    expect(keys(mergeNew([task('b', at)], [mail('a', at)], false))).toEqual(['mail:a', 'task:b']);
  });
});

describe('parseFilter', () => {
  it.each([
    ['inbox', 'inbox'],
    ['mail', 'mail'],
    ['all', 'all'],
    [null, 'all'],
    ['bogus', 'all'],
  ])('%s -> %s', (value, filter) => {
    expect(parseFilter(value)).toBe(filter);
  });
});
