import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Api, downloadFile, getFileThumbnail$Png, previewFile } from '../../../api/generated';
import { SILENT_ERRORS } from '../../../core/http';

/** The text preview of a file: its start, and whether the file goes on beyond it. */
export interface TextPreview {
  text: string;
  truncated: boolean;
}

/** URLs and text of file previews (SPEC.md §8.2), served by `/files/{id}/preview` and `/thumbnail`. */
@Injectable({ providedIn: 'root' })
export class PreviewService {
  private readonly http = inject(HttpClient);
  private readonly rootUrl = inject(Api).rootUrl;

  /** The file inline (images, PDF, audio, video), with `Range` support for media. */
  previewUrl(id: string): string {
    return this.url(previewFile.PATH, id);
  }

  thumbnailUrl(id: string, size: 256 | 1024 = 256): string {
    return `${this.url(getFileThumbnail$Png.PATH, id)}?size=${size}`;
  }

  downloadUrl(id: string): string {
    return this.url(downloadFile.PATH, id);
  }

  /** The first 256 KB of a text file, decoded by the server. */
  async text(id: string): Promise<TextPreview> {
    const response = await firstValueFrom(
      this.http.get(this.previewUrl(id), {
        observe: 'response',
        responseType: 'text',
        context: new HttpContext().set(SILENT_ERRORS, true),
      }),
    );
    return {
      text: response.body ?? '',
      truncated: response.headers.get('X-Preview-Truncated') === 'true',
    };
  }

  private url(path: string, id: string): string {
    return this.rootUrl + path.replace('{id}', encodeURIComponent(id));
  }
}
