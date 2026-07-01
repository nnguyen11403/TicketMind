import { describe, expect, it } from 'vitest';
import { HttpResponse, http } from 'msw';
import { server } from '@/test/server';
import { apiFetch } from './client';
import { ApiError } from './errors';
import { tokenStore } from './tokenStore';

const BASE = 'http://api.test';

describe('apiFetch', () => {
  it('attaches the access token when present', async () => {
    tokenStore.set('token-abc');
    let seen: string | null = null;
    server.use(
      http.get(`${BASE}/tickets`, ({ request }) => {
        seen = request.headers.get('authorization');
        return HttpResponse.json({ ok: true });
      }),
    );
    const result = await apiFetch<{ ok: boolean }>('/tickets');
    expect(result).toEqual({ ok: true });
    expect(seen).toBe('Bearer token-abc');
  });

  it('parses the API error envelope', async () => {
    server.use(
      http.get(`${BASE}/tickets/1`, () =>
        HttpResponse.json({ status: 404, code: 'not_found' }, { status: 404 }),
      ),
    );
    await expect(apiFetch('/tickets/1')).rejects.toMatchObject({
      name: 'ApiError',
      status: 404,
      code: 'not_found',
    });
  });

  it('surfaces retryAfter for rate-limit responses', async () => {
    server.use(
      http.post(`${BASE}/auth/login`, () =>
        HttpResponse.json(
          { status: 429, code: 'rate_limit_exceeded' },
          { status: 429, headers: { 'Retry-After': '17' } },
        ),
      ),
    );
    try {
      await apiFetch('/auth/login', {
        method: 'POST',
        body: { email: 'a@b.co', password: 'x' },
        skipAuth: true,
      });
      throw new Error('expected throw');
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).retryAfterSeconds).toBe(17);
    }
  });

  it('refreshes the access token on 401 then retries once', async () => {
    tokenStore.set('stale-token');
    let refreshCalls = 0;
    let ticketAttempts = 0;
    server.use(
      http.post(`${BASE}/auth/refresh`, () => {
        refreshCalls += 1;
        return HttpResponse.json({ accessToken: 'fresh-token' });
      }),
      http.get(`${BASE}/tickets`, ({ request }) => {
        ticketAttempts += 1;
        const auth = request.headers.get('authorization');
        if (auth === 'Bearer fresh-token') {
          return HttpResponse.json({ items: [] });
        }
        return HttpResponse.json({ status: 401, code: 'invalid_refresh_token' }, { status: 401 });
      }),
    );
    const result = await apiFetch<{ items: unknown[] }>('/tickets');
    expect(result).toEqual({ items: [] });
    expect(refreshCalls).toBe(1);
    expect(ticketAttempts).toBe(2);
    expect(tokenStore.get()).toBe('fresh-token');
  });

  it('clears the token when refresh itself fails', async () => {
    tokenStore.set('stale-token');
    server.use(
      http.post(`${BASE}/auth/refresh`, () =>
        HttpResponse.json({ status: 401, code: 'invalid_refresh_token' }, { status: 401 }),
      ),
      http.get(`${BASE}/tickets`, () =>
        HttpResponse.json({ status: 401, code: 'invalid_refresh_token' }, { status: 401 }),
      ),
    );
    await expect(apiFetch('/tickets')).rejects.toMatchObject({ status: 401 });
    expect(tokenStore.get()).toBeNull();
  });

  it('serialises concurrent refresh attempts into a single request', async () => {
    tokenStore.set('stale-token');
    let refreshCalls = 0;
    server.use(
      http.post(`${BASE}/auth/refresh`, async () => {
        refreshCalls += 1;
        await new Promise((r) => setTimeout(r, 20));
        return HttpResponse.json({ accessToken: 'fresh' });
      }),
      http.get(`${BASE}/a`, ({ request }) =>
        request.headers.get('authorization') === 'Bearer fresh'
          ? HttpResponse.json({ ok: 'a' })
          : HttpResponse.json({ status: 401, code: 'x' }, { status: 401 }),
      ),
      http.get(`${BASE}/b`, ({ request }) =>
        request.headers.get('authorization') === 'Bearer fresh'
          ? HttpResponse.json({ ok: 'b' })
          : HttpResponse.json({ status: 401, code: 'x' }, { status: 401 }),
      ),
    );
    const [a, b] = await Promise.all([apiFetch('/a'), apiFetch('/b')]);
    expect(a).toEqual({ ok: 'a' });
    expect(b).toEqual({ ok: 'b' });
    expect(refreshCalls).toBe(1);
  });
});
