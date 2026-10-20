import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from './api-error';

function response(status: number, error: unknown): HttpErrorResponse {
  return new HttpErrorResponse({ status, statusText: 'Error', error, url: '/api/tasks' });
}

describe('ApiError', () => {
  it('is an Error whose message is the user message', () => {
    const error = new ApiError(409, 'Conflict', 'The task was changed by someone else.');

    expect(error).toBeInstanceOf(Error);
    expect(error.name).toBe('ApiError');
    expect(error.message).toBe('The task was changed by someone else.');
    expect(error.userMessage).toBe('The task was changed by someone else.');
    expect(error.errors).toEqual([]);
  });

  it('uses the title as message when there is no detail', () => {
    const error = new ApiError(404, 'Not Found');

    expect(error.message).toBe('Not Found');
    expect(error.userMessage).toBe('Not Found');
  });

  describe('from', () => {
    it('keeps the HTTP status over the status in the problem body', () => {
      const error = ApiError.from(response(422, { status: 400, title: 'Bad Request' }));

      expect(error.status).toBe(422);
    });

    it('uses the problem status when the response has none', () => {
      const error = ApiError.from(response(0, { status: 503, title: 'Service Unavailable' }));

      expect(error.status).toBe(503);
      expect(error.title).toBe('Service Unavailable');
    });

    it('ignores non-string optional problem fields', () => {
      const error = ApiError.from(
        response(400, { status: 400, title: 'Bad Request', detail: 42, type: {}, instance: [] }),
      );

      expect(error.title).toBe('Bad Request');
      expect(error.detail).toBeUndefined();
      expect(error.type).toBeUndefined();
      expect(error.instance).toBeUndefined();
      expect(error.userMessage).toBe('Bad Request');
    });

    it('keeps only well-formed field errors', () => {
      const error = ApiError.from(
        response(400, {
          status: 400,
          title: 'Bad Request',
          errors: [
            { field: 'title', message: 'must not be blank' },
            null,
            'due: invalid',
            { field: 'due' },
            { message: 'no field' },
            { field: 1, message: 'numeric field' },
            { field: 'due', message: 'must be an ISO-8601 date' },
          ],
        }),
      );

      expect(error.errors).toEqual([
        { field: 'title', message: 'must not be blank' },
        { field: 'due', message: 'must be an ISO-8601 date' },
      ]);
    });

    it('treats non-array errors as no field errors', () => {
      const error = ApiError.from(
        response(400, { status: 400, title: 'Bad Request', errors: { title: 'blank' } }),
      );

      expect(error.errors).toEqual([]);
    });

    it.each([
      ['a null body', null],
      ['an empty string', ''],
      ['malformed JSON text', '{"status": 400, "title":'],
      ['an HTML error page', '<html>Bad Request</html>'],
      ['a JSON text that is not an object', '"Bad Request"'],
      ['an array', [{ status: 400, title: 'Bad Request' }]],
      ['an object without title', { status: 400, detail: 'Validation failed.' }],
      ['an object without status', { title: 'Bad Request' }],
      ['a string status', { status: '400', title: 'Bad Request' }],
      ['a non-string title', { status: 400, title: 400 }],
    ])('falls back to a generic error for %s', (_case, body) => {
      const error = ApiError.from(response(400, body));

      expect(error.status).toBe(400);
      expect(error.title).toBe('The request failed (HTTP 400).');
      expect(error.detail).toBeUndefined();
      expect(error.errors).toEqual([]);
    });

    it.each([
      [0, 'The server cannot be reached.'],
      [400, 'The request failed (HTTP 400).'],
      [499, 'The request failed (HTTP 499).'],
      [500, 'The server failed to process the request.'],
      [504, 'The server failed to process the request.'],
    ])('uses a generic title for status %i without problem body', (status, title) => {
      const error = ApiError.from(response(status, null));

      expect(error.status).toBe(status);
      expect(error.title).toBe(title);
      expect(error.userMessage).toBe(title);
    });
  });
});
