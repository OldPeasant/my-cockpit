import { TestBed } from '@angular/core/testing';
import { IMAGE_ALLOWLIST_KEY, ImageAllowlist } from './image-allowlist';

describe('ImageAllowlist', () => {
  beforeEach(() => localStorage.removeItem(IMAGE_ALLOWLIST_KEY));
  afterEach(() => localStorage.removeItem(IMAGE_ALLOWLIST_KEY));

  it('remembers senders case-insensitively across instances', () => {
    TestBed.inject(ImageAllowlist).add(' Max@Example.test ');
    TestBed.resetTestingModule();
    const list = TestBed.inject(ImageAllowlist);
    expect(list.has('max@example.test')).toBe(true);
    expect(list.has('MAX@EXAMPLE.TEST')).toBe(true);
    expect(list.has('other@example.test')).toBe(false);
    expect(list.has(undefined)).toBe(false);
  });

  it('forgets a sender', () => {
    const list = TestBed.inject(ImageAllowlist);
    list.add('max@example.test');
    list.remove('MAX@example.test');
    expect(list.has('max@example.test')).toBe(false);
    expect(JSON.parse(localStorage.getItem(IMAGE_ALLOWLIST_KEY)!)).toEqual([]);
  });

  it.each(['not json', '{"a":1}', '[1, "ok@example.test"]'])('survives stored %s', (value) => {
    localStorage.setItem(IMAGE_ALLOWLIST_KEY, value);
    const list = TestBed.inject(ImageAllowlist);
    expect(list.has('ok@example.test')).toBe(value.includes('ok@'));
  });
});
