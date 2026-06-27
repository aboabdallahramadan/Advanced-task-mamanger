import { app } from 'electron';

/**
 * API base URL for main-process calls (the secureStore refresh/logout path).
 *
 * The main process is compiled by plain tsc (CommonJS) — Vite's build-time
 * `VITE_API_BASE_URL` replacement does NOT reach it — so it resolves the origin
 * at runtime:
 *   1. TMAP_API_BASE_URL  — OS env at launch; repoint a build without rebuilding.
 *   2. app.isPackaged      — packaged build → production API; dev → local .NET API
 *                            (`http://localhost:5188`, the http profile in
 *                            backend/src/Tmap.Api/Properties/launchSettings.json).
 *
 * Keep the production origin here in sync with the renderer's VITE_API_BASE_URL
 * (apps/desktop/.env.production) so the main-process refresh call hits the same
 * server the renderer talks to.
 */
const PRODUCTION_API_BASE_URL = 'https://api-tasks.qmindtech.net';
const DEV_API_BASE_URL = 'http://localhost:5188';

export const API_BASE_URL = (
  process.env.TMAP_API_BASE_URL ??
  (app.isPackaged ? PRODUCTION_API_BASE_URL : DEV_API_BASE_URL)
).replace(/\/$/, '');
