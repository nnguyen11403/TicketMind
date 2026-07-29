import type { UserRole } from '@/api/auth';

export function isStaff(role: UserRole | undefined | null): boolean {
  return role === 'AGENT' || role === 'ADMIN';
}
