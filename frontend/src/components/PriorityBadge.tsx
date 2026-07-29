import type { TicketPriority } from '@/api/tickets';

const styles: Record<TicketPriority, string> = {
  LOW: 'text-slate-500',
  MEDIUM: 'text-slate-700',
  HIGH: 'text-orange-600',
  CRITICAL: 'text-red-600',
};

const labels: Record<TicketPriority, string> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
  CRITICAL: 'Critical',
};

export function PriorityBadge({ priority }: { priority: TicketPriority | null | undefined }) {
  if (!priority) return <span className="text-slate-400 text-xs">—</span>;
  return <span className={`text-xs font-medium ${styles[priority]}`}>{labels[priority]}</span>;
}
