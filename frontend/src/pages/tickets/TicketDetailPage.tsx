import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  changeTicketStatus,
  getHistory,
  getTicket,
  updateTicket,
  type TicketDetail,
} from '@/api/tickets';
import { StatusBadge } from '@/components/StatusBadge';
import { PriorityBadge } from '@/components/PriorityBadge';
import { Timeline } from './Timeline';
import { CommentComposer } from './CommentComposer';
import { StaffActions } from './StaffActions';
import { useAuth } from '@/auth/useAuth';
import { isStaff } from '@/lib/roles';
import { formatAbsolute, formatRelative } from '@/lib/formatDate';
import { formatError } from '@/lib/errorMessages';

// Claude usually answers within a few seconds; give it a wide margin, then
// give up rather than polling a ticket that is never going to be triaged.
const TRIAGE_POLL_INTERVAL_MS = 3_000;
const TRIAGE_POLL_WINDOW_MS = 2 * 60 * 1_000;

export function TicketDetailPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { user } = useAuth();

  const ticketQuery = useQuery({
    queryKey: ['ticket', id],
    queryFn: () => getTicket(id),
    enabled: Boolean(id),
    // Triage runs asynchronously after the backend commits the ticket, so a
    // freshly created ticket arrives untriaged. Poll until it lands, then stop.
    // Bounded by TRIAGE_POLL_WINDOW_MS so a ticket the RAG service never
    // triaged (service down, unparseable reply) doesn't poll forever.
    refetchInterval: (query) => {
      const data = query.state.data;
      if (!data || data.triagedAt) {
        return false;
      }
      const age = Date.now() - new Date(data.createdAt).getTime();
      return age < TRIAGE_POLL_WINDOW_MS ? TRIAGE_POLL_INTERVAL_MS : false;
    },
  });
  const historyQuery = useQuery({
    queryKey: ['ticket', id, 'history'],
    queryFn: () => getHistory(id),
    enabled: Boolean(id),
  });

  if (ticketQuery.isPending) {
    return <p className="text-slate-500">Loading ticket…</p>;
  }
  if (ticketQuery.isError) {
    return (
      <div>
        <p role="alert" className="text-red-600">
          {formatError(ticketQuery.error)}
        </p>
        <Link to="/tickets" className="mt-4 inline-block text-indigo-600 hover:underline">
          ← Back to tickets
        </Link>
      </div>
    );
  }

  const ticket = ticketQuery.data;
  const staff = isStaff(user?.role);
  const isSubmitter = user?.id === ticket.submitter?.id;
  const canEditBody = isSubmitter && ticket.status === 'OPEN';
  const canCloseAsSubmitter = isSubmitter && ticket.status === 'OPEN';
  const triagePending =
    !ticket.triagedAt && Date.now() - new Date(ticket.createdAt).getTime() < TRIAGE_POLL_WINDOW_MS;

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[minmax(0,1fr)_20rem] gap-6">
      <div className="min-w-0">
        <Link to="/tickets" className="text-sm text-indigo-600 hover:underline">
          ← All tickets
        </Link>
        <TicketHeader ticket={ticket} />
        <TicketBody
          ticket={ticket}
          canEdit={canEditBody}
          onSaved={() => {
            void qc.invalidateQueries({ queryKey: ['ticket', id] });
            void qc.invalidateQueries({ queryKey: ['tickets'] });
          }}
        />

        {ticket.suggestedResolution ? (
          <div className="mt-6 rounded-md border border-indigo-200 bg-indigo-50 p-4">
            <h2 className="text-sm font-semibold text-indigo-900">Suggested resolution</h2>
            <p className="mt-1 whitespace-pre-wrap text-sm text-indigo-900/80">
              {ticket.suggestedResolution}
            </p>
          </div>
        ) : triagePending ? (
          <p className="mt-6 text-sm text-slate-500">Analysing this ticket…</p>
        ) : null}

        <section className="mt-8">
          <h2 className="text-sm font-semibold text-slate-900 mb-3">Activity</h2>
          {historyQuery.isPending ? (
            <p className="text-slate-500 text-sm">Loading activity…</p>
          ) : historyQuery.isError ? (
            <p role="alert" className="text-sm text-red-600">
              {formatError(historyQuery.error)}
            </p>
          ) : (
            <Timeline entries={historyQuery.data} />
          )}
          <CommentComposer ticketId={ticket.id} />
        </section>
      </div>

      <aside className="flex flex-col gap-4">
        {staff ? <StaffActions ticket={ticket} /> : null}
        {canCloseAsSubmitter ? (
          <SubmitterClose
            ticket={ticket}
            onClosed={() => {
              void qc.invalidateQueries({ queryKey: ['ticket', id] });
              void qc.invalidateQueries({ queryKey: ['tickets'] });
              navigate('/tickets');
            }}
          />
        ) : null}
        <TicketMeta ticket={ticket} />
      </aside>
    </div>
  );
}

function TicketHeader({ ticket }: { ticket: TicketDetail }) {
  return (
    <div className="mt-2">
      <h1 className="text-2xl font-semibold text-slate-900 break-words">{ticket.title}</h1>
      <div className="mt-2 flex flex-wrap items-center gap-3 text-sm">
        <StatusBadge status={ticket.status} />
        <PriorityBadge priority={ticket.priority} />
        {ticket.category ? <span className="text-slate-500">{ticket.category}</span> : null}
        <span className="text-slate-500" title={formatAbsolute(ticket.createdAt)}>
          Opened {formatRelative(ticket.createdAt)}
        </span>
      </div>
    </div>
  );
}

function TicketBody({
  ticket,
  canEdit,
  onSaved,
}: {
  ticket: TicketDetail;
  canEdit: boolean;
  onSaved: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState(ticket.title);
  const [description, setDescription] = useState(ticket.description);
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: () =>
      updateTicket(ticket.id, { title: title.trim(), description: description.trim() }),
    onSuccess: () => {
      setEditing(false);
      onSaved();
    },
    onError: (err) => setError(formatError(err)),
  });

  if (!editing) {
    return (
      <div className="mt-6">
        <div className="rounded-md border border-slate-200 bg-white p-4 whitespace-pre-wrap text-slate-800">
          {ticket.description}
        </div>
        {canEdit ? (
          <button
            type="button"
            onClick={() => {
              setTitle(ticket.title);
              setDescription(ticket.description);
              setError(null);
              setEditing(true);
            }}
            className="mt-2 text-sm text-indigo-600 hover:underline"
          >
            Edit ticket
          </button>
        ) : null}
      </div>
    );
  }

  return (
    <form
      className="mt-6 rounded-md border border-slate-200 bg-white p-4 flex flex-col gap-3"
      onSubmit={(event) => {
        event.preventDefault();
        setError(null);
        if (!title.trim() || !description.trim()) {
          setError('Title and description are required.');
          return;
        }
        mutation.mutate();
      }}
    >
      <div className="flex flex-col gap-1">
        <label htmlFor="edit-title" className="text-xs font-medium text-slate-600">
          Title
        </label>
        <input
          id="edit-title"
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          maxLength={200}
          className="rounded-md border border-slate-300 px-3 py-2 text-slate-900 shadow-sm outline-none focus:ring-2 focus:ring-indigo-500"
        />
      </div>
      <div className="flex flex-col gap-1">
        <label htmlFor="edit-description" className="text-xs font-medium text-slate-600">
          Description
        </label>
        <textarea
          id="edit-description"
          rows={6}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          className="rounded-md border border-slate-300 px-3 py-2 text-slate-900 shadow-sm outline-none focus:ring-2 focus:ring-indigo-500"
        />
      </div>
      {error ? (
        <p role="alert" className="text-sm text-red-600">
          {error}
        </p>
      ) : null}
      <div className="flex gap-2">
        <button
          type="submit"
          disabled={mutation.isPending}
          className="rounded-md bg-indigo-600 text-white px-4 py-2 text-sm font-medium hover:bg-indigo-500 disabled:opacity-60"
        >
          {mutation.isPending ? 'Saving…' : 'Save changes'}
        </button>
        <button
          type="button"
          onClick={() => setEditing(false)}
          className="rounded-md border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-100"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}

function SubmitterClose({ ticket, onClosed }: { ticket: TicketDetail; onClosed: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const mutation = useMutation({
    mutationFn: () => changeTicketStatus(ticket.id, 'CLOSED'),
    onSuccess: () => onClosed(),
    onError: (err) => setError(formatError(err)),
  });
  return (
    <div className="rounded-md border border-slate-200 bg-white p-4">
      <h2 className="text-sm font-semibold text-slate-900">Close this ticket</h2>
      <p className="mt-1 text-xs text-slate-500">
        If your issue is resolved, you can close the ticket. It cannot be reopened afterwards.
      </p>
      <button
        type="button"
        onClick={() => {
          setError(null);
          mutation.mutate();
        }}
        disabled={mutation.isPending}
        className="mt-3 rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-100 disabled:opacity-60"
      >
        {mutation.isPending ? 'Closing…' : 'Close ticket'}
      </button>
      {error ? (
        <p role="alert" className="mt-2 text-sm text-red-600">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function TicketMeta({ ticket }: { ticket: TicketDetail }) {
  const rows = useMemo(
    () => [
      { label: 'Submitter', value: ticket.submitter?.displayName ?? '-' },
      { label: 'Assignee', value: ticket.assignee?.displayName ?? 'Unassigned' },
      { label: 'Created', value: formatAbsolute(ticket.createdAt) },
      { label: 'Updated', value: formatAbsolute(ticket.updatedAt) },
      ...(ticket.triagedAt ? [{ label: 'Triaged', value: formatAbsolute(ticket.triagedAt) }] : []),
      ...(ticket.resolvedAt
        ? [{ label: 'Resolved', value: formatAbsolute(ticket.resolvedAt) }]
        : []),
    ],
    [ticket],
  );
  return (
    <div className="rounded-md border border-slate-200 bg-white p-4">
      <h2 className="text-sm font-semibold text-slate-900 mb-2">Details</h2>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
        {rows.map((row) => (
          <div key={row.label} className="contents">
            <dt className="text-slate-500">{row.label}</dt>
            <dd className="text-slate-800">{row.value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}
