import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import {
  login as loginApi,
  logout as logoutApi,
  register as registerApi,
  silentRefresh,
  type AuthenticatedUser,
  type LoginRequest,
  type RegisterRequest,
} from '@/api/auth';
import { tokenStore } from '@/api/tokenStore';
import { AuthContext, type AuthContextValue, type AuthStatus } from './authContextValue';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthenticatedUser | null>(null);
  const [status, setStatus] = useState<AuthStatus>('loading');
  const bootstrappedRef = useRef(false);

  useEffect(() => {
    if (bootstrappedRef.current) return;
    bootstrappedRef.current = true;
    let cancelled = false;
    void (async () => {
      const result = await silentRefresh();
      if (cancelled) return;
      if (result) {
        setUser(result.user);
        setStatus('authenticated');
      } else {
        setUser(null);
        setStatus('anonymous');
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    return tokenStore.subscribe((token) => {
      if (token === null) {
        setUser((current) => (current ? null : current));
        setStatus((current) => (current === 'authenticated' ? 'anonymous' : current));
      }
    });
  }, []);

  const login = useCallback(async (request: LoginRequest) => {
    const response = await loginApi(request);
    setUser(response.user);
    setStatus('authenticated');
    return response;
  }, []);

  const register = useCallback(async (request: RegisterRequest) => {
    const response = await registerApi(request);
    setUser(response.user);
    setStatus('authenticated');
    return response;
  }, []);

  const logout = useCallback(async () => {
    await logoutApi();
    setUser(null);
    setStatus('anonymous');
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ status, user, login, register, logout }),
    [status, user, login, register, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
