import { describe, expect, it } from 'vitest';
import { formatError } from './errorMessages';
import { ApiError, NetworkError } from '@/api/errors';

describe('formatError', () => {
  it('maps known API error codes to human copy', () => {
    expect(formatError(new ApiError({ status: 401, code: 'invalid_credentials' }))).toMatch(
      /email or password/i,
    );
    expect(formatError(new ApiError({ status: 404, code: 'not_found' }))).toMatch(
      /could not find/i,
    );
    expect(formatError(new ApiError({ status: 409, code: 'invalid_ticket_state' }))).toMatch(
      /not allowed/i,
    );
  });

  it('surfaces the retry-after seconds for rate-limit responses', () => {
    const error = new ApiError({ status: 429, code: 'rate_limit_exceeded' }, 12);
    expect(formatError(error)).toMatch(/try again in 12s/i);
  });

  it('falls back to a generic message for unknown API codes', () => {
    expect(formatError(new ApiError({ status: 500, code: 'wat' }))).toMatch(
      /something went wrong/i,
    );
  });

  it('reports network errors distinctly', () => {
    expect(formatError(new NetworkError(new Error('offline')))).toMatch(/cannot reach/i);
  });

  it('falls back to the generic message for unknown throwables', () => {
    expect(formatError('boom')).toMatch(/something went wrong/i);
  });
});
