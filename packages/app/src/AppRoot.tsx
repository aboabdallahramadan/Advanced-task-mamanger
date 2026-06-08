import React from 'react';
import App from './App';

/**
 * Q1 STUB — temporary AppRoot.
 *
 * Both apps/desktop and apps/web mount this component. In Q1 it simply renders
 * the existing <App />, which still talks to `window.api` (desktop-only) internally.
 * Q2 introduces the real DataClient seam and refactors the store to call
 * `props.dataClient` instead of window.api; Q3+ wires the Platform/auth shells.
 *
 * The prop shape is fixed now (so host main.tsx call sites are stable), but the
 * concrete DataClient/Platform types arrive in later phases. We use minimal
 * compile-only placeholders here and DO NOT consume the props yet.
 */
export interface AppRootProps {
  /** Q2: typed as DataClient (packages/app/src/data/DataClient.ts). */
  dataClient?: unknown;
  /** Q3: typed as Platform (packages/app/src/platform/Platform.ts). */
  platform?: unknown;
}

export default function AppRoot(_props: AppRootProps): React.ReactElement {
  // Props intentionally unused in Q1; App still self-wires via window.api.
  return <App />;
}
