import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { server } from '@/test/server';
import { renderWithProviders } from '@/test/renderApp';
import { App } from './App';

const BASE = 'http://api.test';

function anonymousRefresh() {
  server.use(
    http.post(`${BASE}/auth/refresh`, () =>
      HttpResponse.json({ status: 401, code: 'invalid_refresh_token' }, { status: 401 }),
    ),
  );
}

describe('App routing', () => {
  it('redirects anonymous visitors from / to /login', async () => {
    anonymousRefresh();
    renderWithProviders(<App />, { route: '/' });
    expect(await screen.findByRole('heading', { name: /sign in/i })).toBeInTheDocument();
  });

  it('shows the not-found page for an unknown route', async () => {
    anonymousRefresh();
    renderWithProviders(<App />, { route: '/no-such-page' });
    expect(await screen.findByRole('heading', { name: /page not found/i })).toBeInTheDocument();
  });

  it('renders the register page publicly', async () => {
    anonymousRefresh();
    renderWithProviders(<App />, { route: '/register' });
    expect(
      await screen.findByRole('heading', { name: /create your account/i }),
    ).toBeInTheDocument();
  });
});
