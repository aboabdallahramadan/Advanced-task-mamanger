import React from 'react';
import ReactDOM from 'react-dom/client';
import { AppRoot } from '@tmap/app';
import '@tmap/app/index.css';
import { createTmapClient } from '@tmap/api-client';
import { getAuthStore } from '@/auth/authStore';
import { DesktopPlatform } from './platform/DesktopPlatform';
import { API_BASE_URL } from './config';

// Desktop host adapter — talks to the Electron preload (`window.api`): secure
// refresh-token store (in main), OS notifications, focus widget window + tray, auto-launch.
const platform = new DesktopPlatform();

// Raw typed client. The access token is injected per-request from the in-memory
// authStore; the refresh token lives in the main process (safeStorage) and never
// enters the renderer, so no `credentials:'include'` is needed on desktop.
// AppRoot wraps this once with the 401→refresh layer and builds the HttpDataClient
// over that wrapped client — one shared refresh path for all data calls.
const tmapClient = createTmapClient({
  baseUrl: API_BASE_URL,
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
