import { HttpErrorResponse } from '@angular/common/http';
import { FieldError, ProblemDetail } from '../../api/generated';

/**
 * A failed API call, mapped from the server's RFC 9457 Problem Details (SPEC.md §4.2) or, when the
 * response has none (network error, proxy error page), from the HTTP status alone.
 */
export class ApiError extends Error {
  override readonly name = 'ApiError';

  constructor(
    /** HTTP status; 0 when the server could not be reached. */
    readonly status: number,
    readonly title: string,
    readonly detail?: string,
    readonly type?: string,
    readonly instance?: string,
    /** Field errors of a 400 validation error, else empty. */
    readonly errors: readonly FieldError[] = [],
  ) {
    super(detail ?? title);
  }

  /** The text to show to the user. */
  get userMessage(): string {
    return this.detail ?? this.title;
  }

  static from(response: HttpErrorResponse): ApiError {
    const problem = asProblem(response.error);
    if (problem) {
      return new ApiError(
        response.status || problem.status,
        problem.title,
        problem.detail,
        problem.type,
        problem.instance,
        problem.errors ?? [],
      );
    }
    return new ApiError(response.status, fallbackTitle(response.status));
  }
}

function asProblem(body: unknown): ProblemDetail | undefined {
  if (typeof body === 'string') {
    try {
      body = JSON.parse(body);
    } catch {
      return undefined;
    }
  }
  if (typeof body !== 'object' || body === null) return undefined;
  const candidate = body as Partial<ProblemDetail>;
  if (typeof candidate.status !== 'number' || typeof candidate.title !== 'string') return undefined;
  return {
    status: candidate.status,
    title: candidate.title,
    detail: typeof candidate.detail === 'string' ? candidate.detail : undefined,
    type: typeof candidate.type === 'string' ? candidate.type : undefined,
    instance: typeof candidate.instance === 'string' ? candidate.instance : undefined,
    errors: Array.isArray(candidate.errors) ? candidate.errors.filter(isFieldError) : undefined,
  };
}

function isFieldError(value: unknown): value is FieldError {
  const error = value as Partial<FieldError> | null;
  return typeof error?.field === 'string' && typeof error.message === 'string';
}

function fallbackTitle(status: number): string {
  if (status === 0) return $localize`:@@http.error.unreachable:The server cannot be reached.`;
  if (status >= 500)
    return $localize`:@@http.error.server:The server failed to process the request.`;
  return $localize`:@@http.error.request:The request failed (HTTP ${status}:status:).`;
}
