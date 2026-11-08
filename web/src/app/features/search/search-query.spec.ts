import { clearTranslations, loadTranslations } from '@angular/localize';
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

  it('knows a label with a space written with a dash, and a plural with s', () => {
    expect(parseSearchQuery('type:Journal-Entry,Habits x').types).toEqual([
      'JOURNAL_ENTRY',
      'HABIT',
    ]);
  });

  it.each(['constructor', 'constructors', 'note,constructor'])(
    'does not take an inherited object key (%j) for a type, and keeps an unknown word as text',
    (names) => {
      expect(parseSearchQuery(`type:${names} passport`)).toEqual({
        text: `type:${names} passport`,
        types: [],
      });
    },
  );

  it('accepts the prefix and the type name in any case', () => {
    expect(parseSearchQuery('Type:Note')).toEqual({ text: '', types: ['NOTE'] });
  });

  it('keeps an empty query empty', () => {
    expect(parseSearchQuery('   ')).toEqual({ text: '', types: [] });
  });

  describe('in German', () => {
    // The same targets as messages.de.xlf: the labels are read at parse time.
    beforeEach(() =>
      loadTranslations({
        'itemType.note': 'Notiz',
        'itemType.event': 'Termin',
        'itemType.email': 'E-Mail',
        'itemType.journalEntry': 'Journaleintrag',
      }),
    );
    afterEach(() => clearTranslations());

    it.each([
      ['type:notiz passport', ['NOTE']],
      ['type:Termin passport', ['EVENT']],
      ['type:E-Mail passport', ['EMAIL']],
      ['type:journaleintrag passport', ['JOURNAL_ENTRY']],
      ['type:termin,note passport', ['NOTE', 'EVENT']],
    ])('reads %j as a filter by %j through the UI label', (query, types) => {
      expect(parseSearchQuery(query)).toEqual({ text: 'passport', types });
    });

    it('still knows the ids and English aliases', () => {
      expect(parseSearchQuery('type:note,mail x').types).toEqual(['NOTE', 'EMAIL']);
    });
  });
});
