const raw = import.meta.env.VITE_API_BASE_URL;

if (!raw || typeof raw !== 'string') {
  throw new Error('VITE_API_BASE_URL is not set, copy .env.example to .env');
}

export const env = {
  apiBaseUrl: raw.replace(/\/+$/, ''),
} as const;
