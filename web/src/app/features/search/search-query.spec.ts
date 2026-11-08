import { parseSearchQuery, SEARCH_TYPES } from './search-query';

describe('parseSearchQuery', () => {
  it('searches all types when there is no type: filter', () => {
    expect(parseSearchQuery('  renew   passport ')).toEqual({ text: 'renew passport', types: [] });
  });

  it.each([
    ['type:task passport', ['TASK']],
    ['passport type:NOTE', ['NOTE']],
    ['TYPE:Tasks passport', ['TASK']],
    ['type:journal_entry passport', ['JOURNAL_ENTRY']],
    ['type:journal-entry passport', ['JOURNAL_ENTRY']],
    ['type:journal passport', ['JOURNAL_ENTRY']],
    ['type:journalentry passport', ['JOURNAL_ENTRY']],
    ['type:mail passport', ['EMAIL']],
    ['type:emails passport', ['EMAIL']],
    ['type:bookmarks passport', ['BOOKMARK']],
  ])('reads %j as a filter by %j', (query, types) => {
    expect(parseSearchQuery(query)).toEqual({ text: 'passport', types });
  });

  it('combines comma lists and several type: words, in the server order, each once', () => {
    expect(parseSearchQuery('type:note,task passport type:habit,note')).toEqual({
      text: 'passport',
      types: ['TASK', 'NOTE', 'HABIT'],
    });
  });

  it('ignores empty names in a comma list', () => {
    expect(parseSearchQuery('type:note,,task, passport').types).toEqual(['TASK', 'NOTE']);
  });

  it.each(['type:', 'type:unicorn', 'type:note,unicorn', 'type:,'])(
    'keeps %j as text when it names no known type',
    (word) => {
      expect(parseSearchQuery(`${word} passport`)).toEqual({ text: `${word} passport`, types: [] });
    },
  );

  it('does not treat a word that only contains type: as a filter', () => {
    expect(parseSearchQuery('subtype:note')).toEqual({ text: 'subtype:note', types: [] });
  });

  it('returns no text for a filter alone', () => {
    expect(parseSearchQuery(' type:contact ')).toEqual({ text: '', types: ['CONTACT'] });
  });

  it('knows every type by its id', () => {
    for (const type of SEARCH_TYPES) {
      expect(parseSearchQuery(`type:${type.toLowerCase()} x`).types).toEqual([type]);
    }
  });
});
