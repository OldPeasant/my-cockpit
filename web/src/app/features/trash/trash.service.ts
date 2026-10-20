import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  listTrash,
  purgeTrashItem,
  restoreTrashItem,
  TrashItemResponse,
  TrashPageResponse,
} from '../../api/generated';
import { SILENT_ERRORS } from '../../core/http';

/** Page size of the Trash list. */
export const TRASH_PAGE_SIZE = 50;

/**
 * Deleted items of all types (SPEC.md §4.2). Errors are not toasted: the Trash view shows them next
 * to the action that failed.
 */
@Injectable({ providedIn: 'root' })
export class TrashService {
  private readonly api = inject(Api);

  /** One page of the Trash, most recently deleted first. */
  list(cursor?: string): Promise<TrashPageResponse> {
    return this.api.invoke(listTrash, { cursor, limit: TRASH_PAGE_SIZE }, silent());
  }

  /** Puts an item back where it was. */
  async restore(item: Pick<TrashItemResponse, 'type' | 'id'>): Promise<void> {
    await this.api.invoke(restoreTrashItem, { type: item.type, id: item.id }, silent());
  }

  /** Deletes an item and its links permanently. */
  async purge(item: Pick<TrashItemResponse, 'type' | 'id'>): Promise<void> {
    await this.api.invoke(purgeTrashItem, { type: item.type, id: item.id }, silent());
  }
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
