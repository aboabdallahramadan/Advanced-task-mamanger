/**
 * API base URL for main-process calls (the secureStore refresh path).
 * Overridable via TMAP_API_BASE_URL env at launch so a packaged build can be
 * pointed at another server without rebuilding.
 *
 * Default is the local .NET API dev URL (`http://localhost:5188`, the http
 * profile in backend/src/Tmap.Api/Properties/launchSettings.json). The renderer
 * (apps/desktop/src/config.ts) defaults to the same origin via VITE_API_BASE_URL,
 * so the main-process refresh call hits the same server the renderer talks to.
 * Keep TMAP_API_BASE_URL and VITE_API_BASE_URL in sync in production.
 */
export const API_BASE_URL =
  process.env.TMAP_API_BASE_URL?.replace(/\/$/, '') ?? 'http://localhost:5188';
