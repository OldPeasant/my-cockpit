import { hasThumbnail, previewKind, PreviewKind } from './preview-kind';

describe('previewKind', () => {
  it.each<[string | null | undefined, string, PreviewKind | null]>([
    // Images the server thumbnails and serves inline.
    ['image/jpeg', 'beach.jpg', 'image'],
    ['image/png', 'scan.png', 'image'],
    ['image/webp', 'photo.webp', 'image'],
    ['image/gif', 'loop.gif', 'image'],
    // Other images are not previewed.
    ['image/svg+xml', 'logo.svg', null],
    ['image/tiff', 'scan.tiff', null],
    ['image/heic', 'phone.heic', null],
    ['application/pdf', 'invoice.pdf', 'pdf'],
    ['audio/mpeg', 'song.mp3', 'audio'],
    ['audio/ogg', 'note.ogg', 'audio'],
    ['video/mp4', 'clip.mp4', 'video'],
    ['video/webm', 'clip.webm', 'video'],
    ['text/plain', 'notes.txt', 'text'],
    ['text/csv', 'budget.csv', 'text'],
    ['text/html', 'page.html', 'text'],
    ['application/json', 'data.json', 'text'],
    ['application/xml', 'feed.xml', 'text'],
    ['application/yaml', 'config.yaml', 'text'],
    ['application/x-yaml', 'config.yml', 'text'],
    ['application/x-sh', 'run.sh', 'text'],
    ['text/markdown', 'readme.md', 'markdown'],
    ['text/x-markdown', 'readme', 'markdown'],
    // Markdown stored as text/plain is recognised by its name, case-insensitively.
    ['text/plain', 'README.md', 'markdown'],
    ['text/plain', 'Notes.MARKDOWN', 'markdown'],
    ['text/plain', 'notes.md.txt', 'text'],
    // Only text/plain falls back to the name.
    ['text/csv', 'table.md', 'text'],
    ['application/octet-stream', 'readme.md', null],
    // Parameters and case of the type are ignored.
    ['text/plain; charset=utf-8', 'notes.txt', 'text'],
    ['text/plain;charset=UTF-8', 'notes.md', 'markdown'],
    ['IMAGE/PNG', 'scan.png', 'image'],
    ['  application/pdf ; name="a.pdf"', 'a.pdf', 'pdf'],
    ['Application/JSON; charset=utf-8', 'data.json', 'text'],
    // Unknown, missing and empty types have no preview.
    ['application/zip', 'archive.zip', null],
    ['application/octet-stream', 'blob.bin', null],
    ['application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'a.docx', null],
    ['', 'notes.txt', null],
    [null, 'notes.md', null],
    [undefined, 'photo.png', null],
  ])('%s named %s -> %s', (mime, name, kind) => {
    expect(previewKind(mime, name)).toBe(kind);
  });

  it('works without a name', () => {
    expect(previewKind('text/plain')).toBe('text');
    expect(previewKind('image/png')).toBe('image');
  });
});

describe('hasThumbnail', () => {
  it('is true for previewable images only', () => {
    expect(hasThumbnail('image/jpeg')).toBe(true);
    expect(hasThumbnail('image/png; foo=bar')).toBe(true);
    expect(hasThumbnail('image/svg+xml')).toBe(false);
    expect(hasThumbnail('application/pdf')).toBe(false);
    expect(hasThumbnail('video/mp4')).toBe(false);
    expect(hasThumbnail(null)).toBe(false);
    expect(hasThumbnail(undefined)).toBe(false);
  });
});
