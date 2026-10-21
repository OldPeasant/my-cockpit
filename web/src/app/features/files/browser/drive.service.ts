import { HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  Api,
  createFolder,
  deleteFile,
  deleteFolder,
  downloadFile,
  downloadFolder,
  DriveEntryResponse,
  DrivePageResponse,
  DriveUsageResponse,
  FileResponse,
  FolderResponse,
  FolderSummary,
  getDriveUsage,
  getFolder,
  listFolder,
  updateFile,
  updateFolder,
} from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';

/** Page size of a folder listing. */
export const DRIVE_PAGE_SIZE = 100;

/** A folder or a file of a listing, with what both have in common. */
export type DriveEntry =
  | { kind: 'folder'; id: string; name: string; version: number; folder: FolderSummary }
  | { kind: 'file'; id: string; name: string; version: number; file: FileResponse };

/** A folder on the way from the drive root to the shown folder; `id` null is the root. */
export interface Crumb {
  id: string | null;
  name: string;
}

export function toEntry(entry: DriveEntryResponse): DriveEntry | null {
  if (entry.type === 'FOLDER' && entry.folder) {
    const folder = entry.folder;
    return { kind: 'folder', id: folder.id, name: folder.name, version: folder.version, folder };
  }
  if (entry.type === 'FILE' && entry.file) {
    const file = entry.file;
    return { kind: 'file', id: file.id, name: file.name, version: file.version, file };
  }
  return null;
}

/**
 * The drive (SPEC.md §8.2): folder listings, folder and file operations, downloads and disk usage.
 * Changes send the entry's version as `If-Match`. Errors are not toasted: the Files view shows them
 * next to what failed.
 */
@Injectable({ providedIn: 'root' })
export class DriveService {
  private readonly api = inject(Api);

  /** One page of a folder (`null`: the drive root), folders first, then files, by name. */
  async list(
    folderId: string | null,
    cursor?: string,
  ): Promise<{ entries: DriveEntry[]; nextCursor: string | null }> {
    const page: DrivePageResponse = await this.api.invoke(
      listFolder,
      { folderId: folderId ?? undefined, cursor, limit: DRIVE_PAGE_SIZE },
      silent(),
    );
    return {
      entries: page.items.map(toEntry).filter((entry) => entry !== null),
      nextCursor: page.nextCursor ?? null,
    };
  }

  /**
   * All subfolders of a folder. Folders come before files in a listing, so paging stops at the first
   * page that contains a file.
   */
  async subfolders(folderId: string | null): Promise<FolderSummary[]> {
    const folders: FolderSummary[] = [];
    let cursor: string | undefined;
    for (;;) {
      const page = await this.list(folderId, cursor);
      for (const entry of page.entries) if (entry.kind === 'folder') folders.push(entry.folder);
      if (!page.nextCursor || page.entries.some((entry) => entry.kind === 'file')) return folders;
      cursor = page.nextCursor;
    }
  }

  folder(id: string): Promise<FolderResponse> {
    return this.api.invoke(getFolder, { id }, silent());
  }

  /** The drive root, the ancestors of a folder and the folder itself. */
  async crumbs(folderId: string | null): Promise<Crumb[]> {
    const path: Crumb[] = [];
    const seen = new Set<string>();
    let id = folderId;
    // Each folder names only its parent: walk up to the root (guarding against a cycle).
    while (id && !seen.has(id)) {
      seen.add(id);
      const folder = await this.folder(id);
      path.unshift({ id: folder.id, name: folder.name });
      id = folder.parentId ?? null;
    }
    return [{ id: null, name: rootName() }, ...path];
  }

  async createFolder(name: string, parentId: string | null): Promise<FolderResponse> {
    return this.api.invoke(createFolder, { body: { name, parentId } }, silent());
  }

  async rename(entry: DriveEntry, name: string): Promise<void> {
    const ifMatch = etag(entry.version);
    if (entry.kind === 'folder') {
      await this.api.invoke(
        updateFolder,
        { id: entry.id, 'If-Match': ifMatch, body: { name } },
        silent(),
      );
    } else {
      await this.api.invoke(
        updateFile,
        { id: entry.id, 'If-Match': ifMatch, body: { name } },
        silent(),
      );
    }
  }

  /** Moves an entry into `targetId` (`null`: the drive root). */
  async move(entry: DriveEntry, targetId: string | null): Promise<void> {
    const ifMatch = etag(entry.version);
    if (entry.kind === 'folder') {
      await this.api.invoke(
        updateFolder,
        { id: entry.id, 'If-Match': ifMatch, body: { parentId: targetId } },
        silent(),
      );
    } else {
      await this.api.invoke(
        updateFile,
        { id: entry.id, 'If-Match': ifMatch, body: { folderId: targetId } },
        silent(),
      );
    }
  }

  /** Moves an entry (a folder with its content) to the Trash. */
  async delete(entry: DriveEntry): Promise<void> {
    const params = { id: entry.id, 'If-Match': etag(entry.version) };
    if (entry.kind === 'folder') await this.api.invoke(deleteFolder, params, silent());
    else await this.api.invoke(deleteFile, params, silent());
  }

  usage(): Promise<DriveUsageResponse> {
    return this.api.invoke(getDriveUsage, undefined, silent());
  }

  /**
   * The download URL of a file's content, or of a folder as a zip. The browser streams it to disk
   * with the session cookie, so large files never pass through memory.
   */
  downloadUrl(entry: Pick<DriveEntry, 'kind' | 'id'>): string {
    const path = entry.kind === 'folder' ? downloadFolder.PATH : downloadFile.PATH;
    return this.api.rootUrl + path.replace('{id}', encodeURIComponent(entry.id));
  }
}

/** The name of the drive root in breadcrumbs and pickers. */
export function rootName(): string {
  return $localize`:@@files.root:My drive`;
}

function etag(version: number): string {
  return `"${version}"`;
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
