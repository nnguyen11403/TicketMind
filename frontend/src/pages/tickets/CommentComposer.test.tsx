import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse, http } from 'msw';
import { server } from '@/test/server';
import { renderWithProviders } from '@/test/renderApp';
import { CommentComposer } from './CommentComposer';

const BASE = 'http://api.test';
const TICKET_ID = '33333333-3333-3333-3333-333333333333';

function anonymousRefresh() {
  server.use(
    http.post(`${BASE}/auth/refresh`, () =>
      HttpResponse.json({ status: 401, code: 'invalid_refresh_token' }, { status: 401 }),
    ),
  );
}

describe('CommentComposer', () => {
  it('rejects empty comments client-side without hitting the network', async () => {
    anonymousRefresh();
    renderWithProviders(<CommentComposer ticketId={TICKET_ID} />);
    await userEvent.click(await screen.findByRole('button', { name: /post comment/i }));
    expect(screen.getByRole('alert')).toHaveTextContent(/comment cannot be empty/i);
  });

  it('posts a trimmed body and clears the textarea on success', async () => {
    anonymousRefresh();
    let received: string | null = null;
    server.use(
      http.post(`${BASE}/tickets/${TICKET_ID}/comments`, async ({ request }) => {
        const body = (await request.json()) as { body: string };
        received = body.body;
        return HttpResponse.json({
          id: '44444444-4444-4444-4444-444444444444',
          eventType: 'COMMENT',
          actor: null,
          payload: JSON.stringify({ body: body.body }),
          createdAt: new Date().toISOString(),
        });
      }),
    );

    renderWithProviders(<CommentComposer ticketId={TICKET_ID} />);
    const textarea = await screen.findByLabelText(/add a comment/i);
    await userEvent.type(textarea, '   Looking at logs   ');
    await userEvent.click(screen.getByRole('button', { name: /post comment/i }));

    await waitFor(() => expect(received).toBe('Looking at logs'));
    expect(textarea).toHaveValue('');
  });

  it('surfaces server errors from the API envelope', async () => {
    anonymousRefresh();
    server.use(
      http.post(`${BASE}/tickets/${TICKET_ID}/comments`, () =>
        HttpResponse.json({ status: 409, code: 'invalid_ticket_state' }, { status: 409 }),
      ),
    );

    renderWithProviders(<CommentComposer ticketId={TICKET_ID} />);
    await userEvent.type(await screen.findByLabelText(/add a comment/i), 'Something');
    await userEvent.click(screen.getByRole('button', { name: /post comment/i }));

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/not allowed/i));
  });
});
