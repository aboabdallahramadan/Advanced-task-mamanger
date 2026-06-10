import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'path';

// jsdom is not installed in this monorepo and isn't needed here: the WebPlatform
// test exercises fetch/Response globals (present in Node) and never touches the DOM.
// Matches @tmap/app's node-environment vitest setup; the `@` alias points at the
// shared app package so `@/auth/types` and `@/platform/Platform` resolve.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, '../../packages/app/src'),
    },
  },
  test: {
    environment: 'node',
    globals: true,
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
  },
});
