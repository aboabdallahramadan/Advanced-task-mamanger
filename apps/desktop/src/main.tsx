import React from 'react';
import ReactDOM from 'react-dom/client';
import { AppRoot } from '@tmap/app';
import '@tmap/app/index.css';
import { createTmapClient } from '@tmap/api-client';
import type { DataClient } from '@/data/DataClient';
import type { Platform } from '@/platform/Platform';

// Q4 stubs — replaced in Q5 when DesktopPlatform + HttpDataClient land.
// These satisfy the TypeScript shape but are intentionally non-functional at runtime.
const API_BASE = (import.meta as any).env?.VITE_API_BASE_URL ?? 'http://localhost:3000';

const tmapClient = createTmapClient({ baseUrl: API_BASE });

// Minimal Platform stub (desktop — real IPC wiring in Q5).
const platform: Platform = {
  capabilities: {
    tray: true,
    focusWidgetWindow: true,
    autoLaunch: true,
    dataPort: true,
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
