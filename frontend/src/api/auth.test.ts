import { describe, expect, it } from 'vitest';
import { HttpResponse, http } from 'msw';
import { server } from '@/test/server';
import { logout, silentRefresh } from './auth';
import { tokenStore } from './tokenStore';

const BASE = 'http://api.test';

describe('silentRefresh', () => {
  it('sets the token when the backend returns a valid session', async () => {
    server.use(
      http.post(`${BASE}/auth/refresh`, () =>
        HttpResponse.json({
          accessToken: 'refresh-token',
          tokenType: 'Bearer',
          expiresAt: new Date(Date.now() + 900_000).toISOString(),
          user: {
            id: '11111111-1111-1111-1111-111111111111',
            email: 'user@example.com',
            displayName: 'User',
            role: 'USER',
          },
        }),
      ),
    );
    const result = await silentRefresh();
    expect(result?.accessToken).toBe('refresh-token');
    expect(tokenStore.get()).toBe('refresh-token');
  });

  it('clears the token and returns null when the refresh fails', async () => {
    tokenStore.set('stale');
    server.use(
      http.post(`${BASE}/auth/refresh`, () =>
        HttpResponse.json({ status: 401, code: 'invalid_refresh_token' }, { status: 401 }),
      ),
    );
    const result = await silentRefresh();
    expect(result).toBeNull();
    expect(tokenStore.get()).toBeNull();
  });
});

describe('logout', () => {
  it('clears the token even when the backend returns an error', async () => {
    tokenStore.set('to-be-cleared');
    server.use(
      http.post(`${BASE}/auth/logout`, () =>
        HttpResponse.json({ status: 500, code: 'internal_error' }, { status: 500 }),
      ),
    );
    await expect(logout()).rejects.toBeDefined();
    expect(tokenStore.get()).toBeNull();
  });

  it('clears the token on a normal 2xx response', async () => {
    tokenStore.set('to-be-cleared');
    server.use(http.post(`${BASE}/auth/logout`, () => HttpResponse.json({})));
    await logout();
    expect(tokenStore.get()).toBeNull();
  });
});
