import type { HistoryEntry, TicketHistoryEvent } from '@/api/tickets';
import { formatAbsolute, formatRelative } from '@/lib/formatDate';

function parsePayload(raw: string): Record<string, unknown> {
  try {
    const value = JSON.parse(raw) as unknown;
    return value && typeof value === 'object' ? (value as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

function renderEvent(event: TicketHistoryEvent, payload: Record<string, unknown>): React.ReactNode {
  const from = typeof payload.from === 'string' ? payload.from : null;
  const to = typeof payload.to === 'string' ? payload.to : null;
  switch (event) {
    case 'CREATED':
      return <span>opened this ticket</span>;
    case 'UPDATED':
      return <span>edited the ticket</span>;
    case 'STATUS_CHANGED':
    case 'RESOLVED':
    case 'REOPENED':
      if (from && to) {
        return (
          <span>
            changed status from <strong>{from.toLowerCase()}</strong> to{' '}
            <strong>{to.toLowerCase()}</strong>
          </span>
        );
      }
      return <span>changed the status</span>;
    case 'ASSIGNED':
      return typeof payload.assigneeId === 'string' ? (
        <span>assigned the ticket</span>
      ) : (
        <span>unassigned the ticket</span>
      );
    case 'TRIAGED':
      return <span>triaged the ticket</span>;
    case 'COMMENT': {
      const body = typeof payload.body === 'string' ? payload.body : '';
      return (
        <div className="mt-1 rounded-md bg-slate-50 border border-slate-200 px-3 py-2 whitespace-pre-wrap text-slate-800">
          {body}
        </div>
      );
    }
  }
}

export function Timeline({ entries }: { entries: HistoryEntry[] }) {
  if (entries.length === 0) {
    return <p className="text-slate-500 text-sm">No activity yet.</p>;
  }
  return (
    <ol className="space-y-4">
      {entries.map((entry) => {
        const payload = parsePayload(entry.payload);
        const actor = entry.actor?.displayName ?? 'System';
        return (
          <li key={entry.id} className="flex gap-3">
            <div aria-hidden className="mt-1 size-2 rounded-full bg-indigo-500 shrink-0" />
            <div className="flex-1 text-sm">
              <div className="text-slate-700">
                <span className="font-medium text-slate-900">{actor}</span>{' '}
                {renderEvent(entry.eventType, payload)}
              </div>
              <div
                className="text-xs text-slate-500 mt-0.5"
                title={formatAbsolute(entry.createdAt)}
              >
                {formatRelative(entry.createdAt)}
              </div>
            </div>
          </li>
        );
      })}
    </ol>
  );
}
