import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { server } from '@/test/server';
import { renderWithProviders } from '@/test/renderApp';
import { NotFoundPage } from './NotFoundPage';

describe('NotFoundPage', () => {
  it('renders the 404 message and a back-home link', () => {
    server.use(
      http.post('http://api.test/auth/refresh', () =>
        HttpResponse.json({ status: 401, code: 'invalid_refresh_token' }, { status: 401 }),
      ),
    );
    renderWithProviders(<NotFoundPage />);
    expect(screen.getByText('404')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /page not found/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /back home/i })).toHaveAttribute('href', '/');
  });
});
