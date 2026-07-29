import { z } from 'zod';
import { apiFetch } from './client';

export const ticketStatusSchema = z.enum(['OPEN', 'IN_PROGRESS', 'WAITING', 'RESOLVED', 'CLOSED']);
export type TicketStatus = z.infer<typeof ticketStatusSchema>;

export const ticketPrioritySchema = z.enum(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']);
export type TicketPriority = z.infer<typeof ticketPrioritySchema>;

export const ticketHistoryEventSchema = z.enum([
  'CREATED',
  'UPDATED',
  'STATUS_CHANGED',
  'ASSIGNED',
  'TRIAGED',
  'COMMENT',
  'RESOLVED',
  'REOPENED',
]);
export type TicketHistoryEvent = z.infer<typeof ticketHistoryEventSchema>;

const userSummarySchema = z
  .object({
    id: z.string().uuid(),
    displayName: z.string(),
    email: z.string(),
  })
  .nullable();
export type UserSummary = z.infer<typeof userSummarySchema>;

export const ticketSummarySchema = z.object({
  id: z.string().uuid(),
  title: z.string(),
  status: ticketStatusSchema,
  priority: ticketPrioritySchema.nullable().optional(),
  category: z.string().nullable().optional(),
  submitter: userSummarySchema,
  assignee: userSummarySchema,
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type TicketSummary = z.infer<typeof ticketSummarySchema>;

export const ticketDetailSchema = ticketSummarySchema.extend({
  description: z.string(),
  suggestedResolution: z.string().nullable().optional(),
  triagedAt: z.string().nullable().optional(),
  resolvedAt: z.string().nullable().optional(),
});
export type TicketDetail = z.infer<typeof ticketDetailSchema>;

export const pagedTicketsSchema = z.object({
  content: z.array(ticketSummarySchema),
  page: z.number().int(),
  size: z.number().int(),
  totalElements: z.number().int(),
  totalPages: z.number().int(),
});
export type PagedTickets = z.infer<typeof pagedTicketsSchema>;

export const historyEntrySchema = z.object({
  id: z.string().uuid(),
  eventType: ticketHistoryEventSchema,
  actor: userSummarySchema,
  payload: z.string(),
  createdAt: z.string(),
});
export type HistoryEntry = z.infer<typeof historyEntrySchema>;

export interface ListParams {
  status?: TicketStatus | undefined;
  page?: number;
  size?: number;
}

export async function listTickets(params: ListParams = {}): Promise<PagedTickets> {
  const search = new URLSearchParams();
  if (params.status) search.set('status', params.status);
  if (params.page !== undefined) search.set('page', String(params.page));
  if (params.size !== undefined) search.set('size', String(params.size));
  const query = search.toString();
  const payload = await apiFetch<unknown>(`/tickets${query ? `?${query}` : ''}`);
  return pagedTicketsSchema.parse(payload);
}

export async function getTicket(id: string): Promise<TicketDetail> {
  const payload = await apiFetch<unknown>(`/tickets/${id}`);
  return ticketDetailSchema.parse(payload);
}

export async function createTicket(input: {
  title: string;
  description: string;
}): Promise<TicketDetail> {
  const payload = await apiFetch<unknown>('/tickets', {
    method: 'POST',
    body: input,
  });
  return ticketDetailSchema.parse(payload);
}

export async function updateTicket(
  id: string,
  input: { title: string; description: string },
): Promise<TicketDetail> {
  const payload = await apiFetch<unknown>(`/tickets/${id}`, {
    method: 'PATCH',
    body: input,
  });
  return ticketDetailSchema.parse(payload);
}

export async function changeTicketStatus(id: string, status: TicketStatus): Promise<TicketDetail> {
  const payload = await apiFetch<unknown>(`/tickets/${id}/status`, {
    method: 'POST',
    body: { status },
  });
  return ticketDetailSchema.parse(payload);
}

export async function assignTicket(id: string, assigneeId: string | null): Promise<TicketDetail> {
  const payload = await apiFetch<unknown>(`/tickets/${id}/assign`, {
    method: 'POST',
    body: { assigneeId },
  });
  return ticketDetailSchema.parse(payload);
}

export async function addComment(id: string, body: string): Promise<HistoryEntry> {
  const payload = await apiFetch<unknown>(`/tickets/${id}/comments`, {
    method: 'POST',
    body: { body },
  });
  return historyEntrySchema.parse(payload);
}

export async function getHistory(id: string): Promise<HistoryEntry[]> {
  const payload = await apiFetch<unknown>(`/tickets/${id}/history`);
  return z.array(historyEntrySchema).parse(payload);
}
