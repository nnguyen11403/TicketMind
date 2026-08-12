import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { server } from '@/test/server';
import { renderWithProviders } from '@/test/renderApp';
import { LoginPage } from './LoginPage';
import { tokenStore } from '@/api/tokenStore';

const BASE = 'http://api.test';

function unauthenticatedRefresh() {
  server.use(
    http.post(`${BASE}/auth/refresh`, () =>
      HttpResponse.json({ status: 401, code: 'invalid_refresh_token' }, { status: 401 }),
    ),
  );
}

describe('LoginPage', () => {
  it('signs in and navigates to the home route on success', async () => {
    unauthenticatedRefresh();
    server.use(
      http.post(`${BASE}/auth/login`, async ({ request }) => {
        const body = (await request.json()) as { email: string; password: string };
        expect(body).toEqual({ email: 'alice@example.com', password: 'hunter2hunter2' });
        return HttpResponse.json({
          accessToken: 'signed-in-token',
          tokenType: 'Bearer',
          expiresAt: new Date(Date.now() + 900_000).toISOString(),
          user: {
            id: '11111111-1111-1111-1111-111111111111',
            email: 'alice@example.com',
            displayName: 'Alice',
            role: 'USER',
          },
        });
      }),
    );

    renderWithProviders(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<div>home page</div>} />
      </Routes>,
      { route: '/login' },
    );

    await userEvent.type(await screen.findByLabelText(/email/i), 'alice@example.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'hunter2hunter2');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByText('home page')).toBeInTheDocument();
    expect(tokenStore.get()).toBe('signed-in-token');
  });

  it('shows the error envelope message when credentials are wrong', async () => {
    unauthenticatedRefresh();
    server.use(
      http.post(`${BASE}/auth/login`, () =>
        HttpResponse.json({ status: 401, code: 'invalid_credentials' }, { status: 401 }),
      ),
    );

    renderWithProviders(<LoginPage />, { route: '/login' });

    await userEvent.type(await screen.findByLabelText(/email/i), 'alice@example.com');
    await userEvent.type(screen.getByLabelText(/password/i), 'wrongpass12345');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/incorrect/i);
    });
    expect(tokenStore.get()).toBeNull();
  });

  it('validates required fields client-side without hitting the network', async () => {
    unauthenticatedRefresh();
    renderWithProviders(<LoginPage />, { route: '/login' });

    await userEvent.click(await screen.findByRole('button', { name: /sign in/i }));
    expect(await screen.findByText(/valid email/i)).toBeInTheDocument();
    expect(screen.getByText(/password is required/i)).toBeInTheDocument();
  });
});
