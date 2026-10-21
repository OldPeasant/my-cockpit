import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  getNote,
  listNoteRevisions,
  NoteImageResponse,
  NoteResponse,
  NoteRevisionResponse,
  previewFile,
  restoreNoteRevision,
  updateNote,
  uploadNoteImage,
} from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';

/** What the editor saves of a note. */
export interface NoteDraft {
  /** `null` removes the title (the note then shows its first line). */
  readonly title: string | null;
  readonly bodyMd: string;
}

/**
 * One note for the editor (SPEC.md §8.1): load, save, images, revisions. Changes send the note's
 * version as `If-Match`, so a note changed elsewhere fails with 412 instead of being overwritten.
 * Errors are not toasted: the editor shows them in its save status.
 */
@Injectable({ providedIn: 'root' })
export class NoteEditorService {
  private readonly api = inject(Api);

  get(id: string): Promise<NoteResponse> {
    return this.api.invoke(getNote, { id }, silent());
  }

  save(note: NoteResponse, draft: NoteDraft): Promise<NoteResponse> {
    return this.api.invoke(
      updateNote,
      { id: note.id, 'If-Match': etag(note.version), body: draft },
      silent(),
    );
  }

  /** Uploads an image to the note's attachments; the result has the Markdown to insert. */
  uploadImage(noteId: string, file: File): Promise<NoteImageResponse> {
    return this.api.invoke(uploadNoteImage, { id: noteId, body: { file } }, silent());
  }

  /** The URL that shows a file inline; the browser sends the session cookie. */
  fileUrl(fileId: string): string {
    return this.api.rootUrl + previewFile.PATH.replace('{id}', encodeURIComponent(fileId));
  }

  /** The kept earlier versions of a note, newest first. */
  async revisions(noteId: string): Promise<NoteRevisionResponse[]> {
    return (await this.api.invoke(listNoteRevisions, { id: noteId }, silent())).items;
  }

  restore(note: NoteResponse, revision: number): Promise<NoteResponse> {
    return this.api.invoke(
      restoreNoteRevision,
      { id: note.id, number: revision, 'If-Match': etag(note.version) },
      silent(),
    );
  }
}

function etag(version: number): string {
  return `"${version}"`;
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
