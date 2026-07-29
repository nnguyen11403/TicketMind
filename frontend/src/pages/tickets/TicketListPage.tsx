import { useMemo } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { listTickets, type TicketStatus } from '@/api/tickets';
import { StatusBadge } from '@/components/StatusBadge';
import { PriorityBadge } from '@/components/PriorityBadge';
import { formatRelative } from '@/lib/formatDate';
import { formatError } from '@/lib/errorMessages';
import { isStaff } from '@/lib/roles';
import { useAuth } from '@/auth/useAuth';

const PAGE_SIZE = 20;
const STATUSES: TicketStatus[] = ['OPEN', 'IN_PROGRESS', 'WAITING', 'RESOLVED', 'CLOSED'];

export function TicketListPage() {
  const { user } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();

  const status = (searchParams.get('status') as TicketStatus | null) ?? undefined;
  const page = Math.max(0, Number(searchParams.get('page') ?? '0'));

  const query = useQuery({
    queryKey: ['tickets', { status: status ?? null, page }],
    queryFn: () => listTickets({ status, page, size: PAGE_SIZE }),
  });

  const totalPages = query.data?.totalPages ?? 0;
  const hasPrev = page > 0;
  const hasNext = page + 1 < totalPages;

  const setStatus = (next: TicketStatus | undefined) => {
    const params = new URLSearchParams(searchParams);
    if (next) params.set('status', next);
    else params.delete('status');
    params.delete('page');
    setSearchParams(params);
  };

  const setPage = (next: number) => {
    const params = new URLSearchParams(searchParams);
    params.set('page', String(next));
    setSearchParams(params);
  };

  const emptyLabel = useMemo(() => {
    if (isStaff(user?.role)) {
      return status ? `No ${status.toLowerCase()} tickets in the queue.` : 'No tickets yet.';
    }
    return "You haven't opened any tickets yet.";
  }, [status, user?.role]);

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">Tickets</h1>
          <p className="text-sm text-slate-500">
            {isStaff(user?.role)
              ? 'Everyone’s tickets across the workspace.'
              : 'Tickets you have opened.'}
          </p>
        </div>
        <Link
          to="/tickets/new"
          className="rounded-md bg-indigo-600 text-white px-4 py-2 text-sm font-medium hover:bg-indigo-500"
        >
          New ticket
        </Link>
      </div>

      <div className="flex flex-wrap gap-2 mb-4" role="group" aria-label="Filter by status">
        <button
          type="button"
          onClick={() => setStatus(undefined)}
          className={`text-sm rounded-full px-3 py-1 border ${
            !status
              ? 'bg-slate-900 text-white border-slate-900'
              : 'border-slate-300 text-slate-600 hover:bg-slate-100'
          }`}
        >
          All
        </button>
        {STATUSES.map((s) => (
          <button
            key={s}
            type="button"
            onClick={() => setStatus(s)}
            className={`text-sm rounded-full px-3 py-1 border ${
              status === s
                ? 'bg-slate-900 text-white border-slate-900'
                : 'border-slate-300 text-slate-600 hover:bg-slate-100'
            }`}
          >
            {s.replace('_', ' ').toLowerCase()}
          </button>
        ))}
      </div>

      {query.isPending ? (
        <p className="text-slate-500">Loading tickets…</p>
      ) : query.isError ? (
        <p role="alert" className="text-red-600">
          {formatError(query.error)}
        </p>
      ) : query.data.content.length === 0 ? (
        <div className="rounded-md border border-dashed border-slate-300 p-8 text-center text-slate-500">
          {emptyLabel}
        </div>
      ) : (
        <div className="rounded-md border border-slate-200 bg-white overflow-hidden">
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50 text-left text-xs uppercase text-slate-500">
              <tr>
                <th className="px-4 py-2 font-medium">Title</th>
                <th className="px-4 py-2 font-medium">Status</th>
                <th className="px-4 py-2 font-medium">Priority</th>
                <th className="px-4 py-2 font-medium">Submitter</th>
                <th className="px-4 py-2 font-medium">Assignee</th>
                <th className="px-4 py-2 font-medium">Updated</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {query.data.content.map((ticket) => (
                <tr key={ticket.id} className="hover:bg-slate-50">
                  <td className="px-4 py-3">
                    <Link
                      to={`/tickets/${ticket.id}`}
                      className="text-indigo-600 hover:underline font-medium"
                    >
                      {ticket.title}
                    </Link>
                  </td>
                  <td className="px-4 py-3">
                    <StatusBadge status={ticket.status} />
                  </td>
                  <td className="px-4 py-3">
                    <PriorityBadge priority={ticket.priority} />
                  </td>
                  <td className="px-4 py-3 text-slate-600">
                    {ticket.submitter?.displayName ?? '—'}
                  </td>
                  <td className="px-4 py-3 text-slate-600">
                    {ticket.assignee?.displayName ?? (
                      <span className="text-slate-400 italic">Unassigned</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-slate-500" title={ticket.updatedAt}>
                    {formatRelative(ticket.updatedAt)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {query.data && query.data.totalElements > 0 ? (
        <div className="mt-4 flex items-center justify-between text-sm text-slate-500">
          <p>
            Page {page + 1} of {Math.max(1, totalPages)} · {query.data.totalElements} total
          </p>
          <div className="flex gap-2">
            <button
              type="button"
              disabled={!hasPrev}
              onClick={() => setPage(page - 1)}
              className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-40 hover:bg-slate-100 disabled:hover:bg-transparent"
            >
              Previous
            </button>
            <button
              type="button"
              disabled={!hasNext}
              onClick={() => setPage(page + 1)}
              className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-40 hover:bg-slate-100 disabled:hover:bg-transparent"
            >
              Next
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
