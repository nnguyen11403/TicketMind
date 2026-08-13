import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { server } from '@/test/server';
import { renderWithProviders } from '@/test/renderApp';
import { TicketListPage } from './TicketListPage';

const BASE = 'http://api.test';

function authedRefresh(role: 'USER' | 'AGENT' | 'ADMIN' = 'USER') {
  server.use(
    http.post(`${BASE}/auth/refresh`, () =>
      HttpResponse.json({
        accessToken: 'test-token',
        tokenType: 'Bearer',
        expiresAt: new Date(Date.now() + 900_000).toISOString(),
        user: {
          id: '11111111-1111-1111-1111-111111111111',
          email: 'user@example.com',
          displayName: 'User One',
          role,
        },
      }),
    ),
  );
}

function ticketPage(status: string | undefined, page: number) {
  return {
    content: [
      {
        id: '22222222-2222-2222-2222-222222222222',
        title: `Ticket page ${page}${status ? ` (${status})` : ''}`,
        status: status ?? 'OPEN',
        priority: 'MEDIUM',
        category: null,
        submitter: {
          id: '11111111-1111-1111-1111-111111111111',
          displayName: 'User One',
          email: 'user@example.com',
        },
        assignee: null,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
    ],
    page,
    size: 20,
    totalElements: 30,
    totalPages: 2,
  };
}

describe('TicketListPage', () => {
  it('lists tickets and paginates via query string', async () => {
    authedRefresh('USER');
    server.use(
      http.get(`${BASE}/tickets`, ({ request }) => {
        const url = new URL(request.url);
        const page = Number(url.searchParams.get('page') ?? '0');
        const status = url.searchParams.get('status') ?? undefined;
        return HttpResponse.json(ticketPage(status, page));
      }),
    );

    renderWithProviders(
      <Routes>
        <Route path="/tickets" element={<TicketListPage />} />
      </Routes>,
      { route: '/tickets' },
    );

    expect(await screen.findByText('Ticket page 0')).toBeInTheDocument();
    expect(screen.getByText(/Page 1 of 2 · 30 total/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Ticket page 1')).toBeInTheDocument();
  });

  it('filters by status when a chip is clicked', async () => {
    authedRefresh('AGENT');
    server.use(
      http.get(`${BASE}/tickets`, ({ request }) => {
        const url = new URL(request.url);
        const status = url.searchParams.get('status') ?? undefined;
        return HttpResponse.json(ticketPage(status, 0));
      }),
    );

    renderWithProviders(
      <Routes>
        <Route path="/tickets" element={<TicketListPage />} />
      </Routes>,
      { route: '/tickets' },
    );

    expect(await screen.findByText('Ticket page 0')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'in progress' }));
    await waitFor(() =>
      expect(screen.getByText(/Ticket page 0 \(IN_PROGRESS\)/)).toBeInTheDocument(),
    );
  });

  it('renders an empty state when there are no tickets', async () => {
    authedRefresh('USER');
    server.use(
      http.get(`${BASE}/tickets`, () =>
        HttpResponse.json({
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
        }),
      ),
    );

    renderWithProviders(<TicketListPage />, { route: '/tickets' });
    expect(await screen.findByText(/haven't opened any tickets/i)).toBeInTheDocument();
  });

  it('sends the chosen sort to the API', async () => {
    authedRefresh('AGENT');
    const seen: string[] = [];
    server.use(
      http.get(`${BASE}/tickets`, ({ request }) => {
        seen.push(new URL(request.url).searchParams.get('sort') ?? 'none');
        return HttpResponse.json(ticketPage(undefined, 0));
      }),
    );

    renderWithProviders(
      <Routes>
        <Route path="/tickets" element={<TicketListPage />} />
      </Routes>,
      { route: '/tickets' },
    );

    await waitFor(() => expect(seen).toContain('NEWEST'));

    await userEvent.selectOptions(await screen.findByLabelText(/sort/i), 'PRIORITY_HIGH_FIRST');

    await waitFor(() => expect(seen).toContain('PRIORITY_HIGH_FIRST'));
  });

  it('restores the sort from the URL on load', async () => {
    authedRefresh('AGENT');
    const seen: string[] = [];
    server.use(
      http.get(`${BASE}/tickets`, ({ request }) => {
        seen.push(new URL(request.url).searchParams.get('sort') ?? 'none');
        return HttpResponse.json(ticketPage(undefined, 0));
      }),
    );

    renderWithProviders(
      <Routes>
        <Route path="/tickets" element={<TicketListPage />} />
      </Routes>,
      { route: '/tickets?sort=PRIORITY_LOW_FIRST' },
    );

    await waitFor(() => expect(seen).toContain('PRIORITY_LOW_FIRST'));
    expect(await screen.findByLabelText(/sort/i)).toHaveValue('PRIORITY_LOW_FIRST');
  });
});
