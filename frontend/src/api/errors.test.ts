import { describe, expect, it } from 'vitest';
import { ApiError, NetworkError, apiErrorSchema } from './errors';

describe('ApiError', () => {
  it('captures status, code, field errors, and Retry-After', () => {
    const err = new ApiError(
      {
        status: 400,
        code: 'validation_error',
        fieldErrors: [{ field: 'email', message: 'required' }],
      },
      42,
    );
    expect(err).toBeInstanceOf(Error);
    expect(err.status).toBe(400);
    expect(err.code).toBe('validation_error');
    expect(err.fieldErrors).toEqual([{ field: 'email', message: 'required' }]);
    expect(err.retryAfterSeconds).toBe(42);
  });

  it('defaults field errors to an empty array and retry-after to null', () => {
    const err = new ApiError({ status: 500, code: 'internal_error' });
    expect(err.fieldErrors).toEqual([]);
    expect(err.retryAfterSeconds).toBeNull();
  });
});

describe('NetworkError', () => {
  it('wraps the underlying cause', () => {
    const cause = new Error('offline');
    const err = new NetworkError(cause);
    expect(err).toBeInstanceOf(Error);
    expect(err.name).toBe('NetworkError');
    expect(err.cause).toBe(cause);
  });
});

describe('apiErrorSchema', () => {
  it('accepts a minimal envelope', () => {
    expect(apiErrorSchema.safeParse({ status: 400, code: 'x' }).success).toBe(true);
  });

  it('rejects payloads missing required fields', () => {
    expect(apiErrorSchema.safeParse({ code: 'x' }).success).toBe(false);
    expect(apiErrorSchema.safeParse({ status: 400 }).success).toBe(false);
  });
});
