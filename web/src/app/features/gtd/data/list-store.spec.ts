import { etag, patched } from './list-store';

describe('etag', () => {
  it('quotes the version like the server', () => {
    expect(etag(0)).toBe('"0"');
    expect(etag(42)).toBe('"42"');
  });
});

describe('patched', () => {
  interface Item {
    id: string;
    title: string;
    energy?: string;
    contextIds: string[];
  }
  const item: Item = { id: 'a', title: 'Water plants', energy: 'LOW', contextIds: ['home'] };

  it('sets the fields sent and keeps the others', () => {
    expect(patched(item, { title: 'Feed cat', contextIds: [] })).toEqual({
      id: 'a',
      title: 'Feed cat',
      energy: 'LOW',
      contextIds: [],
    });
  });

  it('clears a field sent as null and ignores undefined fields', () => {
    const next = patched(item, { energy: null, title: undefined });
    expect(next).toEqual({ id: 'a', title: 'Water plants', contextIds: ['home'] });
    expect('energy' in next).toBe(false);
  });

  it('keeps falsy values that are not null', () => {
    expect(patched({ n: 5, s: 'x', b: true }, { n: 0, s: '', b: false })).toEqual({
      n: 0,
      s: '',
      b: false,
    });
  });

  it('returns a copy and leaves the original unchanged', () => {
    const next = patched(item, { title: 'Feed cat', energy: null });
    expect(next).not.toBe(item);
    expect(item).toEqual({ id: 'a', title: 'Water plants', energy: 'LOW', contextIds: ['home'] });
  });

  it('returns an equal copy for an empty patch', () => {
    expect(patched(item, {})).toEqual(item);
  });
});
