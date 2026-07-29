import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from '@/components/AppShell';
import { ProtectedRoute } from '@/auth/ProtectedRoute';
import { LoginPage } from '@/pages/LoginPage';
import { RegisterPage } from '@/pages/RegisterPage';
import { NotFoundPage } from '@/pages/NotFoundPage';
import { TicketListPage } from '@/pages/tickets/TicketListPage';
import { TicketCreatePage } from '@/pages/tickets/TicketCreatePage';
import { TicketDetailPage } from '@/pages/tickets/TicketDetailPage';

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route
        element={
          <ProtectedRoute>
            <AppShell />
          </ProtectedRoute>
        }
      >
        <Route path="/" element={<Navigate to="/tickets" replace />} />
        <Route path="/tickets" element={<TicketListPage />} />
        <Route path="/tickets/new" element={<TicketCreatePage />} />
        <Route path="/tickets/:id" element={<TicketDetailPage />} />
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
