import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FileResponse } from '../../../api/generated';
import { provideHttp } from '../../../core/http';
import { FileThumb } from './file-thumb';

function file(mime: string): FileResponse {
  return {
    id: '0192f0c4-0000-7000-8000-000000000201',
    name: 'Beach.png',
    mime,
    size: 1234,
    sha256: 'ab'.repeat(32),
    version: 0,
    createdAt: '2026-10-21T08:00:00Z',
    updatedAt: '2026-10-21T08:00:00Z',
  };
}

describe('FileThumb', () => {
  let fixture: ComponentFixture<FileThumb>;

  function create(mime: string): HTMLElement {
    // The app's API root ('' : same origin).
    TestBed.configureTestingModule({ providers: [provideHttp()] });
    fixture = TestBed.createComponent(FileThumb);
    fixture.componentRef.setInput('file', file(mime));
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the server thumbnail of an image, lazily', () => {
    const el = create('image/png');
    const img = el.querySelector('img')!;
    expect(img.getAttribute('src')).toBe(
      '/api/v1/files/0192f0c4-0000-7000-8000-000000000201/thumbnail?size=256',
    );
    expect(img.getAttribute('loading')).toBe('lazy');
    expect(img.getAttribute('alt')).toBe('');
    expect(el.querySelector('app-item-type-icon')).toBeNull();
  });

  it('falls back to the file icon when the thumbnail fails', () => {
    const el = create('image/jpeg');
    el.querySelector('img')!.dispatchEvent(new Event('error'));
    fixture.detectChanges();
    expect(el.querySelector('img')).toBeNull();
    expect(el.querySelector('app-item-type-icon')).not.toBeNull();
  });

  it.each(['application/pdf', 'text/plain', 'image/svg+xml', 'video/mp4'])(
    'shows the file icon for %s',
    (mime) => {
      const el = create(mime);
      expect(el.querySelector('img')).toBeNull();
      expect(el.querySelector('app-item-type-icon')).not.toBeNull();
    },
  );
});
