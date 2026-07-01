import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return (
    <div className="max-w-md text-center mx-auto py-16">
      <p className="text-sm font-medium text-indigo-600">404</p>
      <h1 className="mt-2 text-2xl font-semibold text-slate-900">Page not found</h1>
      <p className="mt-2 text-slate-600">The page you’re looking for doesn’t exist.</p>
      <Link
        to="/"
        className="mt-6 inline-block rounded-md bg-indigo-600 text-white px-4 py-2 font-medium hover:bg-indigo-500"
      >
        Back home
      </Link>
    </div>
  );
}
