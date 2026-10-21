import { NoteResponse } from '../../../api/generated';
import {
  excerpt,
  EXCERPT_LENGTH,
  filterNotes,
  matchesQuery,
  parseTags,
  sortNotes,
} from './notes-filter';

function note(id: string, fields: Partial<NoteResponse> = {}): NoteResponse {
  return {
    id,
    displayTitle: fields.title ?? `Note ${id}`,
    bodyMd: '',
    tags: [],
    pinned: false,
    archived: false,
    createdAt: '2026-10-01T08:00:00Z',
    updatedAt: '2026-10-01T08:00:00Z',
    version: 1,
    ...fields,
  };
}

describe('sortNotes', () => {
  it('puts pinned notes first, then the most recently changed', () => {
    const old = note('01', { updatedAt: '2026-10-01T08:00:00Z' });
    const recent = note('02', { updatedAt: '2026-10-05T08:00:00Z' });
    const pinnedOld = note('03', { pinned: true, updatedAt: '2026-09-01T08:00:00Z' });
    const pinnedRecent = note('04', { pinned: true, updatedAt: '2026-10-02T08:00:00Z' });

    expect(sortNotes([old, recent, pinnedOld, pinnedRecent]).map((n) => n.id)).toEqual([
      '04',
      '03',
      '02',
      '01',
    ]);
  });

  it('orders notes changed at the same time by id, later first, and does not change its input', () => {
    const input = [note('0a'), note('0b')];
    expect(sortNotes(input).map((n) => n.id)).toEqual(['0b', '0a']);
    expect(input.map((n) => n.id)).toEqual(['0a', '0b']);
  });
});

describe('matchesQuery', () => {
  const packing = note('01', {
    title: 'Packing list',
    displayTitle: 'Packing list',
    bodyMd: '- [ ] Passport\n- [ ] Sunscreen',
    tags: ['Holiday'],
  });

  it('matches every note for an empty or blank query', () => {
    expect(matchesQuery(packing, '')).toBe(true);
    expect(matchesQuery(packing, '   ')).toBe(true);
  });

  it('needs every word, regardless of case, in title, text or tags', () => {
    expect(matchesQuery(packing, 'PACKING')).toBe(true);
    expect(matchesQuery(packing, 'passport sun')).toBe(true);
    expect(matchesQuery(packing, '#holiday')).toBe(true);
    expect(matchesQuery(packing, 'passport visa')).toBe(false);
  });

  it('finds the derived title of a note without one', () => {
    const untitled = note('02', {
      displayTitle: 'Ideas for the garden',
      bodyMd: 'Ideas for the garden',
    });
    expect(matchesQuery(untitled, 'garden')).toBe(true);
  });
});

describe('filterNotes', () => {
  it('keeps the matching notes, pinned first', () => {
    const a = note('01', { bodyMd: 'train to Lucerne', updatedAt: '2026-10-09T08:00:00Z' });
    const b = note('02', { bodyMd: 'train times', pinned: true });
    const c = note('03', { bodyMd: 'shopping' });

    expect(filterNotes([a, b, c], 'train').map((n) => n.id)).toEqual(['02', '01']);
  });
});

describe('excerpt', () => {
  it('leaves out the first line when it is the title', () => {
    expect(excerpt({ title: undefined, bodyMd: '\n# Groceries\nMilk\nBread' })).toBe('Milk\nBread');
    expect(excerpt({ title: 'Groceries', bodyMd: 'Milk\nBread' })).toBe('Milk\nBread');
  });

  it('turns Markdown into plain text', () => {
    const body = [
      '## Plan',
      '> **Quote** with `code`',
      '- [ ] open',
      '- [x] done',
      '* item',
      'See [the site](https://example.test) and [[task:0192f0c4-0000-7000-8000-000000000001]]',
      '![](cockpit-file:0192f0c4-0000-7000-8000-000000000009)',
    ].join('\n');
    expect(excerpt({ title: 'T', bodyMd: body })).toBe(
      'Plan\nQuote with code\n☐ open\n☑ done\n• item\nSee the site and',
    );
  });

  it('collapses runs of blank lines and cuts long text', () => {
    expect(excerpt({ title: 'T', bodyMd: 'a\n\n\n\nb' })).toBe('a\n\nb');
    const long = excerpt({ title: 'T', bodyMd: 'x'.repeat(EXCERPT_LENGTH + 50) });
    expect(long).toHaveLength(EXCERPT_LENGTH + 1);
    expect(long.endsWith('…')).toBe(true);
  });

  it('is empty for a note with only a first line', () => {
    expect(excerpt({ title: undefined, bodyMd: 'Only a title' })).toBe('');
    expect(excerpt({ title: undefined, bodyMd: '' })).toBe('');
  });
});

describe('parseTags', () => {
  it('splits on commas and spaces, drops # and keeps each tag once', () => {
    expect(parseTags('#Holiday, work  holiday,,#ideas')).toEqual(['Holiday', 'work', 'ideas']);
    expect(parseTags('  ')).toEqual([]);
  });
});
