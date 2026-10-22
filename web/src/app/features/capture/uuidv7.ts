/**
 * A UUIDv7 (RFC 9562): 48 bits of Unix milliseconds, then version, 74 random bits and the variant.
 * The client picks the capture id, so a retried request is recognized by the server.
 */
export function uuidv7(
  now = Date.now(),
  random: (bytes: Uint8Array<ArrayBuffer>) => void = fillRandom,
): string {
  const bytes = new Uint8Array(16);
  random(bytes);
  let ms = Math.max(0, Math.floor(now));
  for (let i = 5; i >= 0; i--) {
    bytes[i] = ms % 256;
    ms = Math.floor(ms / 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function fillRandom(bytes: Uint8Array<ArrayBuffer>): void {
  crypto.getRandomValues(bytes);
}
