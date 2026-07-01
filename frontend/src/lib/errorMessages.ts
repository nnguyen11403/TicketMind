import { ApiError, NetworkError } from '@/api/errors';

const codeMessages: Record<string, string> = {
  invalid_credentials: 'Email or password is incorrect.',
  email_already_exists: 'An account with that email already exists.',
  account_locked: 'This account is temporarily locked. Try again shortly.',
  password_policy: 'That password does not meet the security requirements.',
  invalid_refresh_token: 'Your session has expired. Please sign in again.',
  rate_limit_exceeded: 'Too many attempts. Please slow down and try again.',
  validation_error: 'Please fix the highlighted fields and try again.',
  not_found: 'We could not find that resource.',
  invalid_ticket_state: 'That action is not allowed in the ticket’s current state.',
  agent_required: 'The assignee must be an active agent or admin.',
  internal_error: 'Something went wrong on our end. Please try again shortly.',
  unknown_error: 'Something went wrong. Please try again.',
  network_error: 'Cannot reach the server. Check your connection and retry.',
};

export function formatError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'rate_limit_exceeded' && error.retryAfterSeconds) {
      return `Too many attempts — try again in ${error.retryAfterSeconds}s.`;
    }
    return codeMessages[error.code] ?? codeMessages.unknown_error!;
  }
  if (error instanceof NetworkError) {
    return codeMessages.network_error!;
  }
  return codeMessages.unknown_error!;
}
