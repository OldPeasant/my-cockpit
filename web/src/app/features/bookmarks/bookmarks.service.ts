import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  Api,
  BookmarkPageResponse,
  BookmarkPatchRequest,
  BookmarkResponse,
  BookmarkTagResponse,
  createBookmark,
  CreateBookmarkRequest,
  deleteBookmark,
  getBookmark,
  listBookmarks,
  listBookmarkTags,
  previewFile,
  updateBookmark,
} from '../../api/generated';
import { SILENT_ERRORS } from '../../core/http';

/** Which bookmarks a list shows. */
export type BookmarkView = 'queue' | 'all';

/** The readable copy of a page, as the server's file preview serves it. */
export interface Snapshot {
  text: string;
  markdown: boolean;
  /** The file goes on beyond `text`. */
  truncated: boolean;
}

/**
 * Bookmarks and the read-later queue (SPEC.md §8.4). Changes send the bookmark's version as
 * `If-Match`. Errors are not toasted: the Bookmarks view shows them next to the bookmark.
 */
@Injectable({ providedIn: 'root' })
export class BookmarksService {
  private readonly api = inject(Api);
  private readonly http = inject(HttpClient);

  /**
   * One page of a view, newest first: the read-later queue (in the list and unread), or all
   * bookmarks, of one tag when `tag` is set.
   */
  list(view: BookmarkView, tag: string | null, cursor?: string): Promise<BookmarkPageResponse> {
    const filter = view === 'queue' ? { readLater: true, read: false } : {};
    return this.api.invoke(
      listBookmarks,
      { ...filter, ...(tag ? { tag } : {}), ...(cursor ? { cursor } : {}) },
      silent(),
    );
  }

  async tags(): Promise<BookmarkTagResponse[]> {
    return (await this.api.invoke(listBookmarkTags, undefined, silent())).items;
  }

  get(id: string): Promise<BookmarkResponse> {
    return this.api.invoke(getBookmark, { id }, silent());
  }

  create(body: CreateBookmarkRequest): Promise<BookmarkResponse> {
    return this.api.invoke(createBookmark, { body }, silent());
  }

  update(bookmark: BookmarkResponse, body: BookmarkPatchRequest): Promise<BookmarkResponse> {
    return this.api.invoke(
      updateBookmark,
      { id: bookmark.id, 'If-Match': `"${bookmark.version}"`, body },
      silent(),
    );
  }

  /** Moves the bookmark to the Trash. */
  remove(bookmark: BookmarkResponse): Promise<void> {
    return this.api.invoke(
      deleteBookmark,
      { id: bookmark.id, 'If-Match': `"${bookmark.version}"` },
      silent(),
    );
  }

  /** The readable copy stored as file `fileId`: its first 256 KB, decoded by the server. */
  async snapshot(fileId: string): Promise<Snapshot> {
    const url = this.api.rootUrl + previewFile.PATH.replace('{id}', encodeURIComponent(fileId));
    const response = await firstValueFrom(
      this.http.get(url, { observe: 'response', responseType: 'text', context: silent() }),
    );
    const type = response.headers.get('Content-Type') ?? '';
    return {
      text: response.body ?? '',
      markdown: type.split(';')[0].trim().toLowerCase() === 'text/markdown',
      truncated: response.headers.get('X-Preview-Truncated') === 'true',
    };
  }
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
