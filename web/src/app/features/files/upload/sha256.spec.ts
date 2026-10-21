import { Sha256 } from './sha256';

/** Deterministic bytes, different at every offset of a 256-byte period. */
function bytes(length: number, seed = 7): Uint8Array {
  const data = new Uint8Array(length);
  for (let i = 0; i < length; i++) data[i] = (i * 31 + seed + (i >>> 8)) & 0xff;
  return data;
}

/** The reference digest from WebCrypto. */
async function reference(data: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new Uint8Array(data));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

function hashInParts(data: Uint8Array, splits: readonly number[]): string {
  const hash = new Sha256();
  let start = 0;
  for (const end of [...splits, data.length]) {
    hash.update(data.subarray(start, end));
    start = end;
  }
  return hash.hex();
}

describe('Sha256', () => {
  it('matches the FIPS 180-4 test vectors', () => {
    const text = (s: string) => new TextEncoder().encode(s);
    expect(new Sha256().hex()).toBe(
      'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    );
    expect(new Sha256().update(text('abc')).hex()).toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    );
    expect(
      new Sha256().update(text('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq')).hex(),
    ).toBe('248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1');
  });

  // Lengths around the padding boundary (55/56 bytes leave room for the length or not) and the
  // block size.
  it.each([0, 1, 55, 56, 57, 63, 64, 65, 119, 120, 127, 128, 129, 1000])(
    'equals WebCrypto for %i bytes in one part',
    async (length) => {
      const data = bytes(length);
      expect(new Sha256().update(data).hex()).toBe(await reference(data));
    },
  );

  it.each<[string, number, number[]]>([
    ['byte by byte', 130, Array.from({ length: 129 }, (_, i) => i + 1)],
    ['at block boundaries', 256, [64, 128, 192]],
    ['just before and after block boundaries', 300, [63, 65, 127, 129]],
    ['with empty parts', 200, [0, 0, 50, 50, 50, 200]],
    ['in uneven parts filling a partial block', 1000, [3, 70, 71, 200, 555, 999]],
    ['in upload-sized chunks', 3 * 65536 + 17, [65536, 131072, 196608]],
  ])('equals WebCrypto when fed %s', async (_, length, splits) => {
    const data = bytes(length, length);
    expect(hashInParts(data, splits)).toBe(await reference(data));
  });

  it('equals WebCrypto for a megabyte in parts of a prime size', async () => {
    const data = bytes(1024 * 1024 + 3, 99);
    const splits: number[] = [];
    for (let at = 9973; at < data.length; at += 9973) splits.push(at);
    expect(hashInParts(data, splits)).toBe(await reference(data));
  });

  it('returns the same digest again and takes no more data after hex()', () => {
    const hash = new Sha256().update(bytes(10));
    const digest = hash.hex();
    expect(hash.hex()).toBe(digest);
    expect(() => hash.update(bytes(1))).toThrow('SHA-256 already finished');
  });
});
