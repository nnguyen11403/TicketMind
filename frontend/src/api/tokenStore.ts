// Access tokens live in module memory only. They are never persisted to
// localStorage/sessionStorage — that would give XSS a persistent credential
// harvest. The refresh token is an HttpOnly cookie scoped to /auth on the
// backend, so it is unreachable from JS by design.
let accessToken: string | null = null;

type Listener = (token: string | null) => void;
const listeners = new Set<Listener>();

export const tokenStore = {
  get(): string | null {
    return accessToken;
  },
  set(next: string | null): void {
    accessToken = next;
    for (const listener of listeners) {
      listener(next);
    }
  },
  clear(): void {
    tokenStore.set(null);
  },
  subscribe(listener: Listener): () => void {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },
};
