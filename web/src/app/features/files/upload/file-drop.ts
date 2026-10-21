import { ChangeDetectionStrategy, Component, output, signal } from '@angular/core';

/**
 * Makes its element a drop target for files from the desktop: emits the dropped files and, while
 * files are dragged over it, outlines it and says that they upload here. Drags of anything else
 * (text, links) pass by.
 */
@Component({
  selector: '[appFileDrop]',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ng-content />
    @if (dragging()) {
      <p class="drop-hint" aria-hidden="true" i18n="@@files.upload.dropHint">
        Drop to upload here
      </p>
    }
  `,
  styles: `
    :host {
      position: relative;
      display: block;
      min-height: 12rem;
      border-radius: var(--app-radius);
      outline: 2px dashed transparent;
      outline-offset: 4px;
    }

    :host(.dragging) {
      outline-color: var(--app-accent);
    }

    .drop-hint {
      position: absolute;
      inset: auto 0 var(--app-space-3);
      margin: 0;
      color: var(--app-accent);
      font: var(--mat-sys-title-small);
      text-align: center;
      pointer-events: none;
    }
  `,
  host: {
    '[class.dragging]': 'dragging()',
    '(dragenter)': 'onDragOver($event)',
    '(dragover)': 'onDragOver($event)',
    '(dragleave)': 'onDragLeave($event)',
    '(drop)': 'onDrop($event)',
  },
})
export class FileDrop {
  readonly filesDropped = output<File[]>({ alias: 'appFileDrop' });
  readonly dragging = signal(false);

  protected onDragOver(event: DragEvent): void {
    if (!event.dataTransfer?.types.includes('Files')) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
    this.dragging.set(true);
  }

  protected onDragLeave(event: DragEvent): void {
    // Leaving for a child element is still inside the target.
    const target = event.currentTarget as Node | null;
    const next = event.relatedTarget as Node | null;
    if (target && next && target.contains(next)) return;
    this.dragging.set(false);
  }

  protected onDrop(event: DragEvent): void {
    this.dragging.set(false);
    const files = event.dataTransfer?.files;
    if (!files?.length) return;
    event.preventDefault();
    this.filesDropped.emit(Array.from(files));
  }
}
