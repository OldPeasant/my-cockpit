import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  getJournalEntry,
  getJournalHeatmap,
  getJournalOnThisDay,
  JournalEntryRequest,
  JournalEntryResponse,
  JournalHeatmapResponse,
  JournalImageResponse,
  JournalOnThisDayResponse,
  putJournalEntry,
  uploadJournalImage,
} from '../../api/generated';
import { ApiError, SILENT_ERRORS } from '../../core/http';

/** What the journal saves of an entry. */
export interface JournalDraft {
  readonly bodyMd: string;
  /** 1 (bad) to 5 (great); null: no mood. */
  readonly mood: number | null;
  readonly tags: readonly string[];
}

/**
 * The journal (SPEC.md §8.5): one entry per date (`yyyy-MM-dd`), the heatmap and "on this day".
 * Saving an existing entry sends its version as `If-Match`, so an entry changed elsewhere fails with
 * 412 instead of being overwritten; saving a date without an entry creates it. Errors are not
 * toasted: the journal shows them itself.
 */
@Injectable({ providedIn: 'root' })
export class JournalService {
  private readonly api = inject(Api);

  /** The entry of `date`, or null when the date has none. */
  async get(date: string): Promise<JournalEntryResponse | null> {
    try {
      return await this.api.invoke(getJournalEntry, { date }, silent());
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null;
      throw error;
    }
  }

  /** Replaces the content of `entry`, or creates the entry of `date` when there is none (`entry` null). */
  save(
    date: string,
    entry: JournalEntryResponse | null,
    draft: JournalDraft,
  ): Promise<JournalEntryResponse> {
    const body: JournalEntryRequest = {
      bodyMd: draft.bodyMd,
      mood: draft.mood,
      tags: [...draft.tags],
    };
    return this.api.invoke(
      putJournalEntry,
      entry ? { date, 'If-Match': `"${entry.version}"`, body } : { date, body },
      silent(),
    );
  }

  /** Stores an image for the journal; the result has the Markdown to insert. */
  uploadImage(file: File): Promise<JournalImageResponse> {
    return this.api.invoke(uploadJournalImage, { body: { file } }, silent());
  }

  /** The dates with an entry; without a range the server's last 365 days up to today. */
  heatmap(range?: { from: string; to: string }): Promise<JournalHeatmapResponse> {
    return this.api.invoke(getJournalHeatmap, range ?? {}, silent());
  }

  /** The entries of `date`'s day in earlier years; without a date today's (and `date` is today). */
  onThisDay(date?: string): Promise<JournalOnThisDayResponse> {
    return this.api.invoke(getJournalOnThisDay, date ? { date } : {}, silent());
  }
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
