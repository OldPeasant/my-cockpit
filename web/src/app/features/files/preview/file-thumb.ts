import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FileResponse } from '../../../api/generated';
import { ItemTypeIcon } from '../../../shared/links';
import { hasThumbnail } from './preview-kind';
import { PreviewService } from './preview.service';

/**
 * The server's thumbnail of an image file (SPEC.md §8.2), else (and when it fails to load) the file
 * icon. In a grid (`.grid` around it) the thumbnail fills the tile.
 */
@Component({
  selector: 'app-file-thumb',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ItemTypeIcon],
  template: `
    @if (src(); as url) {
      <img [src]="url" alt="" loading="lazy" (error)="failed.set(true)" />
    } @else {
      <app-item-type-icon type="FILE" />
    }
  `,
  styles: `
    :host {
      display: contents;
    }

    img {
      flex: none;
      width: 24px;
      height: 24px;
      border-radius: 2px;
      object-fit: cover;
    }

    :host-context(.grid) img {
      width: 100%;
      height: auto;
      aspect-ratio: 4 / 3;
      border-radius: calc(var(--app-radius) / 2);
      background: var(--app-border);
    }
  `,
})
export class FileThumb {
  private readonly previews = inject(PreviewService);

  readonly file = input.required<FileResponse>();

  protected readonly failed = signal(false);
  protected readonly src = computed(() => {
    const file = this.file();
    return hasThumbnail(file.mime) && !this.failed() ? this.previews.thumbnailUrl(file.id) : null;
  });
}
