import { useAuth } from '@/auth/useAuth';

export function HomePage() {
  const { user } = useAuth();

  return (
    <div className="max-w-2xl">
      <h1 className="text-2xl font-semibold text-slate-900">
        Welcome{user ? `, ${user.displayName}` : ''}
      </h1>
      <p className="mt-2 text-slate-600">
        Ticket management is coming next. This is the authenticated shell for the ticket UI that
        ships in step 7.
      </p>
    </div>
  );
}
