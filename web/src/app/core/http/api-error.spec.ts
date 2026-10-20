import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
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

    describe('retryAfter (Retry-After header)', () => {
      function throttled(retryAfter: string | null, body: unknown = null): HttpErrorResponse {
        return new HttpErrorResponse({
          status: 429,
          statusText: 'Too Many Requests',
          error: body,
          url: '/api/v1/auth/login',
          headers:
            retryAfter === null
              ? new HttpHeaders()
              : new HttpHeaders({ 'Retry-After': retryAfter }),
        });
      }

      it('reads delay-seconds from a response with a problem body', () => {
        const error = ApiError.from(throttled('42', { status: 429, title: 'Too Many Requests' }));

        expect(error.status).toBe(429);
        expect(error.title).toBe('Too Many Requests');
        expect(error.retryAfter).toBe(42);
      });

      it('reads delay-seconds from a response without a problem body', () => {
        const error = ApiError.from(throttled('7'));

        expect(error.title).toBe('The request failed (HTTP 429).');
        expect(error.retryAfter).toBe(7);
      });

      it.each([
        ['0', 0],
        [' 15 ', 15],
        ['900', 900],
      ])('parses %j as %i seconds', (header, seconds) => {
        expect(ApiError.from(throttled(header)).retryAfter).toBe(seconds);
      });

      it.each([
        ['no header', null],
        ['an empty header', ''],
        ['a blank header', '   '],
        ['an HTTP-date', 'Wed, 21 Oct 2026 07:28:00 GMT'],
        ['a negative number', '-5'],
        ['a decimal number', '1.5'],
        ['text', 'soon'],
      ])('is undefined for %s', (_case, header) => {
        expect(ApiError.from(throttled(header)).retryAfter).toBeUndefined();
      });

      it('is undefined when built directly without it', () => {
        expect(new ApiError(429, 'Too Many Requests').retryAfter).toBeUndefined();
      });
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
