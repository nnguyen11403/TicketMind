import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { server } from '@/test/server';
import { renderWithProviders } from '@/test/renderApp';
import { ProtectedRoute } from './ProtectedRoute';

const BASE = 'http://api.test';

describe('ProtectedRoute', () => {
  it('renders children when the silent refresh succeeds', async () => {
    server.use(
      http.post(`${BASE}/auth/refresh`, () =>
        HttpResponse.json({
          accessToken: 'ok-token',
          tokenType: 'Bearer',
          expiresAt: new Date(Date.now() + 900_000).toISOString(),
          user: {
            id: '22222222-2222-2222-2222-222222222222',
            email: 'alice@example.com',
            displayName: 'Alice',
            role: 'USER',
          },
        }),
      ),
    );

    renderWithProviders(
      <Routes>
        <Route
          path="/"
          element={
            <ProtectedRoute>
              <div>secret</div>
            </ProtectedRoute>
          }
        />
        <Route path="/login" element={<div>login page</div>} />
      </Routes>,
      { route: '/' },
    );

    expect(await screen.findByText('secret')).toBeInTheDocument();
  });

  it('redirects to /login when the silent refresh fails', async () => {
    server.use(
      http.post(`${BASE}/auth/refresh`, () =>
        HttpResponse.json({ status: 401, code: 'invalid_refresh_token' }, { status: 401 }),
      ),
    );

    renderWithProviders(
      <Routes>
        <Route
          path="/"
          element={
            <ProtectedRoute>
              <div>secret</div>
            </ProtectedRoute>
          }
        />
        <Route path="/login" element={<div>login page</div>} />
      </Routes>,
      { route: '/' },
    );

    expect(await screen.findByText('login page')).toBeInTheDocument();
  });

  it('redirects to home when the user role is not allowed', async () => {
    server.use(
      http.post(`${BASE}/auth/refresh`, () =>
        HttpResponse.json({
          accessToken: 'ok-token',
          tokenType: 'Bearer',
          expiresAt: new Date(Date.now() + 900_000).toISOString(),
          user: {
            id: '33333333-3333-3333-3333-333333333333',
            email: 'user@example.com',
            displayName: 'Regular User',
            role: 'USER',
          },
        }),
      ),
    );

    renderWithProviders(
      <Routes>
        <Route
          path="/admin"
          element={
            <ProtectedRoute roles={['ADMIN']}>
              <div>admin console</div>
            </ProtectedRoute>
          }
        />
        <Route path="/" element={<div>regular home</div>} />
      </Routes>,
      { route: '/admin' },
    );

    expect(await screen.findByText('regular home')).toBeInTheDocument();
  });
});
