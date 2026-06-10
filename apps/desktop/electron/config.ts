/**
 * API base URL for main-process calls (the secureStore refresh path).
 * Overridable via TMAP_API_BASE_URL env at launch so a packaged build can be
 * pointed at another server without rebuilding.
 *
 * NOTE: The plan's stale draft defaulted to port 5050, but Q4 shipped the
 * renderer (apps/desktop/src/main.tsx) defaulting to `http://localhost:3000`
 * (VITE_API_BASE_URL ?? 'http://localhost:3000'). We mirror that here so the
 * main-process refresh call hits the same origin the renderer talks to.
 */
export const API_BASE_URL =
  process.env.TMAP_API_BASE_URL?.replace(/\/$/, '') ?? 'http://localhost:3000';
