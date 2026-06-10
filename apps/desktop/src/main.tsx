import React from 'react';
import ReactDOM from 'react-dom/client';
import { AppRoot } from '@tmap/app';
import '@tmap/app/index.css';
import { createTmapClient } from '@tmap/api-client';
import type { DataClient } from '@/data/DataClient';
import { DesktopPlatform } from './platform/DesktopPlatform';

const API_BASE = (import.meta as any).env?.VITE_API_BASE_URL ?? 'http://localhost:3000';

const tmapClient = createTmapClient({ baseUrl: API_BASE });

// Desktop host adapter — talks to the Electron preload (`window.api`): secure
// refresh-token store, OS notifications, focus widget window + tray, auto-launch.
const platform = new DesktopPlatform();

// DataClient is wired in a later phase (HttpDataClient over the refresh-wrapped client).
const dataClient = {} as DataClient;

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AppRoot dataClient={dataClient} platform={platform} tmapClient={tmapClient} />
  </React.StrictMode>,
);
