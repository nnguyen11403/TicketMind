import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { addComment } from '@/api/tickets';
import { formatError } from '@/lib/errorMessages';

export function CommentComposer({ ticketId }: { ticketId: string }) {
  const qc = useQueryClient();
  const [body, setBody] = useState('');
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: (value: string) => addComment(ticketId, value),
    onSuccess: () => {
      setBody('');
      void qc.invalidateQueries({ queryKey: ['ticket', ticketId, 'history'] });
    },
    onError: (err) => setError(formatError(err)),
  });

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    const trimmed = body.trim();
    if (!trimmed) {
      setError('Comment cannot be empty.');
      return;
    }
    mutation.mutate(trimmed);
  };

  return (
    <form onSubmit={submit} className="mt-6">
      <label htmlFor="comment" className="text-sm font-medium text-slate-700">
        Add a comment
      </label>
      <textarea
        id="comment"
        rows={3}
        value={body}
        onChange={(event) => setBody(event.target.value)}
        placeholder="Share an update, question, or resolution notes…"
        className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-slate-900 shadow-sm outline-none focus:ring-2 focus:ring-indigo-500"
      />
      {error ? (
        <p role="alert" className="mt-1 text-sm text-red-600">
          {error}
        </p>
      ) : null}
      <div className="mt-2 flex justify-end">
        <button
          type="submit"
          disabled={mutation.isPending}
          className="rounded-md bg-indigo-600 text-white px-4 py-2 text-sm font-medium hover:bg-indigo-500 disabled:opacity-60"
        >
          {mutation.isPending ? 'Posting…' : 'Post comment'}
        </button>
      </div>
    </form>
  );
}
