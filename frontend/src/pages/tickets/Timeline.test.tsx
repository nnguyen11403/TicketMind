import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Timeline } from './Timeline';
import type { HistoryEntry, TicketHistoryEvent } from '@/api/tickets';

let idSeq = 0;
function entry(
  eventType: TicketHistoryEvent,
  payload: Record<string, unknown> = {},
  actor: HistoryEntry['actor'] = {
    id: '11111111-1111-1111-1111-111111111111',
    displayName: 'Alice',
    email: 'alice@example.com',
  },
): HistoryEntry {
  idSeq += 1;
  return {
    id: `${eventType}-${idSeq}`,
    eventType,
    actor,
    payload: JSON.stringify(payload),
    createdAt: new Date().toISOString(),
  };
}

describe('Timeline', () => {
  it('shows an empty-state message when there are no entries', () => {
    render(<Timeline entries={[]} />);
    expect(screen.getByText(/no activity yet/i)).toBeInTheDocument();
  });

  it('renders CREATED, UPDATED, and TRIAGED events with human labels', () => {
    render(<Timeline entries={[entry('CREATED'), entry('UPDATED'), entry('TRIAGED')]} />);
    expect(screen.getByText(/opened this ticket/i)).toBeInTheDocument();
    expect(screen.getByText(/edited the ticket/i)).toBeInTheDocument();
    expect(screen.getByText(/triaged the ticket/i)).toBeInTheDocument();
  });

  it('expands STATUS_CHANGED into a from → to sentence', () => {
    render(<Timeline entries={[entry('STATUS_CHANGED', { from: 'OPEN', to: 'IN_PROGRESS' })]} />);
    expect(screen.getByText(/changed status from/i)).toBeInTheDocument();
    expect(screen.getByText('open')).toBeInTheDocument();
    expect(screen.getByText('in_progress')).toBeInTheDocument();
  });

  it('falls back to a generic status label when from/to are missing', () => {
    render(<Timeline entries={[entry('STATUS_CHANGED', {})]} />);
    expect(screen.getByText(/changed the status/i)).toBeInTheDocument();
  });

  it('renders RESOLVED and REOPENED using the same status template', () => {
    render(
      <Timeline
        entries={[
          entry('RESOLVED', { from: 'IN_PROGRESS', to: 'RESOLVED' }),
          entry('REOPENED', { from: 'CLOSED', to: 'OPEN' }),
        ]}
      />,
    );
    expect(screen.getByText('resolved')).toBeInTheDocument();
    expect(screen.getByText('closed')).toBeInTheDocument();
  });

  it('distinguishes ASSIGNED and unassigned via the payload', () => {
    render(
      <Timeline
        entries={[
          entry('ASSIGNED', { assigneeId: '99999999-9999-9999-9999-999999999999' }),
          entry('ASSIGNED', {}),
        ]}
      />,
    );
    expect(screen.getByText('assigned the ticket')).toBeInTheDocument();
    expect(screen.getByText('unassigned the ticket')).toBeInTheDocument();
  });

  it('shows the comment body from the payload for COMMENT events', () => {
    render(<Timeline entries={[entry('COMMENT', { body: 'Looking at logs now.' })]} />);
    expect(screen.getByText('Looking at logs now.')).toBeInTheDocument();
  });

  it('renders "System" when actor is null and handles unparseable payloads', () => {
    const bad: HistoryEntry = {
      id: 'bad',
      eventType: 'CREATED',
      actor: null,
      payload: 'not json at all',
      createdAt: new Date().toISOString(),
    };
    render(<Timeline entries={[bad]} />);
    expect(screen.getByText('System')).toBeInTheDocument();
    expect(screen.getByText(/opened this ticket/i)).toBeInTheDocument();
  });
});
