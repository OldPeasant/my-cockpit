import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { map, Observable, of, switchMap, timer } from 'rxjs';
import { Api, search, SearchGroupResponse } from '../../api/generated';
import { SILENT_ERRORS } from '../../core/http';
import { itemTypeLabel } from '../../shared/links/item-type';
import { PaletteItem, PaletteProvider } from '../../shell/shell-tokens';

/** Wait after the last keystroke before asking the server. */
export const SEARCH_DEBOUNCE_MS = 200;

/** Shorter queries (trimmed) find too much to be useful and are not sent. */
export const SEARCH_MIN_LENGTH = 2;

/** Maximum results of one palette search (all groups together). */
export const SEARCH_LIMIT = 20;

/**
 * Global search rows of the command palette (SPEC.md §4.4): `GET /api/v1/search`, one palette group
 * per item type in the server's order, best match first. Choosing a row opens its deep link.
 *
 * Out-of-order safe: the palette drops the subscription of the previous query, which cancels its
 * pending debounce timer or HTTP request, so an older answer can never replace a newer one.
 */
@Injectable()
export class SearchPaletteProvider implements PaletteProvider {
  private readonly http = inject(HttpClient);
  private readonly api = inject(Api);
  private readonly router = inject(Router);

  items(query: string): Observable<readonly PaletteItem[]> {
    const q = query.trim();
    if (q.length < SEARCH_MIN_LENGTH) {
      return of([]);
    }
    // Errors are not toasted: the palette keeps its other rows (e.g. the commands) instead.
    const context = new HttpContext().set(SILENT_ERRORS, true);
    return timer(SEARCH_DEBOUNCE_MS).pipe(
      switchMap(() => search(this.http, this.api.rootUrl, { q, limit: SEARCH_LIMIT }, context)),
      map((response) => response.body.groups.flatMap((group) => this.rows(group))),
    );
  }

  private rows(group: SearchGroupResponse): PaletteItem[] {
    const name = itemTypeLabel(group.type);
    // Items without a deep link belong to a module that cannot show them yet: nothing to open.
    return group.items.flatMap((item) => {
      const deepLink = item.deepLink;
      if (!deepLink) {
        return [];
      }
      return [
        {
          id: `search-${item.type}-${item.id}`,
          group: name,
          label: item.title,
          hint: item.subtitle,
          run: () => void this.router.navigateByUrl(deepLink),
        },
      ];
    });
  }
}
