import {
  hostOf,
  inQueue,
  matchesQuery,
  MAX_TASK_TITLE,
  normalizeUrl,
  parseTags,
} from './bookmark-display';
import { bookmark } from './testing';

describe('bookmark display', () => {
  it('shows the host without www', () => {
    expect(hostOf('https://www.example.org/a?b')).toBe('example.org');
    expect(hostOf('not a url')).toBe('not a url');
  });

  it('accepts http(s) addresses and adds https to bare ones', () => {
    expect(normalizeUrl(' https://example.org/a ')).toBe('https://example.org/a');
    expect(normalizeUrl('http://example.org')).toBe('http://example.org');
    expect(normalizeUrl('example.org/a')).toBe('https://example.org/a');
    expect(normalizeUrl('localhost:8080/x')).toBe('https://localhost:8080/x');
  });

  it('rejects what is not a web address', () => {
    expect(normalizeUrl('')).toBeNull();
    expect(normalizeUrl('hello world')).toBeNull();
    expect(normalizeUrl('hello')).toBeNull();
    expect(normalizeUrl('javascript:alert(1)')).toBeNull();
    expect(normalizeUrl('ftp://example.org/file')).toBeNull();
    expect(normalizeUrl('mailto:anna@example.org')).toBeNull();
  });

  it('parses tags at commas and spaces, without # and duplicates', () => {
    expect(parseTags('kotlin, #Design  design,,rust')).toEqual(['kotlin', 'Design', 'rust']);
    expect(parseTags('  ')).toEqual([]);
  });

  it('is in the queue while in the list and unread', () => {
    expect(inQueue(bookmark('1'))).toBe(true);
    expect(inQueue(bookmark('1', { readAt: '2026-10-21T08:00:00Z' }))).toBe(false);
    expect(inQueue(bookmark('1', { readLater: false }))).toBe(false);
  });

  describe('matchesQuery', () => {
    const ESSAY = bookmark('1', {
      title: 'On Slow Software',
      displayTitle: 'On Slow Software',
      url: 'https://www.example.org/slow-reading',
      description: 'Why waiting for tools hurts focus.',
      tags: ['essays', 'Productivity'],
    });
    const UNTITLED = bookmark('2', {
      title: undefined,
      displayTitle: 'docs.example.com',
      url: 'https://docs.example.com/guide',
      description: undefined,
      tags: [],
    });

    it('matches everything for a blank query', () => {
      expect(matchesQuery(ESSAY, '')).toBe(true);
      expect(matchesQuery(ESSAY, '   ')).toBe(true);
      expect(matchesQuery(UNTITLED, '\t\n')).toBe(true);
    });

    it('matches the title, the URL and the notes, ignoring case', () => {
      expect(matchesQuery(ESSAY, 'slow software')).toBe(true);
      expect(matchesQuery(ESSAY, 'SOFTWARE')).toBe(true);
      expect(matchesQuery(ESSAY, 'example.org/slow')).toBe(true);
      expect(matchesQuery(ESSAY, 'Focus')).toBe(true);
    });

    it('matches the display title of a bookmark without a title', () => {
      expect(matchesQuery(UNTITLED, 'docs.example')).toBe(true);
      expect(matchesQuery(UNTITLED, 'guide')).toBe(true);
    });

    it('matches tags with or without #, ignoring case', () => {
      expect(matchesQuery(ESSAY, '#essays')).toBe(true);
      expect(matchesQuery(ESSAY, '#productivity')).toBe(true);
      expect(matchesQuery(ESSAY, '#PROD')).toBe(true);
      expect(matchesQuery(ESSAY, 'essays')).toBe(true);
      expect(matchesQuery(ESSAY, '#kotlin')).toBe(false);
      // "#" only marks a tag: a word of the notes with # in front is not a tag.
      expect(matchesQuery(ESSAY, '#focus')).toBe(false);
    });

    it('needs every word to match, wherever each occurs', () => {
      expect(matchesQuery(ESSAY, '  focus   #essays example ')).toBe(true);
      expect(matchesQuery(ESSAY, 'focus kotlin')).toBe(false);
      expect(matchesQuery(UNTITLED, 'docs essays')).toBe(false);
    });

    it('does not match across the boundaries of fields', () => {
      // Title ends with "Software", the URL starts with "https": no word spans both.
      expect(matchesQuery(ESSAY, 'softwarehttps')).toBe(false);
    });
  });

  it('caps task titles at the server limit', () => {
    expect(MAX_TASK_TITLE).toBe(500);
  });
});
