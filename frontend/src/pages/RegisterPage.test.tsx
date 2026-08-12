import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { server } from '@/test/server';
import { renderWithProviders } from '@/test/renderApp';
import { RegisterPage } from './RegisterPage';
import { tokenStore } from '@/api/tokenStore';

const BASE = 'http://api.test';

function anonymousRefresh() {
  server.use(
    http.post(`${BASE}/auth/refresh`, () =>
      HttpResponse.json({ status: 401, code: 'invalid_refresh_token' }, { status: 401 }),
    ),
  );
}

describe('RegisterPage', () => {
  it('registers and redirects to the home route', async () => {
    anonymousRefresh();
    server.use(
      http.post(`${BASE}/auth/register`, async ({ request }) => {
        const body = (await request.json()) as { email: string };
        expect(body.email).toBe('new@example.com');
        return HttpResponse.json({
          accessToken: 'signed-up-token',
          tokenType: 'Bearer',
          expiresAt: new Date(Date.now() + 900_000).toISOString(),
          user: {
            id: '11111111-1111-1111-1111-111111111111',
            email: 'new@example.com',
            displayName: 'New User',
            role: 'USER',
          },
        });
      }),
    );

    renderWithProviders(
      <Routes>
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/" element={<div>home page</div>} />
      </Routes>,
      { route: '/register' },
    );

    await userEvent.type(await screen.findByLabelText(/display name/i), 'New User');
    await userEvent.type(screen.getByLabelText(/email/i), 'new@example.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'hunter2hunter2');
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    expect(await screen.findByText('home page')).toBeInTheDocument();
    expect(tokenStore.get()).toBe('signed-up-token');
  });

  it('enforces the 12-char + letter + digit policy client-side', async () => {
    anonymousRefresh();
    renderWithProviders(<RegisterPage />, { route: '/register' });

    await userEvent.type(await screen.findByLabelText(/display name/i), 'A');
    await userEvent.type(screen.getByLabelText(/email/i), 'good@example.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'shortpwd');
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    expect(await screen.findByText(/at least 12 characters/i)).toBeInTheDocument();
  });

  it('reports server-side errors from the envelope without leaking the raw message', async () => {
    anonymousRefresh();
    server.use(
      http.post(`${BASE}/auth/register`, () =>
        HttpResponse.json({ status: 409, code: 'email_already_exists' }, { status: 409 }),
      ),
    );

    renderWithProviders(<RegisterPage />, { route: '/register' });

    await userEvent.type(await screen.findByLabelText(/display name/i), 'New User');
    await userEvent.type(screen.getByLabelText(/email/i), 'new@example.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'hunter2hunter2');
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(/account with that email/i),
    );
  });
});
