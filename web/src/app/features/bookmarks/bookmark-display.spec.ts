import { hostOf, inQueue, normalizeUrl, parseTags } from './bookmark-display';
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
});
