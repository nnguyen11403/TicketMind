// The React plugin's type comes from the top-level Vite 8, while Vitest 3
// still bundles a Vite 5 API. Both work at runtime but the compile-time
// Plugin types are incompatible — hence the cast.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react() as never],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    css: false,
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    coverage: {
      // `main.tsx` is the bootstrap entry (ReactDOM.createRoot) — asserting
      // it renders adds nothing over the App tests. `env.ts` throws at import
      // time when misconfigured; runtime paths are covered by the tests that
      // exercise the API base URL.
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/main.tsx',
        'src/lib/env.ts',
        'src/test/**',
        '**/*.test.{ts,tsx}',
        '**/*.d.ts',
      ],
      thresholds: {
        statements: 80,
        branches: 80,
        functions: 80,
        lines: 80,
      },
    },
  },
});
