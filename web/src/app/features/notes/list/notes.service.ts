import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  createNote,
  CreateNoteRequest,
  listNotes,
  listNoteTags,
  NoteResponse,
  NoteTagResponse,
  updateNote,
} from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';

/** Page size used while loading the notes: the largest the API allows. */
export const NOTES_PAGE_SIZE = 200;

/** Which notes the overview shows. */
export interface NotesQuery {
  /** Only notes with this tag (regardless of case). */
  readonly tag?: string;
  /** Archived notes instead of the active ones. */
  readonly archived: boolean;
}

/**
 * Notes for the overview (SPEC.md §8.1). The overview searches and sorts on the client, so `list`
 * loads every page. Changes send the note's version as `If-Match`. Errors are not toasted: the
 * Notes view shows them next to the action that failed.
 */
@Injectable({ providedIn: 'root' })
export class NotesService {
  private readonly api = inject(Api);

  /** Every note matching `query`, pinned first, then the most recently changed. */
  async list(query: NotesQuery): Promise<NoteResponse[]> {
    const notes: NoteResponse[] = [];
    let cursor: string | undefined;
    do {
      const page = await this.api.invoke(
        listNotes,
        {
          tag: query.tag,
          archived: query.archived ? 'true' : 'false',
          cursor,
          limit: NOTES_PAGE_SIZE,
        },
        silent(),
      );
      notes.push(...page.items);
      cursor = page.nextCursor ?? undefined;
    } while (cursor);
    return notes;
  }

  /** Every tag in use, with its number of notes. */
  async tags(): Promise<NoteTagResponse[]> {
    return (await this.api.invoke(listNoteTags, undefined, silent())).items;
  }

  create(body: CreateNoteRequest): Promise<NoteResponse> {
    return this.api.invoke(createNote, { body }, silent());
  }

  setPinned(note: NoteResponse, pinned: boolean): Promise<NoteResponse> {
    return this.patch(note, { pinned });
  }

  setArchived(note: NoteResponse, archived: boolean): Promise<NoteResponse> {
    return this.patch(note, { archived });
  }

  private patch(
    note: NoteResponse,
    body: { pinned?: boolean; archived?: boolean },
  ): Promise<NoteResponse> {
    return this.api.invoke(
      updateNote,
      { id: note.id, 'If-Match': `"${note.version}"`, body },
      silent(),
    );
  }
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
