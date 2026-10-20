import { uuidv7 } from './uuidv7';

describe('uuidv7', () => {
  it('has the version 7 and RFC 9562 variant bits', () => {
    expect(uuidv7()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });

  it('starts with the Unix milliseconds, so later ids sort after earlier ones', () => {
    const at = Date.UTC(2026, 9, 21, 8, 0, 0);
    expect(uuidv7(at, (b) => b.fill(0))).toBe(
      `${at.toString(16).padStart(12, '0').replace(/^(.{8})(.{4})$/, '$1-$2')}-7000-8000-000000000000`,
    );
    expect(uuidv7(at + 1) > uuidv7(at)).toBe(true);
  });

  it('keeps the random bits outside version and variant', () => {
    expect(uuidv7(0, (b) => b.fill(0xff))).toBe('00000000-0000-7fff-bfff-ffffffffffff');
  });

  it('differs between calls in the same millisecond', () => {
    expect(uuidv7(1000)).not.toBe(uuidv7(1000));
  });
});
