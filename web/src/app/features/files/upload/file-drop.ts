import { Directive, output, signal } from '@angular/core';

/**
 * Makes its element a drop target for files from the desktop: emits the dropped files and, while
 * files are dragged over it, adds the `dragging` class (the host template reads `dragging()` via
 * `#drop="appFileDrop"` to show a hint). Drags of anything else (text, links) pass by.
 */
@Directive({
  selector: '[appFileDrop]',
  exportAs: 'appFileDrop',
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
