/**
 * Desktop renderer API base URL.
 *
 * Set VITE_API_BASE_URL at build time to point a packaged build at a different
 * server (e.g. `VITE_API_BASE_URL=https://api.yourdomain.com npm run build`).
 *
 * The main process reads TMAP_API_BASE_URL (apps/desktop/electron/config.ts) for the
 * secureStore:refreshAndGetAccess IPC call — keep both in sync in production.
 *
 * In dev (`npm run dev`) the default resolves to http://localhost:5188, the local
 * .NET API dev URL (the http profile in backend/src/Tmap.Api/Properties/launchSettings.json).
 */
export const API_BASE_URL: string =
  ((import.meta as { env?: { VITE_API_BASE_URL?: string } }).env?.VITE_API_BASE_URL as
    | string
    | undefined) ?? 'http://localhost:5188';
