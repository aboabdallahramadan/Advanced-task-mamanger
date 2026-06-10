import React from 'react';
import ReactDOM from 'react-dom/client';
import { AppRoot } from '@tmap/app';
import '@tmap/app/index.css';
import { createTmapClient } from '@tmap/api-client';
import { getAuthStore } from '@/auth/authStore';
import { WebPlatform } from './platform/WebPlatform';

const API_BASE_URL: string =
  ((import.meta as { env?: { VITE_API_BASE_URL?: string } }).env?.VITE_API_BASE_URL as
    | string
    | undefined) ?? 'http://localhost:5188';

// Web host adapter — cookie-based refresh, Web Notifications, no tray/widget/auto-launch.
const platform = new WebPlatform(API_BASE_URL);

// Raw typed client. `credentials:'include'` lets the browser send the httpOnly
// refresh cookie on the refresh call; the access token is injected per-request from
// the in-memory authStore. AppRoot wraps this once with the 401→refresh layer and
// builds the HttpDataClient over that wrapped client — one shared refresh path.
const tmapClient = createTmapClient({
  baseUrl: API_BASE_URL,
  credentials: 'include',
  getAccessToken: () => {
    try {
      return getAuthStore().getState().accessToken;
    } catch {
      // authStore not yet initialized (first paint before AppRoot init) — no token.
      return null;
    }
  },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AppRoot platform={platform} tmapClient={tmapClient} />
  </React.StrictMode>,
);
