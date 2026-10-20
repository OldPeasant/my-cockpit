import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  createLink,
  deleteLink,
  ItemRefDto,
  LinkResponse,
  listLinks,
  search,
  SearchGroupResponse,
} from '../../api/generated';
import { SILENT_ERRORS } from '../../core/http';

/** An item type of the universal item references (SPEC.md §4.3). */
export type ItemType = ItemRefDto['type'];

/** Maximum results of one "add link" search. */
export const LINK_SEARCH_LIMIT = 20;

/**
 * Links between items of any type (SPEC.md §4.3) and the search that finds link targets
 * (§4.4). Errors are not toasted: the Linked items panel shows them next to the action that failed.
 */
@Injectable({ providedIn: 'root' })
export class LinksService {
  private readonly api = inject(Api);

  /** The links of an item, in both directions, oldest first. */
  async list(item: ItemRefDto): Promise<LinkResponse[]> {
    const response = await this.api.invoke(listLinks, { type: item.type, id: item.id }, silent());
    return response.items;
  }

  /** Links `source` to `target`; the result is the link as seen from `source`. */
  create(source: ItemRefDto, target: ItemRefDto): Promise<LinkResponse> {
    return this.api.invoke(
      createLink,
      { body: { source: ref(source), target: ref(target) } },
      silent(),
    );
  }

  /** Removes a manual link from both items. */
  async remove(id: string): Promise<void> {
    await this.api.invoke(deleteLink, { id }, silent());
  }

  /** Items of all types matching `query`, grouped by type. */
  async search(query: string): Promise<SearchGroupResponse[]> {
    const response = await this.api.invoke(
      search,
      { q: query, limit: LINK_SEARCH_LIMIT },
      silent(),
    );
    return response.groups;
  }
}

/** Only type and id: callers may pass richer objects. */
function ref(item: ItemRefDto): ItemRefDto {
  return { type: item.type, id: item.id };
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
