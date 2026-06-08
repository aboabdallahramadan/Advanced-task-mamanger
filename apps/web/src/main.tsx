import React from 'react';
import ReactDOM from 'react-dom/client';
import AppRoot from '@tmap/app';
import '@tmap/app/index.css';

// Q1: thin web renderer entry. AppRoot is the @tmap/app stub (renders <App />).
// NOTE: <App /> still references window.api at runtime; the web host has no
// window.api yet, so deep interactions will error at runtime until Q2 injects a
// DataClient and Q3 provides WebPlatform. Q1's gate is BUILD-only for web.
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AppRoot />
  </React.StrictMode>,
);
