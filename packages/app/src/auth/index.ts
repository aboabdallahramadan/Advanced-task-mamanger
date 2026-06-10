// packages/app/src/auth/index.ts
export type { AuthTokenResponse, AuthUser, ProblemDetails } from './types';
export { unwrapAuth, isProblemDetails, problemToMessage } from './types';
export { createRefreshClient } from './refreshClient';
export type { RefreshClient, RefreshClientOptions } from './refreshClient';
export {
  createAuthStore,
  initAuthStore,
  getAuthStore,
  useAuthStore,
} from './authStore';
export type { AuthState, AuthStatus, AuthStoreDeps } from './authStore';
// (temporary until Q4-8)
// export { LoginView } from './LoginView';
// export { RegisterView } from './RegisterView';
