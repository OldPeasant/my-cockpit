import { ApiError } from '../../../core/http';

/** The text for a failed request: field errors of a validation error, a conflict, or the problem. */
export function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return $localize`:@@mailSettings.error:Something went wrong.`;
  }
  if (error.status === 409 || error.status === 412) {
    return $localize`:@@mailSettings.changedElsewhere:It was changed elsewhere. Reload the page to see the current state, then try again.`;
  }
  if (error.errors.length) {
    return error.errors.map((e) => `${e.field}: ${e.message}`).join('; ');
  }
  return error.userMessage;
}
