import { Link, Outlet } from 'react-router-dom';
import { useAuth } from '@/auth/useAuth';

export function AppShell() {
  const { user, logout } = useAuth();

  return (
    <div className="min-h-full flex flex-col">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto max-w-6xl px-6 h-14 flex items-center justify-between">
          <Link to="/" className="font-semibold text-slate-900">
            TicketMind
          </Link>
          <div className="flex items-center gap-4 text-sm">
            {user ? (
              <>
                <span className="text-slate-600" data-testid="current-user">
                  {user.displayName}{' '}
                  <span className="text-slate-400">({user.role.toLowerCase()})</span>
                </span>
                <button
                  type="button"
                  onClick={() => {
                    void logout();
                  }}
                  className="rounded-md border border-slate-300 px-3 py-1.5 text-slate-700 hover:bg-slate-100"
                >
                  Log out
                </button>
              </>
            ) : null}
          </div>
        </div>
      </header>
      <main className="flex-1">
        <div className="mx-auto max-w-6xl px-6 py-8">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
