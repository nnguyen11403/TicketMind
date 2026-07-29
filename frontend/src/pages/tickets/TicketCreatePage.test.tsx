import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { server } from '@/test/server';
import { renderWithProviders } from '@/test/renderApp';
import { TicketCreatePage } from './TicketCreatePage';

const BASE = 'http://api.test';

function authedRefresh() {
  server.use(
    http.post(`${BASE}/auth/refresh`, () =>
      HttpResponse.json({
        accessToken: 'test-token',
        accessTokenExpiresIn: 900,
        user: {
          id: '11111111-1111-1111-1111-111111111111',
          email: 'user@example.com',
          displayName: 'User One',
          role: 'USER',
        },
      }),
    ),
  );
}

describe('TicketCreatePage', () => {
  it('submits and navigates to the new ticket detail page', async () => {
    authedRefresh();
    server.use(
      http.post(`${BASE}/tickets`, async ({ request }) => {
        const body = (await request.json()) as { title: string; description: string };
        expect(body.title).toBe('Cannot log in');
        return HttpResponse.json({
          id: '33333333-3333-3333-3333-333333333333',
          title: body.title,
          description: body.description,
          status: 'OPEN',
          priority: null,
          category: null,
          submitter: {
            id: '11111111-1111-1111-1111-111111111111',
            displayName: 'User One',
            email: 'user@example.com',
          },
          assignee: null,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        });
      }),
    );

    renderWithProviders(
      <Routes>
        <Route path="/tickets/new" element={<TicketCreatePage />} />
        <Route path="/tickets/:id" element={<div>ticket detail</div>} />
      </Routes>,
      { route: '/tickets/new' },
    );

    await userEvent.type(await screen.findByLabelText(/title/i), 'Cannot log in');
    await userEvent.type(screen.getByLabelText(/description/i), 'I get a 500 error on submit.');
    await userEvent.click(screen.getByRole('button', { name: /submit ticket/i }));

    expect(await screen.findByText('ticket detail')).toBeInTheDocument();
  });

  it('shows validation errors when submitted empty', async () => {
    authedRefresh();
    renderWithProviders(<TicketCreatePage />, { route: '/tickets/new' });
    await userEvent.click(await screen.findByRole('button', { name: /submit ticket/i }));
    await waitFor(() => {
      expect(screen.getByText(/title is required/i)).toBeInTheDocument();
      expect(screen.getByText(/description is required/i)).toBeInTheDocument();
    });
  });

  it('surfaces server errors from the envelope', async () => {
    authedRefresh();
    server.use(
      http.post(`${BASE}/tickets`, () =>
        HttpResponse.json({ status: 500, code: 'internal_error' }, { status: 500 }),
      ),
    );

    renderWithProviders(<TicketCreatePage />, { route: '/tickets/new' });
    await userEvent.type(await screen.findByLabelText(/title/i), 'Cannot log in');
    await userEvent.type(screen.getByLabelText(/description/i), 'Details.');
    await userEvent.click(screen.getByRole('button', { name: /submit ticket/i }));

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(/something went wrong/i),
    );
  });
});
