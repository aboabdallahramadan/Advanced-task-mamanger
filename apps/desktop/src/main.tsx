import React from 'react';
import ReactDOM from 'react-dom/client';
import AppRoot from '@tmap/app';
import '@tmap/app/index.css';

// Q1: thin Electron renderer entry. AppRoot is the @tmap/app stub (renders <App />,
// which still self-wires via window.api on desktop). Q3 builds DesktopPlatform +
// HttpDataClient here and passes them: <AppRoot dataClient={...} platform={...} />.
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AppRoot />
  </React.StrictMode>,
);
