import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

// Shared base config for @tmap/app. The host apps (apps/desktop, apps/web)
// define their own build targets and re-declare the @/ alias into this src tree.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
});
