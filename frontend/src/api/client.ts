import { env } from '@/lib/env';
import { ApiError, NetworkError, apiErrorSchema } from './errors';
import { tokenStore } from './tokenStore';

export type HttpMethod = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';

export interface RequestOptions {
  method?: HttpMethod;
  body?: unknown;
  signal?: AbortSignal;
  // When true, don't attach the Authorization header and don't try to refresh.
  // Used for the auth endpoints themselves so we don't recurse.
  skipAuth?: boolean;
}

interface RefreshResult {
  accessToken: string;
}

// Serialise concurrent refresh attempts — many stale requests firing at once
// would otherwise trigger the backend's family-revocation path.
let refreshInflight: Promise<RefreshResult | null> | null = null;

async function refreshAccessToken(): Promise<RefreshResult | null> {
  if (refreshInflight) {
    return refreshInflight;
  }
  refreshInflight = (async () => {
    try {
      const response = await fetch(`${env.apiBaseUrl}/auth/refresh`, {
        method: 'POST',
        credentials: 'include',
      });
      if (!response.ok) {
        return null;
      }
      const json = (await response.json()) as { accessToken?: unknown };
      if (typeof json.accessToken !== 'string') {
        return null;
      }
      tokenStore.set(json.accessToken);
      return { accessToken: json.accessToken };
    } catch {
      return null;
    } finally {
      refreshInflight = null;
    }
  })();
  return refreshInflight;
}

export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, signal, skipAuth = false } = options;
  const url = path.startsWith('http') ? path : `${env.apiBaseUrl}${path}`;

  const attempt = async (token: string | null): Promise<Response> => {
    const headers: Record<string, string> = {
      Accept: 'application/json',
    };
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }
    try {
      return await fetch(url, {
        method,
        headers,
        credentials: 'include',
        body: body === undefined ? null : JSON.stringify(body),
        signal,
      });
    } catch (cause) {
      throw new NetworkError(cause);
    }
  };

  let response = await attempt(skipAuth ? null : tokenStore.get());

  if (response.status === 401 && !skipAuth) {
    const refreshed = await refreshAccessToken();
    if (refreshed) {
      response = await attempt(refreshed.accessToken);
    } else {
      tokenStore.clear();
    }
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const contentType = response.headers.get('content-type') ?? '';
  const isJson = contentType.includes('application/json');
  const payload: unknown = isJson ? await response.json().catch(() => null) : null;

  if (!response.ok) {
    const retryAfter = response.headers.get('retry-after');
    const parsedError = apiErrorSchema.safeParse(payload);
    if (parsedError.success) {
      throw new ApiError(parsedError.data, retryAfter ? Number(retryAfter) : null);
    }
    throw new ApiError(
      { status: response.status, code: 'unknown_error' },
      retryAfter ? Number(retryAfter) : null,
    );
  }

  return payload as T;
}
