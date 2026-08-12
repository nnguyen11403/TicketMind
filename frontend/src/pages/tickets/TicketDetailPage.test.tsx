import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { server } from '@/test/server';
import { renderWithProviders } from '@/test/renderApp';
import { TicketDetailPage } from './TicketDetailPage';

const BASE = 'http://api.test';
const TICKET_ID = '33333333-3333-3333-3333-333333333333';
const SUBMITTER_ID = '11111111-1111-1111-1111-111111111111';
const AGENT_ID = '22222222-2222-2222-2222-222222222222';

interface UserOpts {
  role: 'USER' | 'AGENT' | 'ADMIN';
  id: string;
  displayName: string;
}

function authedRefresh(user: UserOpts) {
  server.use(
    http.post(`${BASE}/auth/refresh`, () =>
      HttpResponse.json({
        accessToken: 'test-token',
        tokenType: 'Bearer',
        expiresAt: new Date(Date.now() + 900_000).toISOString(),
        user: {
          id: user.id,
          email: `${user.displayName.toLowerCase()}@example.com`,
          displayName: user.displayName,
          role: user.role,
        },
      }),
    ),
  );
}

function ticket(
  overrides: Partial<{
    status: string;
    assignee: { id: string; displayName: string; email: string } | null;
    title: string;
    description: string;
    category: string | null;
    suggestedResolution: string | null;
    triagedAt: string | null;
    createdAt: string;
  }> = {},
) {
  return {
    id: TICKET_ID,
    title: overrides.title ?? 'Cannot log in',
    description: overrides.description ?? 'I keep getting a 500.',
    status: overrides.status ?? 'OPEN',
    priority: 'MEDIUM',
    category: overrides.category ?? null,
    suggestedResolution: overrides.suggestedResolution ?? null,
    triagedAt: overrides.triagedAt ?? null,
    submitter: {
      id: SUBMITTER_ID,
      displayName: 'Alice',
      email: 'alice@example.com',
    },
    assignee: overrides.assignee ?? null,
    createdAt: overrides.createdAt ?? new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
}

function history() {
  return [
    {
      id: '44444444-4444-4444-4444-444444444444',
      eventType: 'CREATED',
      actor: {
        id: SUBMITTER_ID,
        displayName: 'Alice',
        email: 'alice@example.com',
      },
      payload: '{}',
      createdAt: new Date().toISOString(),
    },
  ];
}

describe('TicketDetailPage', () => {
  it('polls an untriaged ticket and renders the suggestion once triage lands', async () => {
    authedRefresh({ id: SUBMITTER_ID, displayName: 'Alice', role: 'USER' });
    let calls = 0;
    server.use(
      http.get(`${BASE}/tickets/${TICKET_ID}`, () => {
        calls += 1;
        // Triage runs asynchronously on the backend, so the first read of a
        // freshly created ticket has no result yet.
        return HttpResponse.json(
          calls === 1
            ? ticket()
            : ticket({
                category: 'auth',
                suggestedResolution: 'Reset the password and resend the link.',
                triagedAt: new Date().toISOString(),
              }),
        );
      }),
      http.get(`${BASE}/tickets/${TICKET_ID}/history`, () => HttpResponse.json(history())),
    );

    renderWithProviders(
      <Routes>
        <Route path="/tickets/:id" element={<TicketDetailPage />} />
      </Routes>,
      { route: `/tickets/${TICKET_ID}` },
    );

    expect(await screen.findByText(/analysing this ticket/i)).toBeInTheDocument();
    expect(
      await screen.findByText('Reset the password and resend the link.', undefined, {
        timeout: 8000,
      }),
    ).toBeInTheDocument();
    expect(screen.queryByText(/analysing this ticket/i)).not.toBeInTheDocument();
  }, 15000);

  it('does not poll or show a pending hint for a ticket that is already triaged', async () => {
    authedRefresh({ id: SUBMITTER_ID, displayName: 'Alice', role: 'USER' });
    server.use(
      http.get(`${BASE}/tickets/${TICKET_ID}`, () =>
        HttpResponse.json(
          ticket({
            category: 'auth',
            suggestedResolution: 'Reset the password.',
            triagedAt: new Date().toISOString(),
          }),
        ),
      ),
      http.get(`${BASE}/tickets/${TICKET_ID}/history`, () => HttpResponse.json(history())),
    );

    renderWithProviders(
      <Routes>
        <Route path="/tickets/:id" element={<TicketDetailPage />} />
      </Routes>,
      { route: `/tickets/${TICKET_ID}` },
    );

    expect(await screen.findByText('Reset the password.')).toBeInTheDocument();
    expect(screen.queryByText(/analysing this ticket/i)).not.toBeInTheDocument();
  });

  it('gives up on the pending hint for an old ticket the RAG service never triaged', async () => {
    authedRefresh({ id: SUBMITTER_ID, displayName: 'Alice', role: 'USER' });
    const longAgo = new Date(Date.now() - 60 * 60 * 1000).toISOString();
    server.use(
      http.get(`${BASE}/tickets/${TICKET_ID}`, () =>
        HttpResponse.json(ticket({ createdAt: longAgo })),
      ),
      http.get(`${BASE}/tickets/${TICKET_ID}/history`, () => HttpResponse.json(history())),
    );

    renderWithProviders(
      <Routes>
        <Route path="/tickets/:id" element={<TicketDetailPage />} />
      </Routes>,
      { route: `/tickets/${TICKET_ID}` },
    );

    expect(await screen.findByRole('heading', { name: 'Cannot log in' })).toBeInTheDocument();
    expect(screen.queryByText(/analysing this ticket/i)).not.toBeInTheDocument();
  });

  it('shows the ticket header, description, and timeline for the submitter', async () => {
    authedRefresh({ id: SUBMITTER_ID, displayName: 'Alice', role: 'USER' });
    server.use(
      http.get(`${BASE}/tickets/${TICKET_ID}`, () => HttpResponse.json(ticket())),
      http.get(`${BASE}/tickets/${TICKET_ID}/history`, () => HttpResponse.json(history())),
    );

    renderWithProviders(
      <Routes>
        <Route path="/tickets/:id" element={<TicketDetailPage />} />
      </Routes>,
      { route: `/tickets/${TICKET_ID}` },
    );

    expect(await screen.findByRole('heading', { name: 'Cannot log in' })).toBeInTheDocument();
    expect(screen.getByText('I keep getting a 500.')).toBeInTheDocument();
    expect(await screen.findByText(/opened this ticket/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /edit ticket/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /close ticket/i })).toBeInTheDocument();
    expect(screen.queryByText(/Staff actions/i)).not.toBeInTheDocument();
  });

  it('lets a submitter edit an open ticket', async () => {
    authedRefresh({ id: SUBMITTER_ID, displayName: 'Alice', role: 'USER' });
    let currentTitle = 'Cannot log in';
    server.use(
      http.get(`${BASE}/tickets/${TICKET_ID}`, () =>
        HttpResponse.json(ticket({ title: currentTitle })),
      ),
      http.get(`${BASE}/tickets/${TICKET_ID}/history`, () => HttpResponse.json(history())),
      http.patch(`${BASE}/tickets/${TICKET_ID}`, async ({ request }) => {
        const body = (await request.json()) as { title: string; description: string };
        currentTitle = body.title;
        return HttpResponse.json(ticket({ title: body.title, description: body.description }));
      }),
    );

    renderWithProviders(
      <Routes>
        <Route path="/tickets/:id" element={<TicketDetailPage />} />
      </Routes>,
      { route: `/tickets/${TICKET_ID}` },
    );

    await userEvent.click(await screen.findByRole('button', { name: /edit ticket/i }));
    const titleInput = screen.getByLabelText(/title/i);
    await userEvent.clear(titleInput);
    await userEvent.type(titleInput, 'Login broken');
    await userEvent.click(screen.getByRole('button', { name: /save changes/i }));

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'Login broken' })).toBeInTheDocument(),
    );
  });

  it('renders staff actions for agents', async () => {
    authedRefresh({ id: AGENT_ID, displayName: 'Bob', role: 'AGENT' });
    server.use(
      http.get(`${BASE}/tickets/${TICKET_ID}`, () => HttpResponse.json(ticket())),
      http.get(`${BASE}/tickets/${TICKET_ID}/history`, () => HttpResponse.json(history())),
    );

    renderWithProviders(
      <Routes>
        <Route path="/tickets/:id" element={<TicketDetailPage />} />
      </Routes>,
      { route: `/tickets/${TICKET_ID}` },
    );

    expect(await screen.findByText(/Staff actions/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /take ticket/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^edit ticket$/i })).not.toBeInTheDocument();
  });

  it('lets an agent take an unassigned ticket', async () => {
    authedRefresh({ id: AGENT_ID, displayName: 'Bob', role: 'AGENT' });
    let currentAssignee: { id: string; displayName: string; email: string } | null = null;
    server.use(
      http.get(`${BASE}/tickets/${TICKET_ID}`, () =>
        HttpResponse.json(ticket({ assignee: currentAssignee })),
      ),
      http.get(`${BASE}/tickets/${TICKET_ID}/history`, () => HttpResponse.json(history())),
      http.post(`${BASE}/tickets/${TICKET_ID}/assign`, async ({ request }) => {
        const body = (await request.json()) as { assigneeId: string | null };
        if (body.assigneeId) {
          currentAssignee = { id: AGENT_ID, displayName: 'Bob', email: 'bob@example.com' };
        } else {
          currentAssignee = null;
        }
        return HttpResponse.json(ticket({ assignee: currentAssignee }));
      }),
    );

    renderWithProviders(
      <Routes>
        <Route path="/tickets/:id" element={<TicketDetailPage />} />
      </Routes>,
      { route: `/tickets/${TICKET_ID}` },
    );

    await userEvent.click(await screen.findByRole('button', { name: /take ticket/i }));
    await waitFor(() => expect(screen.getByText(/Assigned to/)).toBeInTheDocument());
    expect(screen.getAllByText('Bob').length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /take ticket/i })).not.toBeInTheDocument();
  });

  it('lets an agent unassign an assigned ticket', async () => {
    authedRefresh({ id: AGENT_ID, displayName: 'Bob', role: 'AGENT' });
    let currentAssignee: { id: string; displayName: string; email: string } | null = {
      id: AGENT_ID,
      displayName: 'Bob',
      email: 'bob@example.com',
    };
    server.use(
      http.get(`${BASE}/tickets/${TICKET_ID}`, () =>
        HttpResponse.json(ticket({ assignee: currentAssignee })),
      ),
      http.get(`${BASE}/tickets/${TICKET_ID}/history`, () => HttpResponse.json(history())),
      http.post(`${BASE}/tickets/${TICKET_ID}/assign`, async ({ request }) => {
        const body = (await request.json()) as { assigneeId: string | null };
        currentAssignee = body.assigneeId
          ? { id: AGENT_ID, displayName: 'Bob', email: 'bob@example.com' }
          : null;
        return HttpResponse.json(ticket({ assignee: currentAssignee }));
      }),
    );

    renderWithProviders(
      <Routes>
        <Route path="/tickets/:id" element={<TicketDetailPage />} />
      </Routes>,
      { route: `/tickets/${TICKET_ID}` },
    );

    await userEvent.click(await screen.findByRole('button', { name: /unassign/i }));
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /^unassign$/i })).not.toBeInTheDocument(),
    );
    expect(screen.getByRole('button', { name: /take ticket/i })).toBeInTheDocument();
  });

  it('lets a submitter close their own open ticket', async () => {
    authedRefresh({ id: SUBMITTER_ID, displayName: 'Alice', role: 'USER' });
    let closed = false;
    server.use(
      http.get(`${BASE}/tickets/${TICKET_ID}`, () =>
        HttpResponse.json(ticket({ status: closed ? 'CLOSED' : 'OPEN' })),
      ),
      http.get(`${BASE}/tickets/${TICKET_ID}/history`, () => HttpResponse.json(history())),
      http.post(`${BASE}/tickets/${TICKET_ID}/status`, async ({ request }) => {
        const body = (await request.json()) as { status: string };
        closed = body.status === 'CLOSED';
        return HttpResponse.json(ticket({ status: 'CLOSED' }));
      }),
    );

    renderWithProviders(
      <Routes>
        <Route path="/tickets/:id" element={<TicketDetailPage />} />
        <Route path="/tickets" element={<div>tickets list</div>} />
      </Routes>,
      { route: `/tickets/${TICKET_ID}` },
    );

    await userEvent.click(await screen.findByRole('button', { name: /close ticket/i }));
    expect(await screen.findByText('tickets list')).toBeInTheDocument();
  });

  it('renders an error state and back link when the ticket fetch fails', async () => {
    authedRefresh({ id: SUBMITTER_ID, displayName: 'Alice', role: 'USER' });
    server.use(
      http.get(`${BASE}/tickets/${TICKET_ID}`, () =>
        HttpResponse.json({ status: 404, code: 'not_found' }, { status: 404 }),
      ),
      http.get(`${BASE}/tickets/${TICKET_ID}/history`, () => HttpResponse.json(history())),
    );

    renderWithProviders(
      <Routes>
        <Route path="/tickets/:id" element={<TicketDetailPage />} />
      </Routes>,
      { route: `/tickets/${TICKET_ID}` },
    );

    expect(await screen.findByRole('alert')).toHaveTextContent(/could not find/i);
    expect(screen.getByRole('link', { name: /back to tickets/i })).toBeInTheDocument();
  });
});
