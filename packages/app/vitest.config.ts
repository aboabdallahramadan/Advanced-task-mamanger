import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    // fake-indexeddb schedules its IndexedDB tasks on setImmediate (lib/scheduling.ts).
    // Excluding setImmediate/queueMicrotask/nextTick from the fake set lets Dexie keep
    // running while a test's vi.useFakeTimers() still controls the engine's own
    // setTimeout/setInterval (debounce + periodic sync). Without this, any Dexie I/O
    // under fake timers deadlocks.
    fakeTimers: {
      toFake: [
        'setTimeout',
        'clearTimeout',
        'setInterval',
        'clearInterval',
        'Date',
      ],
    },
  },
});
