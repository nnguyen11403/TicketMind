import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { server } from '@/test/server';
import { renderWithProviders } from '@/test/renderApp';
import { AppShell } from './AppShell';
import { tokenStore } from '@/api/tokenStore';

const BASE = 'http://api.test';

function authedRefresh(role: 'USER' | 'AGENT' | 'ADMIN' = 'USER') {
  server.use(
    http.post(`${BASE}/auth/refresh`, () =>
      HttpResponse.json({
        accessToken: 'test-token',
        accessTokenExpiresIn: 900,
        user: {
          id: '11111111-1111-1111-1111-111111111111',
          email: 'user@example.com',
          displayName: 'Alice',
          role,
        },
      }),
    ),
  );
}

describe('AppShell', () => {
  it('renders the current user and outlet content', async () => {
    authedRefresh('AGENT');
    renderWithProviders(
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/" element={<div>outlet content</div>} />
        </Route>
      </Routes>,
      { route: '/' },
    );
    expect(await screen.findByText('outlet content')).toBeInTheDocument();
    expect(await screen.findByTestId('current-user')).toHaveTextContent(/alice/i);
    expect(screen.getByTestId('current-user')).toHaveTextContent(/agent/);
  });

  it('logs out and clears the in-memory token', async () => {
    authedRefresh('USER');
    server.use(http.post(`${BASE}/auth/logout`, () => HttpResponse.json({})));

    renderWithProviders(
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/" element={<div>outlet</div>} />
        </Route>
      </Routes>,
      { route: '/' },
    );

    await screen.findByTestId('current-user');
    await userEvent.click(screen.getByRole('button', { name: /log out/i }));
    await waitFor(() => expect(tokenStore.get()).toBeNull());
  });
});
