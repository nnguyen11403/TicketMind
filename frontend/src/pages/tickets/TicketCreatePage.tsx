import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createTicket } from '@/api/tickets';
import { FormField } from '@/components/FormField';
import { formatError } from '@/lib/errorMessages';

const schema = z.object({
  title: z
    .string()
    .trim()
    .min(1, 'Title is required')
    .max(200, 'Title must be 200 characters or fewer'),
  description: z
    .string()
    .trim()
    .min(1, 'Description is required')
    .max(20000, 'Description is too long'),
});

type FormValues = z.infer<typeof schema>;

export function TicketCreatePage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { title: '', description: '' },
  });

  const mutation = useMutation({
    mutationFn: createTicket,
    onSuccess: (ticket) => {
      void qc.invalidateQueries({ queryKey: ['tickets'] });
      navigate(`/tickets/${ticket.id}`, { replace: true });
    },
    onError: (error) => {
      setServerError(formatError(error));
    },
  });

  const onSubmit = handleSubmit((values) => {
    setServerError(null);
    mutation.mutate(values);
  });

  return (
    <div className="max-w-2xl">
      <h1 className="text-2xl font-semibold text-slate-900 mb-6">Open a ticket</h1>
      <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
        <FormField
          label="Title"
          placeholder="Short summary of the issue"
          maxLength={200}
          {...register('title')}
          error={errors.title?.message}
        />
        <div className="flex flex-col gap-1">
          <label htmlFor="description" className="text-sm font-medium text-slate-700">
            Description
          </label>
          <textarea
            id="description"
            rows={8}
            placeholder="Steps to reproduce, expected vs. actual, screenshots pasted below…"
            {...register('description')}
            aria-invalid={errors.description ? true : undefined}
            className={`rounded-md border px-3 py-2 text-slate-900 shadow-sm outline-none focus:ring-2 focus:ring-indigo-500 ${
              errors.description ? 'border-red-400' : 'border-slate-300'
            }`}
          />
          {errors.description ? (
            <p role="alert" className="text-sm text-red-600">
              {errors.description.message}
            </p>
          ) : null}
        </div>
        {serverError ? (
          <p role="alert" className="text-sm text-red-600">
            {serverError}
          </p>
        ) : null}
        <div className="flex gap-3">
          <button
            type="submit"
            disabled={mutation.isPending}
            className="rounded-md bg-indigo-600 text-white px-4 py-2 font-medium hover:bg-indigo-500 disabled:opacity-60"
          >
            {mutation.isPending ? 'Submitting…' : 'Submit ticket'}
          </button>
          <button
            type="button"
            onClick={() => navigate('/tickets')}
            className="rounded-md border border-slate-300 px-4 py-2 font-medium text-slate-700 hover:bg-slate-100"
          >
            Cancel
          </button>
        </div>
      </form>
    </div>
  );
}
