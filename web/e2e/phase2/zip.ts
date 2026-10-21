import { inflateRawSync } from 'node:zlib';

/**
 * A minimal reader of zip archives (stored or deflated entries, no Zip64, no encryption), enough to look inside the
 * backups and folder zips the server writes. Reads the central directory, so data descriptors don't matter.
 */
export class Zip {
  private readonly entries = new Map<
    string,
    { method: number; compressedSize: number; offset: number }
  >();

  constructor(private readonly bytes: Buffer) {
    const end = this.findEndOfCentralDirectory();
    const count = bytes.readUInt16LE(end + 10);
    let at = bytes.readUInt32LE(end + 16);
    for (let i = 0; i < count; i++) {
      if (bytes.readUInt32LE(at) !== 0x02014b50)
        throw new Error(`no central directory entry at ${at}`);
      const flags = bytes.readUInt16LE(at + 8);
      const nameLength = bytes.readUInt16LE(at + 28);
      const extraLength = bytes.readUInt16LE(at + 30);
      const commentLength = bytes.readUInt16LE(at + 32);
      const rawName = bytes.subarray(at + 46, at + 46 + nameLength);
      // Bit 11: the name is UTF-8 (else CP437, which the server never writes for non-ASCII names).
      const name = rawName.toString(flags & 0x800 ? 'utf8' : 'latin1');
      this.entries.set(name, {
        method: bytes.readUInt16LE(at + 10),
        compressedSize: bytes.readUInt32LE(at + 20),
        offset: bytes.readUInt32LE(at + 42),
      });
      at += 46 + nameLength + extraLength + commentLength;
    }
  }

  /** The names of all entries (files and directories, which end with `/`), in archive order. */
  names(): string[] {
    return [...this.entries.keys()];
  }

  has(name: string): boolean {
    return this.entries.has(name);
  }

  /** The uncompressed content of the entry [name]. */
  read(name: string): Buffer {
    const entry = this.entries.get(name);
    if (!entry) throw new Error(`no entry ${name} in the zip`);
    const local = entry.offset;
    if (this.bytes.readUInt32LE(local) !== 0x04034b50)
      throw new Error(`no local header for ${name}`);
    const start =
      local + 30 + this.bytes.readUInt16LE(local + 26) + this.bytes.readUInt16LE(local + 28);
    const data = this.bytes.subarray(start, start + entry.compressedSize);
    if (entry.method === 0) return Buffer.from(data);
    if (entry.method === 8) return inflateRawSync(data);
    throw new Error(`unsupported compression method ${entry.method} of ${name}`);
  }

  text(name: string): string {
    return this.read(name).toString('utf8');
  }

  private findEndOfCentralDirectory(): number {
    // The record is 22 bytes plus a comment of at most 65535 bytes, at the very end.
    const lowest = Math.max(0, this.bytes.length - 22 - 0xffff);
    for (let at = this.bytes.length - 22; at >= lowest; at--) {
      if (this.bytes.readUInt32LE(at) === 0x06054b50) return at;
    }
    throw new Error('not a zip archive (no end of central directory record)');
  }
}
