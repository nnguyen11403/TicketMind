import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  assignTicket,
  changeTicketStatus,
  type TicketDetail,
  type TicketStatus,
} from '@/api/tickets';
import { useAuth } from '@/auth/useAuth';
import { formatError } from '@/lib/errorMessages';
import { useState } from 'react';

const STAFF_STATUSES: TicketStatus[] = ['OPEN', 'IN_PROGRESS', 'WAITING', 'RESOLVED', 'CLOSED'];

export function StaffActions({ ticket }: { ticket: TicketDetail }) {
  const { user } = useAuth();
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const statusMutation = useMutation({
    mutationFn: (next: TicketStatus) => changeTicketStatus(ticket.id, next),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['ticket', ticket.id] });
      void qc.invalidateQueries({ queryKey: ['tickets'] });
    },
    onError: (err) => setError(formatError(err)),
  });

  const assignMutation = useMutation({
    mutationFn: (assigneeId: string | null) => assignTicket(ticket.id, assigneeId),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['ticket', ticket.id] });
      void qc.invalidateQueries({ queryKey: ['tickets'] });
    },
    onError: (err) => setError(formatError(err)),
  });

  const isAssignedToMe = user?.id === ticket.assignee?.id;
  const canClose = ticket.status !== 'CLOSED';

  return (
    <div className="rounded-md border border-slate-200 bg-white p-4 flex flex-col gap-3">
      <h2 className="text-sm font-semibold text-slate-900">Staff actions</h2>

      <div>
        <label htmlFor="status-select" className="text-xs font-medium text-slate-600">
          Status
        </label>
        <select
          id="status-select"
          value={ticket.status}
          disabled={statusMutation.isPending || !canClose}
          onChange={(event) => {
            setError(null);
            statusMutation.mutate(event.target.value as TicketStatus);
          }}
          className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 shadow-sm outline-none focus:ring-2 focus:ring-indigo-500 disabled:bg-slate-100"
        >
          {STAFF_STATUSES.map((s) => (
            <option key={s} value={s}>
              {s.replace('_', ' ').toLowerCase()}
            </option>
          ))}
        </select>
        {ticket.status === 'CLOSED' ? (
          <p className="mt-1 text-xs text-slate-500">Closed tickets cannot be reopened.</p>
        ) : null}
      </div>

      <div>
        <p className="text-xs font-medium text-slate-600">Assignment</p>
        <p className="mt-1 text-sm text-slate-700">
          {ticket.assignee ? (
            <>
              Assigned to <span className="font-medium">{ticket.assignee.displayName}</span>
            </>
          ) : (
            <span className="text-slate-500 italic">Unassigned</span>
          )}
        </p>
        <div className="mt-2 flex gap-2 flex-wrap">
          {!isAssignedToMe && user ? (
            <button
              type="button"
              onClick={() => {
                setError(null);
                assignMutation.mutate(user.id);
              }}
              disabled={assignMutation.isPending}
              className="rounded-md border border-indigo-600 text-indigo-600 px-3 py-1.5 text-sm hover:bg-indigo-50 disabled:opacity-60"
            >
              Take ticket
            </button>
          ) : null}
          {ticket.assignee ? (
            <button
              type="button"
              onClick={() => {
                setError(null);
                assignMutation.mutate(null);
              }}
              disabled={assignMutation.isPending}
              className="rounded-md border border-slate-300 text-slate-700 px-3 py-1.5 text-sm hover:bg-slate-100 disabled:opacity-60"
            >
              Unassign
            </button>
          ) : null}
        </div>
      </div>

      {error ? (
        <p role="alert" className="text-sm text-red-600">
          {error}
        </p>
      ) : null}
    </div>
  );
}
