import { z } from 'zod';

const fieldErrorSchema = z.object({
  field: z.string(),
  message: z.string(),
});

export const apiErrorSchema = z.object({
  status: z.number(),
  code: z.string(),
  fieldErrors: z.array(fieldErrorSchema).optional(),
});

export type FieldError = z.infer<typeof fieldErrorSchema>;
export type ApiErrorBody = z.infer<typeof apiErrorSchema>;

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: FieldError[];
  readonly retryAfterSeconds: number | null;

  constructor(body: ApiErrorBody, retryAfterSeconds: number | null = null) {
    super(body.code);
    this.name = 'ApiError';
    this.status = body.status;
    this.code = body.code;
    this.fieldErrors = body.fieldErrors ?? [];
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

export class NetworkError extends Error {
  constructor(cause: unknown) {
    super('network_error');
    this.name = 'NetworkError';
    this.cause = cause;
  }
}
