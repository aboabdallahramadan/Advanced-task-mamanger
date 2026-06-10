import React from 'react';
import ReactDOM from 'react-dom/client';
import { AppRoot } from '@tmap/app';
import '@tmap/app/index.css';
import { createTmapClient } from '@tmap/api-client';
import type { DataClient } from '@/data/DataClient';
import type { Platform } from '@/platform/Platform';

// Q4 stubs — replaced in Q5 when WebPlatform + HttpDataClient land.
// These satisfy the TypeScript shape but are intentionally non-functional at runtime.
const API_BASE = (import.meta as any).env?.VITE_API_BASE_URL ?? 'http://localhost:3000';

const tmapClient = createTmapClient({ baseUrl: API_BASE });

// Minimal Platform stub (web — real WebPlatform wired in Q5).
const platform: Platform = {
  capabilities: {
    tray: false,
    focusWidgetWindow: false,
    autoLaunch: false,
    dataPort: false,
  },
  auth: {
    refreshAndGetAccess: async () => null,
    clear: async () => {},
  },
  notify: () => {},
  on: () => {},
  off: () => {},
};

// Minimal DataClient stub — real HttpDataClient wired in Q5.
const dataClient = {} as DataClient;

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AppRoot dataClient={dataClient} platform={platform} tmapClient={tmapClient} />
  </React.StrictMode>,
);
